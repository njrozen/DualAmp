package com.dualamp.mix

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.dualamp.library.LibraryRepository
import com.dualamp.library.PlaylistStore
import com.dualamp.playback.PlaybackService
import com.dualamp.settings.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Plays a mix-mode playlist's transitions, live: while song 1 plays on the main player, song 2 starts on a second
 * player ("deck"), kept on song 1's beat by nudging its speed, and both are shaped by [MixFx] (volume, EQ, filters,
 * echo, loop). When song 1 is gone, the main player takes over song 2 at the deck's exact spot (silently lined up,
 * then a short swap), so the rest of the app (queue, lyrics, notification) just sees the next song begin.
 *
 * Everything here runs on the main thread, ticking every 20 ms while a transition is near.
 */
@UnstableApi
object MixEngine {
    private const val TAG = "MixEngine"
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    /** While the mix editor is open: its unsaved transition is used for its two songs. */
    @Volatile var draft: Transition? = null

    enum class Phase { IDLE, ARMED, MIXING, HANDOVER, ALIGN, CROSS }

    /** What the mixer is doing, for the waveform view: song 1, song 2, and where song 2 is (deck), ms. */
    data class Live(val phase: Phase, val fromId: Long, val toId: Long, val deckPosMs: Long)

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    private class Active(
        val t: Transition,
        val aIndex: Int,
        val bIndex: Int,
        val bpmA: Float,
        val bpmB: Float,
        val lenMs: Long,
    ) {
        val ratio: Double = if (t.beatmatch && bpmA > 0 && bpmB > 0) bpmA.toDouble() / matchedBpm(bpmA, bpmB) else 1.0
        fun expectedB(posA: Double) = t.entryMs + (posA - t.startMs) * ratio
        fun x(posA: Double) = if (lenMs <= 0) (if (posA >= t.startMs) 1.0 else -1.0) else (posA - t.startMs) / lenMs
        /** Song 2 (B_TO_A) eases back to its own tempo over 4 bars after the transition, in units of x. */
        val afterRampX: Double get() = if (lenMs <= 0) 1.0 else (4 * 4 * 60_000.0 / (if (bpmA > 0) bpmA else 120f)) / lenMs
        /** The effect rings on this long (in units of x) after song 1 has gone, before the main player takes over song 2. */
        val tailX: Double get() = if (lenMs <= 0 || !t.effect.atEnd) 0.0
            else t.effect.tailMs(60_000.0 / (if (bpmA > 0) bpmA else 120f)).toDouble() / lenMs
    }

    private var phase = Phase.IDLE
    private var cur: Active? = null
    private var lastPosA = -1.0
    private var lastTick = 0L
    private var lastDeckSpeed = 1f
    private var lastMainSpeed = 1f
    private var phaseStart = 0L
    private var alignSeeks = 0
    private var alignGood = 0
    private var alignTarget = 0L
    private var alignDeckAt = 0L
    private var seekLatency = 250L
    private var lastLog = 0L
    private var mainPlayingSince = 0L

    fun start() {
        if (started) return
        started = true
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val delay = try { step() } catch (e: Exception) {
                Log.w(TAG, "Mixer step failed", e); abort(); 250L
            }
            handler.postDelayed(this, delay)
        }
    }

    /** Song positions, extrapolated to now while playing (ExoPlayer does that itself). */
    private fun pos(p: Player): Double = p.currentPosition.toDouble()

    private fun transitionFor(playlistId: String, from: Long, to: Long): Transition? {
        draft?.let { if (it.fromId == from && it.toId == to) return it }
        val p = PlaylistStore.playlists.value.firstOrNull { it.id == playlistId } ?: return null
        return p.transitions[Transition.key(from, to)]
    }

    private fun step(): Long {
        val main = PlaybackService.exo ?: return 250L.also { reset() }
        val deck = PlaybackService.deck
        val mixId = Prefs.mixPlaylistId.value
        if (mixId.isEmpty() || deck == null) { if (phase != Phase.IDLE) abort(); return 250 }
        if (phase == Phase.HANDOVER || phase == Phase.ALIGN || phase == Phase.CROSS) return handover(main, deck)

        val idx = main.currentMediaItemIndex
        val next = if (main.repeatMode == Player.REPEAT_MODE_ONE) C.INDEX_UNSET else main.nextMediaItemIndex
        val aId = main.currentMediaItem?.mediaId?.toLongOrNull()
        val bId = if (next == C.INDEX_UNSET) null else main.getMediaItemAt(next).mediaId.toLongOrNull()
        val t = if (aId != null && bId != null) transitionFor(mixId, aId, bId) else null
        if (t == null) { if (phase != Phase.IDLE) abort(); return 150 }

        val a = SongAnalyzer[t.fromId]; val b = SongAnalyzer[t.toId]
        if (a == null || b == null) {
            LibraryRepository.library.value?.songById?.let { m -> SongAnalyzer.request(listOfNotNull(m[t.fromId], m[t.toId])) }
        }
        val bpmA = a?.bpm ?: 0f; val bpmB = b?.bpm ?: 0f
        val c0 = cur
        if (c0 == null || c0.aIndex != idx || c0.bIndex != next || c0.t.pairKey != t.pairKey) {
            if (phase != Phase.IDLE) abort()
            cur = Active(t, idx, next, bpmA, bpmB, t.lengthMs(bpmA))
        } else if (c0.t != t || c0.bpmA != bpmA || c0.bpmB != bpmB) {
            // Edited in the mix editor (or analysis arrived): new plans, same decks.
            cur = Active(t, idx, next, bpmA, bpmB, t.lengthMs(bpmA))
            if (phase != Phase.IDLE) setPlans(main, cur!!, a, b)
        }
        val c = cur!!
        val posA = pos(main)
        val now = System.nanoTime()
        // A jump (the user seeked): start again from where song 1 is now.
        if (lastPosA >= 0 && phase != Phase.IDLE && main.isPlaying) {
            val expected = lastPosA + (now - lastTick) / 1e6 * main.playbackParameters.speed
            if (abs(posA - expected) > 1500) { abort(); cur = c }
        }
        lastPosA = posA; lastTick = now
        val x = c.x(posA)
        val endX = maxOf(if (c.t.tempo == MixTempo.B_TO_A && c.ratio != 1.0) 1 + c.afterRampX else 1.0, 1 + c.tailX)
        val duration = main.duration.takeIf { it > 0 } ?: Long.MAX_VALUE

        when (phase) {
            Phase.IDLE -> {
                if (posA >= c.t.startMs - 8000 && x < endX && posA < duration - 500) {
                    deck.volume = 1f
                    deck.setMediaItem(main.getMediaItemAt(c.bIndex), c.expectedB(posA).toLong().coerceAtLeast(0))
                    deck.prepare()
                    deck.playWhenReady = false
                    setPlans(main, c, a, b)
                    phase = Phase.ARMED
                    Log.d(TAG, "armed ${c.t.pairKey} start=${c.t.startMs} entry=${c.t.entryMs} len=${c.lenMs} ratio=${c.ratio}")
                    _live.value = Live(phase, c.t.fromId, c.t.toId, deck.currentPosition)
                }
                return if (posA >= c.t.startMs - 20_000) 40 else 200
            }
            Phase.ARMED -> {
                updateOffsets(main, deck)
                if (x >= endX) { abort(); return 100 }
                // The deck takes about 130 ms to start sounding (measured on the Thor): start it that much ahead.
                val target = c.expectedB(posA + 130)
                if (main.isPlaying && posA >= c.t.startMs - 2500 && target >= 0) {
                    deck.seekTo(target.toLong())
                    deck.play()
                    phase = Phase.MIXING
                    Log.d(TAG, "deck started at ${target.toLong()} (song 1 at ${posA.toLong()})")
                }
                return 20
            }
            Phase.MIXING -> {
                updateOffsets(main, deck)
                if (!main.isPlaying) { if (deck.playWhenReady) deck.pause(); return 40 }
                if (!deck.playWhenReady) deck.play()
                // Tempo: song 1 (main player) and song 2 (deck), then song 2's spot kept on song 1's beat.
                val sA = Automation.speed(c.t, true, x, c.bpmA, c.bpmB).toFloat()
                setMainSpeed(main, sA)
                val sB = Automation.speed(c.t, false, x, c.bpmA, c.bpmB, c.afterRampX)
                var speedB = sB
                if (x < 1.0 && deck.isPlaying) {
                    val err = pos(deck) - c.expectedB(posA)
                    if (now - lastLog > 500_000_000L) {
                        lastLog = now
                        Log.d(TAG, "mixing x=%.3f err=%.0fms speedA=%.4f speedB=%.4f".format(x, err, sA, sB))
                    }
                    if (abs(err) > 400 && x < 0) { Log.d(TAG, "resync (silent) err=$err"); deck.seekTo(c.expectedB(posA + 250).toLong().coerceAtLeast(0)); return 40 }
                    if (abs(err) > 1500) { Log.d(TAG, "resync err=$err"); deck.seekTo(c.expectedB(posA + 250).toLong().coerceAtLeast(0)); return 40 }
                    // Song 2 still silent: catch up hard. Heard: gently, so the tempo barely wavers.
                    speedB = if (x < 0) sB * (1 - (err / 400.0).coerceIn(-0.15, 0.15)) else sB * (1 - (err / 1500.0).coerceIn(-0.05, 0.05))
                }
                setDeckSpeed(deck, speedB.toFloat())
                _live.value = Live(phase, c.t.fromId, c.t.toId, deck.currentPosition)
                // Song 1 is gone (or about to end): the main player takes over song 2.
                if (x >= endX || posA >= duration - 400) startHandover(main, deck, c)
                return 20
            }
            else -> return 20
        }
    }

    private fun setPlans(main: ExoPlayer, c: Active, a: Analysis?, b: Analysis?) {
        val mainFx = PlaybackService.mainFx ?: return
        val deckFx = PlaybackService.deckFx ?: return
        val beatA = ((a?.beatLengthMs ?: 500f) * 1000).toLong()
        val beatB = ((b?.beatLengthMs ?: 500f) * 1000).toLong()
        val loopLen = if (c.t.loop.bars > 0) (c.t.loop.bars * 4L * beatA).coerceAtMost(c.lenMs * 1000) else 0L
        mainFx.plan = DeckPlan(c.t, true, c.t.startMs * 1000, c.lenMs * 1000, beatA,
            loopStartUs = c.t.startMs * 1000, loopLenUs = loopLen,
            endUs = (main.duration.takeIf { it > 0 } ?: Long.MAX_VALUE / 1000) * 1000)
        deckFx.plan = DeckPlan(c.t, false, c.t.entryMs * 1000, (c.lenMs * c.ratio * 1000).toLong(), beatB)
    }

    /** The song-time offset each effect needs ([MixFx.offsetUs]): where the sink is minus where the song is. */
    private fun updateOffsets(main: ExoPlayer, deck: ExoPlayer) {
        PlaybackService.mainFx?.let { update(it, main) }
        PlaybackService.deckFx?.let { update(it, deck) }
    }

    private fun update(fx: MixFx, p: ExoPlayer) {
        val out = fx.outPosUs
        if (out == Long.MIN_VALUE) return
        val now = System.nanoTime()
        val outNow = if (p.isPlaying) out + ((now - fx.outPosNanos) / 1000 * p.playbackParameters.speed).toLong() else out
        val off = outNow - p.currentPosition * 1000
        val old = fx.offsetUs
        fx.offsetUs = if (old == Long.MIN_VALUE || abs(off - old) > 60_000) off else old + (off - old) / 8
    }

    private fun setMainSpeed(main: ExoPlayer, s: Float) {
        if (abs(s - lastMainSpeed) < 0.002f && !(s == 1f && lastMainSpeed != 1f)) return
        lastMainSpeed = s
        main.playbackParameters = PlaybackParameters(s, 1f)
    }

    private fun setDeckSpeed(deck: ExoPlayer, s: Float) {
        if (abs(s - lastDeckSpeed) < 0.0015f) return
        lastDeckSpeed = s
        deck.playbackParameters = PlaybackParameters(s, 1f)
    }

    // -----------------------------------------------------------------------------------------
    // Handover: the main player picks up song 2 where the deck is, muted; once they line up, a quick swap.

    private fun startHandover(main: ExoPlayer, deck: ExoPlayer, c: Active) {
        main.volume = 0f
        PlaybackService.mainFx?.let { it.plan = null; it.offsetUs = Long.MIN_VALUE }
        setMainSpeed(main, 1f)
        // Song 2 at its own tempo for the swap.
        setDeckSpeed(deck, 1f)
        alignDeckAt = deck.currentPosition
        alignTarget = alignDeckAt + seekLatency
        main.seekTo(c.bIndex, alignTarget)
        mainPlayingSince = 0L
        Log.d(TAG, "handover: song 2 at ${alignDeckAt} ms on the deck")
        phase = Phase.ALIGN
        phaseStart = System.nanoTime()
        alignSeeks = 1
        alignGood = 0
        _live.value = Live(phase, c.t.fromId, c.t.toId, deck.currentPosition)
    }

    private fun handover(main: ExoPlayer, deck: ExoPlayer): Long {
        val c = cur ?: return 20L.also { finish(main, deck) }
        val elapsedMs = (System.nanoTime() - phaseStart) / 1_000_000
        if (main.currentMediaItemIndex != c.bIndex || deck.playbackState == Player.STATE_ENDED || deck.playbackState == Player.STATE_IDLE) {
            // Skipped away meanwhile, or the deck stopped: just play on the main player.
            finish(main, deck); return 20
        }
        if (!main.playWhenReady) { if (deck.playWhenReady) deck.pause(); return 40 }
        if (!deck.playWhenReady) deck.play()
        when (phase) {
            Phase.ALIGN -> {
                if (!main.isPlaying || main.playbackState != Player.STATE_READY) {
                    mainPlayingSince = 0L
                    if (elapsedMs > 6000) startCross()
                    return 10
                }
                // Let the main player's clock settle for a moment after it starts.
                val now = System.nanoTime()
                if (mainPlayingSince == 0L) mainPlayingSince = now
                if (now - mainPlayingSince < 200_000_000L) return 10
                val err = pos(deck) - pos(main) // > 0: the deck is ahead
                if (abs(err) > 60 && alignSeeks < 4) {
                    // Learn how long a seek takes to play and try again.
                    val measured = (alignTarget - alignDeckAt) + err
                    Log.d(TAG, "handover: off by ${err.toLong()} ms, a seek takes ${measured.toLong()} ms")
                    if (measured in 20.0..2000.0) seekLatency = measured.toLong()
                    alignDeckAt = deck.currentPosition
                    alignTarget = alignDeckAt + seekLatency
                    main.seekTo(alignTarget)
                    mainPlayingSince = 0L
                    alignSeeks++
                    return 20
                }
                setDeckSpeed(deck, (1 - (err / 600.0).coerceIn(-0.04, 0.04)).toFloat())
                alignGood = if (abs(err) < 8) alignGood + 1 else 0
                if (alignGood >= 3 || elapsedMs > 5000) {
                    Log.d(TAG, "handover: lined up (${err.toLong()} ms) after ${elapsedMs} ms, $alignSeeks seeks")
                    startCross()
                }
                return 10
            }
            Phase.CROSS -> {
                val k = ((System.nanoTime() - phaseStart) / 1e6 / 250.0).coerceIn(0.0, 1.0).toFloat()
                main.volume = k
                deck.volume = 1 - k
                if (k >= 1f) finish(main, deck)
                return 10
            }
            else -> return 20
        }
    }

    private fun startCross() {
        phase = Phase.CROSS
        phaseStart = System.nanoTime()
    }

    private fun finish(main: ExoPlayer, deck: ExoPlayer) {
        main.volume = 1f
        setMainSpeed(main, 1f)
        deck.stop()
        deck.volume = 1f
        setDeckSpeed(deck, 1f)
        PlaybackService.deckFx?.plan = null
        PlaybackService.mainFx?.plan = null
        reset()
    }

    /** Stops a transition half way (a skip, a seek, mix mode turned off): song 1 back to normal, the deck stopped. */
    fun abort() {
        val main = PlaybackService.exo
        val deck = PlaybackService.deck
        if (main != null) { main.volume = 1f; if (main.playbackParameters.speed != 1f) main.playbackParameters = PlaybackParameters(1f, 1f) }
        lastMainSpeed = 1f
        if (deck != null) { deck.stop(); deck.volume = 1f; deck.playbackParameters = PlaybackParameters(1f, 1f) }
        lastDeckSpeed = 1f
        PlaybackService.deckFx?.plan = null
        PlaybackService.mainFx?.plan = null
        reset()
    }

    private fun reset() {
        phase = Phase.IDLE
        cur = null
        lastPosA = -1.0
        _live.value = null
    }

    /** Where song 2 is on the deck right now (ms), during a transition. */
    fun deckPositionMs(): Long? = if (phase == Phase.MIXING || phase == Phase.ALIGN || phase == Phase.CROSS) PlaybackService.deck?.currentPosition else null
}
