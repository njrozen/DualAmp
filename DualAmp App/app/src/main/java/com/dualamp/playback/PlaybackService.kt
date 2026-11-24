package com.dualamp.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.dualamp.audio.DspProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dualamp.LauncherActivity
import com.dualamp.library.LibraryRepository
import com.dualamp.library.Song
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Background playback with a media notification, lock-screen controls and headset buttons. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var deckPlayer: ExoPlayer? = null
    private var mainFxRef: com.dualamp.mix.MixFx? = null
    private var deckFxRef: com.dualamp.mix.MixFx? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val fx = com.dualamp.mix.MixFx().also { mainFxRef = it }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                // DualAmp's own DSP (equalizer, tone, width, limiter) runs inside the sink.
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(fx, DspProcessor()))
                    .build()
                return TapAudioSink(com.dualamp.mix.MixClockSink(sink, fx))
            }
        }.setEnableDecoderFallback(true)
            // Tempo changes (mix mode's beatmatching) are done by Android's own time-stretch, which follows small changes
            // smoothly; ExoPlayer's would drain and restart the audio chain on each one (clicks, stutter).
            .setEnableAudioTrackPlaybackParams(true)

        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // Audio focus is handled by ScreenAudioFocus (so the Thor applies the right screen's volume).
                /* handleAudioFocus = */ false,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        ScreenAudioFocus.attach(this, player)
        // A PC song that can't load almost always means the PC went away: tell the user.
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                if (id < 0) com.dualamp.library.RemoteLibrary.reportProblem(
                    "A song from your PC couldn't play: DualAmp couldn't reach the PC.", always = true,
                )
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Items sent from a controller lose their URI; rebuild it from the MediaStore id.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val id = item.mediaId.toLongOrNull()
                        if (item.localConfiguration == null && id != null) {
                            // PC songs (negative ids) stream from their server address.
                            val uri = if (id < 0) LibraryRepository.library.value?.songById?.get(id)?.uri else Song.contentUri(id)
                            if (uri != null) item.buildUpon().setUri(uri).build() else item
                        } else item
                    }.toMutableList()
                )
            })
            .build()
        deckPlayer = buildDeck()
        com.dualamp.mix.MixEngine.start()
    }

    /** The mixer's second player: song 2 during a transition (see [com.dualamp.mix.MixEngine]). */
    private fun buildDeck(): ExoPlayer {
        val fx = com.dualamp.mix.MixFx().also { deckFxRef = it }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                com.dualamp.mix.MixClockSink(DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(fx, DspProcessor()))
                    .build(), fx)
        }.setEnableDecoderFallback(true)
            // Tempo changes (mix mode's beatmatching) are done by Android's own time-stretch, which follows small changes
            // smoothly; ExoPlayer's would drain and restart the audio chain on each one (clicks, stutter).
            .setEnableAudioTrackPlaybackParams(true)
        return ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // One screen was swiped away in Recents: the two screens are one app, so the other one closes too
        // (backs up the screens' own onDestroy, which some launchers' Recents skip).
        com.dualamp.AppEvents.taskRemoved()
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        com.dualamp.mix.MixEngine.abort()
        deckPlayer?.release()
        deckPlayer = null
        ScreenAudioFocus.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: PlaybackService? = null

        /**
         * DualAmp was quit: empty the player (so Android's media controls and the notification drop it; the queue is
         * saved in Prefs and put back on the next start) and stop the service. Main thread.
         */
        fun quit() {
            val s = instance ?: return
            s.session?.player?.run { stop(); clearMediaItems() }
            s.stopSelf()
        }

        /** The player itself (same process): for what a MediaController can't do, like the shuffle order. Main thread. */
        val exo: ExoPlayer? get() = instance?.session?.player as? ExoPlayer

        /** The mixer's second player and the effects on each player. */
        val deck: ExoPlayer? get() = instance?.deckPlayer
        val mainFx: com.dualamp.mix.MixFx? get() = instance?.mainFxRef
        val deckFx: com.dualamp.mix.MixFx? get() = instance?.deckFxRef

        /** Every queue position in the order it plays (follows shuffle). */
        fun playOrder(p: androidx.media3.common.Player): List<Int> {
            val t = p.currentTimeline
            if (t.isEmpty) return emptyList()
            val out = ArrayList<Int>(t.windowCount)
            var i = t.getFirstWindowIndex(p.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && out.size < t.windowCount) {
                out += i
                i = t.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            }
            return out
        }

        /**
         * Puts [items] next in play order, shuffled or not: straight after the current song, or with [afterQueued] after
         * the songs already queued there ([isQueued]). So Play next / Add to queue come before the rest of an album or
         * playlist. Returns false when the player isn't there yet.
         */
        fun insertUpNext(items: List<MediaItem>, afterQueued: Boolean): Boolean {
            val p = exo ?: return false
            if (items.isEmpty()) return true
            if (p.mediaItemCount == 0) return false
            val order = playOrder(p)
            var at = order.indexOf(p.currentMediaItemIndex).coerceAtLeast(0)
            if (afterQueued) while (at + 1 < order.size && isQueued(p.getMediaItemAt(order[at + 1]))) at++
            val listIndex = order[at] + 1
            p.addMediaItems(listIndex, items)
            if (p.shuffleModeEnabled) {
                // The player shuffles new songs in anywhere: put them where they belong in the play order.
                val newOrder = orderWithInserted(order, at, listIndex, items.size)
                p.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(newOrder.toIntArray(), System.nanoTime()))
            }
            return true
        }

        /** Reshuffles the whole queue (a shuffled playlist starting over). */
        fun reshuffle() {
            val p = exo ?: return
            p.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(p.mediaItemCount, System.nanoTime()))
        }

        const val EXTRA_FROM = "dualamp.from"
        /** Added with Play next / Add to queue. */
        const val FROM_QUEUE = "queue"
        /** Added by Autoplay when the album, artist or songs ran out. */
        const val FROM_AUTOPLAY = "autoplay"

        fun fromOf(item: MediaItem): String? = item.mediaMetadata.extras?.getString(EXTRA_FROM)
        fun isQueued(item: MediaItem) = fromOf(item) == FROM_QUEUE
    }
}
