package com.dualamp.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dualamp.library.LibraryRepository
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

    fun connect(context: Context) {
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
                    // Queue ran out and it was not an album or playlist: keep going with a random song.
                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED &&
                        continueWithRandom && player.repeatMode == Player.REPEAT_MODE_OFF) playRandom()
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
     * When true, a random library song is added once the queue runs out. Off while playing an album or
     * playlist, which simply end.
     */
    var continueWithRandom = true

    /** [fromCollection]: the songs are a whole album or playlist, which should end when finished. */
    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, fromCollection: Boolean = false) {
        continueWithRandom = !fromCollection
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle && startIndex == 0) songs.indices.random() else startIndex.coerceIn(songs.indices)
        c.setMediaItems(songs.map(Song::toMediaItem), start, 0)
        c.prepare()
        c.play()
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(songs)
        c.addMediaItems(c.currentMediaItemIndex + 1, songs.map(Song::toMediaItem))
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(songs)
        c.addMediaItems(songs.map(Song::toMediaItem))
    }

    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0)
        c.play()
    }

    /** Moves a queued song so it plays right after the current one. */
    fun moveToNext(index: Int) {
        val c = controller ?: return
        val target = c.currentMediaItemIndex + if (index > c.currentMediaItemIndex) 1 else 0
        if (index != c.currentMediaItemIndex) c.moveMediaItem(index, target)
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

    /** Next song; at the end of the queue (or with no queue) this starts a random song from the library. */
    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else playRandom()
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
     * played before it) is removed. When the song ends, a random song follows as usual.
     */
    fun clearQueue() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (c.mediaItemCount == 0 || current < 0) return
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
        continueWithRandom = true
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
