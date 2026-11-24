package com.dualamp.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dualamp.library.ArtistNames
import com.dualamp.library.LibraryRepository
import com.dualamp.library.artistKey
import com.dualamp.library.Song
import com.dualamp.settings.Prefs
import com.dualamp.viz.VizControl
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlayerState(
    val connected: Boolean = false,
    val songId: Long? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Long> = emptyList(),
    val queueIndex: Int = 0,
    /** Queue positions still to play after the current song, in play order (follows shuffle). */
    val upNext: List<Int> = emptyList(),
)

/**
 * Shared connection to [PlaybackService], used by both screens.
 * All calls must happen on the main thread (MediaController's requirement).
 */
object PlayerConnection {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val positionMs: Long get() = controller?.currentPosition ?: 0

    private var appContext: Context? = null

    /** At start, and again from the screens after a quit ([stopForQuit]); does nothing while connected. */
    fun connect(context: Context) {
        appContext = context.applicationContext
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = f.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    publish()
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) saveQueue()
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) VizControl.songChanged()
                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) savePosition()
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) scope.launch { topUp() }
                    // The queue ran out: a playlist starts over; anything else goes on with Autoplay (if on).
                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED &&
                        player.repeatMode == Player.REPEAT_MODE_OFF) scope.launch { if (fromPlaylist) restartPlaylist() else if (Prefs.autoplay.value) playSimilar() }
                }
            })
            publish()
            restoreQueue()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun publish() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() }
        _state.value = PlayerState(
            connected = true,
            songId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            queueIndex = c.currentMediaItemIndex,
            upNext = upNextOf(c),
        )
    }

    /**
     * The queue is a playlist: when it ends it starts over (reshuffled when shuffle is on), so it never runs out.
     * Anything else (album, artist, songs) is followed by similar songs when Autoplay is on, or stops.
     */
    val fromPlaylist: Boolean get() = Prefs.queueFromPlaylist.value

    /**
     * [playlist]: the songs are a playlist, which starts over when it ends. [mixPlaylistId]: that playlist plays in mix
     * mode (in order, with its transitions), from [startMs] of the first song.
     */
    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, playlist: Boolean = false,
                  mixPlaylistId: String? = null, startMs: Long = 0) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        Prefs.queueFromPlaylist.value = playlist || mixPlaylistId != null
        Prefs.mixPlaylistId.value = mixPlaylistId.orEmpty()
        val shuffled = shuffle && mixPlaylistId == null
        c.shuffleModeEnabled = shuffled
        if (mixPlaylistId != null) com.dualamp.mix.SongAnalyzer.request(songs)
        val start = if (shuffled && startIndex == 0) songs.indices.random() else startIndex.coerceIn(songs.indices)
        c.setMediaItems(songs.map { it.toMediaItem() }, start, startMs.coerceAtLeast(0))
        c.prepare()
        c.play()
    }

    /** Straight after the current song, before the rest of the album or playlist (shuffled or not). */
    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(songs)
        val items = songs.map { it.toMediaItem(PlaybackService.FROM_QUEUE) }
        if (!PlaybackService.insertUpNext(items, afterQueued = false)) c.addMediaItems(c.currentMediaItemIndex + 1, items)
    }

    /** After the songs already queued, still before the rest of the album or playlist (shuffled or not). */
    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(songs)
        val items = songs.map { it.toMediaItem(PlaybackService.FROM_QUEUE) }
        if (!PlaybackService.insertUpNext(items, afterQueued = true)) c.addMediaItems(items)
    }

    /**
     * Autoplay: while the last song plays, the next similar one is already lined up after it, so it follows without a
     * gap (and shows in the queue).
     */
    private fun topUp() {
        val p = PlaybackService.exo ?: return
        if (fromPlaylist || !Prefs.autoplay.value || p.repeatMode != Player.REPEAT_MODE_OFF || p.mediaItemCount == 0) return
        if (p.hasNextMediaItem() || p.playbackState == Player.STATE_ENDED) return
        val s = similarSong() ?: return
        PlaybackService.insertUpNext(listOf(s.toMediaItem(PlaybackService.FROM_AUTOPLAY)), afterQueued = true)
    }

    /** Settings > Autoplay changed: line up a song now, or take out the ones it lined up. */
    fun autoplayChanged() {
        val p = PlaybackService.exo ?: return
        if (Prefs.autoplay.value) return topUp()
        // Only the ones still to come (in play order), last first so the positions stay right.
        val upcoming = PlaybackService.playOrder(p).dropWhile { it != p.currentMediaItemIndex }.drop(1)
        upcoming.filter { PlaybackService.fromOf(p.getMediaItemAt(it)) == PlaybackService.FROM_AUTOPLAY }
            .sortedDescending().forEach { p.removeMediaItem(it) }
    }

    /** The playlist reached its end: from the top again, reshuffled with shuffle on. Songs queued on top of it played once. */
    private fun restartPlaylist() {
        val p = PlaybackService.exo ?: return
        val own = (0 until p.mediaItemCount).count { PlaybackService.fromOf(p.getMediaItemAt(it)) == null }
        if (own > 0) for (i in p.mediaItemCount - 1 downTo 0) if (PlaybackService.fromOf(p.getMediaItemAt(i)) != null) p.removeMediaItem(i)
        if (p.mediaItemCount == 0) return
        if (p.shuffleModeEnabled) PlaybackService.reshuffle()
        p.seekTo(p.currentTimeline.getFirstWindowIndex(p.shuffleModeEnabled), 0)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    /** Lines up a song like the ones played lately and plays it (Autoplay, or Next at the end of the queue). */
    private fun playSimilar() {
        val p = PlaybackService.exo ?: return playRandom()
        val s = similarSong() ?: return playRandom()
        if (!PlaybackService.insertUpNext(listOf(s.toMediaItem(PlaybackService.FROM_AUTOPLAY)), afterQueued = true)) return playRandom()
        p.seekToNextMediaItem()
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    /**
     * A song like the last ones played: sharing their genres, artists (featured ones too) and era, and not played lately.
     * Picked at random from the 30 closest, so Autoplay doesn't go round in circles.
     */
    private fun similarSong(): Song? {
        val lib = LibraryRepository.library.value ?: return null
        if (lib.songs.isEmpty()) return null
        val p = PlaybackService.exo
        val ids = if (p == null) _state.value.queue else (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).mediaId.toLongOrNull() }
        val played = if (p == null) ids else PlaybackService.playOrder(p).let { o -> o.take(o.indexOf(p.currentMediaItemIndex) + 1) }.map { ids[it] }
        val recent = ids.toSet()
        val seeds = played.takeLast(10).mapNotNull { lib.songById[it] }
        val fresh = lib.songs.filter { it.id !in recent }.ifEmpty { lib.songs }
        if (seeds.isEmpty()) return fresh.random()
        val genres = seeds.flatMap { s -> s.genres.map { it.lowercase() } }.toSet()
        val artists = seeds.flatMap { s -> ArtistNames.credited(s) + s.primaryArtist }.map { artistKey(it) }.toSet()
        val years = seeds.map { it.year }.filter { it > 0 }
        val year = if (years.isEmpty()) 0 else years.average().toInt()
        fun score(s: Song): Int {
            var n = 0
            if (s.genres.any { it.lowercase() in genres }) n += 3
            if ((ArtistNames.credited(s) + s.primaryArtist).any { artistKey(it) in artists }) n += 2
            if (year > 0 && s.year > 0 && kotlin.math.abs(s.year - year) <= 4) n += 1
            return n
        }
        val scored = fresh.map { it to score(it) }.filter { it.second > 0 }
        if (scored.isEmpty()) return fresh.random()
        return scored.shuffled().sortedByDescending { it.second }.take(30).random().first
    }

    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0)
        c.play()
    }

    /** Moves a queued song so it plays right after the current one (shuffled or not). */
    fun moveToNext(index: Int) {
        val c = controller ?: return
        if (index == c.currentMediaItemIndex) return
        val p = PlaybackService.exo
        val song = c.getMediaItemAt(index).mediaId.toLongOrNull()?.let { LibraryRepository.library.value?.songById?.get(it) }
        if (p != null && song != null) {
            p.removeMediaItem(index)
            PlaybackService.insertUpNext(listOf(song.toMediaItem(PlaybackService.FROM_QUEUE)), afterQueued = false)
        } else c.moveMediaItem(index, c.currentMediaItemIndex + if (index > c.currentMediaItemIndex) 1 else 0)
    }

    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playRandom() // nothing queued: play pressed starts something
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    /** Next song; at the end of the queue a playlist starts over, anything else goes on with a similar song. */
    fun next() {
        val c = controller ?: return
        when {
            c.hasNextMediaItem() -> c.seekToNextMediaItem()
            c.mediaItemCount == 0 -> playRandom()
            fromPlaylist -> restartPlaylist()
            else -> playSimilar()
        }
    }

    /** Adds a random song from the library to the end of the queue and plays it. */
    fun playRandom() {
        val c = controller ?: return
        val songs = LibraryRepository.library.value?.songs.orEmpty()
        if (songs.isEmpty()) return
        val recent = _state.value.queue.takeLast(50).toSet()
        val pick = songs.filter { it.id !in recent }.ifEmpty { songs }.random()
        c.addMediaItem(pick.toMediaItem())
        c.seekTo(c.mediaItemCount - 1, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    /**
     * Empties the queue but keeps the current song playing: everything after it (and the songs already
     * played before it) is removed. When the song ends, Autoplay follows (if on).
     */
    fun clearQueue() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (c.mediaItemCount == 0 || current < 0) return
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
        Prefs.queueFromPlaylist.value = false
        Prefs.mixPlaylistId.value = ""
    }

    /** Restarts the song if more than 3 s in, otherwise goes to the previous one. */
    fun previous() = controller?.seekToPrevious() ?: Unit

    fun seekTo(ms: Long) = controller?.seekTo(ms) ?: Unit

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun saveQueue() {
        val s = _state.value
        Prefs.queueIds.value = s.queue.joinToString(",")
        Prefs.queueIndex.value = s.queueIndex
    }

    /**
     * Quitting DualAmp: stop the music (the queue and position are kept for next time) and end the playback service,
     * so Android's media controls drop DualAmp and the lid-closed buttons rest until DualAmp opens again ([connect]).
     */
    fun stopForQuit() {
        val c = controller ?: return
        savePosition()
        c.pause()
        c.stop()
        // Disconnect first, so emptying the player below is not saved over the queue.
        c.release()
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
        _state.value = PlayerState()
        PlaybackService.quit()
        appContext?.let { it.stopService(android.content.Intent(it, PlaybackService::class.java)) }
    }

    fun savePosition() {
        val c = controller ?: return
        Prefs.queuePositionMs.value = c.currentPosition.toInt()
        Prefs.queueIndex.value = c.currentMediaItemIndex
    }

    /** Puts the last session's queue back (paused) once the library is loaded. */
    private fun restoreQueue() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val ids = Prefs.queueIds.value.split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return
        scope.launch {
            val lib = LibraryRepository.library.filterNotNull().first()
            if (c.mediaItemCount > 0) return@launch
            val savedIndex = Prefs.queueIndex.value
            val savedId = ids.getOrNull(savedIndex)
            val items: List<MediaItem> = ids.mapNotNull { lib.songById[it]?.toMediaItem() }
            if (items.isEmpty()) return@launch
            val index = items.indexOfFirst { it.mediaId == savedId?.toString() }.coerceAtLeast(0)
            c.setMediaItems(items, index, Prefs.queuePositionMs.value.toLong())
            c.prepare()
        }
    }
}

/** Positions that will play after the current one, in the order the player will play them. */
private fun upNextOf(c: Player): List<Int> {
    val timeline = c.currentTimeline
    val current = c.currentMediaItemIndex
    if (timeline.isEmpty || current < 0) return emptyList()
    val out = ArrayList<Int>()
    var i = timeline.getNextWindowIndex(current, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
    while (i != androidx.media3.common.C.INDEX_UNSET && out.size < 5000) {
        out += i
        i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
    }
    return out
}
