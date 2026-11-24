package com.dualamp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.dualamp.input.ControllerBus
import com.dualamp.library.LibraryRepository
import com.dualamp.library.Lyrics
import com.dualamp.library.LyricsRepository
import com.dualamp.library.PlaylistStore
import com.dualamp.playback.PlayerConnection
import com.dualamp.settings.Prefs
import com.dualamp.ui.Accent
import com.dualamp.ui.Surface1
import com.dualamp.ui.Surface2
import com.dualamp.ui.TextDim
import com.dualamp.ui.TextMain
import com.dualamp.ui.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The bottom screen's own now-playing view (tap the song in the player bar): a big progress bar, big buttons (previous,
 * back 10 s, play / pause, forward 10 s, next; like, add to playlist, shuffle, repeat, lyrics, queue) and the lyrics.
 * Lyrics shown here leave the top screen (it keeps just the visualizer) and go back there when this closes.
 * Back arrow, B or Back closes it; the gamepad moves along the buttons (A presses).
 */
object BottomPlayer {
    val open = MutableStateFlow(false)
    /** The lyrics are on the bottom screen (while [open]) instead of over the visualizer. */
    val lyricsHere: Boolean get() = open.value && Prefs.lyricsOnBottom.value

    /** The gamepad's button: row 0 the transport, row 1 the rest. */
    var row by mutableIntStateOf(0)
    var col by mutableIntStateOf(2)

    fun close() { open.value = false }

    fun openUp() { row = 0; col = 2; open.value = true }

    private fun buttons(row: Int): List<() -> Unit> = if (row == 0) listOf(
        PlayerConnection::previous, { seekBy(-10_000) }, PlayerConnection::togglePlay, { seekBy(10_000) }, PlayerConnection::next,
    ) else listOf(
        { PlayerConnection.state.value.songId?.let(PlaylistStore::toggleLike) }, { addToPlaylist() }, PlayerConnection::toggleShuffle,
        PlayerConnection::cycleRepeat, { Prefs.lyricsOnBottom.value = !Prefs.lyricsOnBottom.value }, { openQueue() },
    )

    /** Set by the library screen: its playlist menu, and its Queue tab. */
    var addToPlaylist: () -> Unit = {}
    var openQueue: () -> Unit = {}
    /** The song's menu (go to artist / album, edit song info...), from tapping its title. */
    var songMenu: (com.dualamp.library.Song) -> Unit = {}

    fun seekBy(ms: Long) {
        val dur = PlayerConnection.state.value.durationMs
        PlayerConnection.seekTo((PlayerConnection.positionMs + ms).coerceIn(0, if (dur > 0) dur - 500 else Long.MAX_VALUE))
    }

    /** Gamepad while open; false for what it leaves to the library screen. */
    fun handle(nav: ControllerBus.Nav): Boolean {
        when (nav) {
            ControllerBus.Nav.BACK -> close()
            ControllerBus.Nav.LEFT -> col = (col - 1).coerceAtLeast(0)
            ControllerBus.Nav.RIGHT -> col = (col + 1).coerceAtMost(buttons(row).lastIndex)
            ControllerBus.Nav.UP -> if (row > 0) { row = 0; col = col.coerceAtMost(buttons(0).lastIndex) }
            ControllerBus.Nav.DOWN -> if (row < 1) { row = 1; col = col.coerceAtMost(buttons(1).lastIndex) }
            ControllerBus.Nav.SELECT -> buttons(row).getOrNull(col)?.invoke()
            else -> return false
        }
        return true
    }
}

@Composable
internal fun BottomPlayerView(usingPad: Boolean) {
    val player by PlayerConnection.state.collectAsState()
    val lib by LibraryRepository.library.collectAsState()
    val pcLib by LibraryRepository.pcLibrary.collectAsState()
    val song = player.songId?.let { lib?.songById?.get(it) ?: pcLib?.songById?.get(it) }
    if (song == null) { LaunchedEffect(Unit) { BottomPlayer.close() }; return }
    val lyricsHere by Prefs.lyricsOnBottom.state.collectAsState()
    val artBg by Prefs.playerArtBackground.state.collectAsState()
    val dim by Prefs.playerArtDim.state.collectAsState()
    val blur by Prefs.playerArtBlur.state.collectAsState()
    val waveform by Prefs.playerWaveform.state.collectAsState()
    val playlists by PlaylistStore.playlists.collectAsState()
    val liked = playlists.firstOrNull { it.id == PlaylistStore.LIKED_ID }?.songIds?.contains(song.id) == true

    // Position, a few times a second; the bar follows your finger while you drag it.
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(song.id) { while (true) { position = PlayerConnection.positionMs; delay(200) } }
    val dur = player.durationMs.coerceAtLeast(1)

    fun focused(r: Int, c: Int) = usingPad && BottomPlayer.row == r && BottomPlayer.col == c
    // Over the album art, the text and buttons sit on a darkened panel (Settings > Background behind the buttons).
    val panel: Modifier = if (artBg) Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = dim / 100f)) else Modifier

    Box(Modifier.fillMaxSize().background(Surface1).clickable(enabled = false) {}) {
        if (artBg) BlurredArt(song.albumId, song.id, blur)
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val wide = maxWidth > maxHeight * 1.15f
            val controls: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.then(panel).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    // The buttons shrink to fit narrow space (portrait with the lyrics), keeping their proportions.
                    val k = (maxWidth / 380.dp).coerceIn(0.6f, if (lyricsHere && !wide) 1f else 1.25f)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Tap the title or artist: the song's menu (go to artist / album, edit song info...).
                        Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { BottomPlayer.songMenu(song) }.padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                                modifier = Modifier.scrollIfLong())
                            Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "), color = TextDim,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.scrollIfLong())
                        }
                        Spacer(Modifier.height(6.dp))
                        // Settings > Waveform progress bar: the song's waveform, like the top screen's.
                        if (waveform) com.dualamp.ui.WaveformBar(
                            song = song,
                            fraction = dragging ?: (position.toFloat() / dur).coerceIn(0f, 1f),
                            onDrag = { dragging = it },
                            onRelease = { dragging?.let { PlayerConnection.seekTo((it * dur).toLong()); position = (it * dur).toLong() }; dragging = null },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            played = TextMain,
                        ) else Slider(
                            value = dragging ?: (position.toFloat() / dur).coerceIn(0f, 1f),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = { dragging?.let { PlayerConnection.seekTo((it * dur).toLong()); position = (it * dur).toLong() }; dragging = null },
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = TextMain.copy(alpha = 0.18f)),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatTime(dragging?.let { (it * dur).toLong() } ?: position), color = TextDim, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text(formatTime(player.durationMs), color = TextDim, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp * k))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(Icons.Rounded.SkipPrevious, "Previous", 60.dp * k, focused(0, 0), PlayerConnection::previous)
                            RoundButton(Icons.Rounded.Replay10, "Back 10 seconds", 60.dp * k, focused(0, 1), { BottomPlayer.seekBy(-10_000) })
                            RoundButton(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play / pause", 84.dp * k, focused(0, 2),
                                PlayerConnection::togglePlay, filled = true)
                            RoundButton(Icons.Rounded.Forward10, "Forward 10 seconds", 60.dp * k, focused(0, 3), { BottomPlayer.seekBy(10_000) })
                            RoundButton(Icons.Rounded.SkipNext, "Next", 60.dp * k, focused(0, 4), PlayerConnection::next)
                        }
                        Spacer(Modifier.height(10.dp * k))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp + 6.dp * k), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like", 48.dp * k, focused(1, 0),
                                { PlaylistStore.toggleLike(song.id) }, on = liked)
                            RoundButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", 48.dp * k, focused(1, 1), BottomPlayer.addToPlaylist)
                            RoundButton(Icons.Rounded.Shuffle, "Shuffle", 48.dp * k, focused(1, 2), PlayerConnection::toggleShuffle, on = player.shuffle)
                            RoundButton(if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", 48.dp * k,
                                focused(1, 3), PlayerConnection::cycleRepeat, on = player.repeatMode != Player.REPEAT_MODE_OFF)
                            RoundButton(Icons.Rounded.Lyrics, "Lyrics", 48.dp * k, focused(1, 4), { Prefs.lyricsOnBottom.value = !lyricsHere }, on = lyricsHere)
                            RoundButton(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", 48.dp * k, focused(1, 5), BottomPlayer.openQueue)
                        }
                    }
                }
            }
            // No artwork here (it's on the other screen): the buttons get the room. Lyrics, when on, sit beside or above them.
            val top: @Composable (Modifier) -> Unit = { mod ->
                BottomLyrics(song, player.durationMs, mod, if (artBg) Color.Black.copy(alpha = dim / 100f) else Surface2)
            }

            Column(Modifier.fillMaxSize()) {
                Row(Modifier.then(panel).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { BottomPlayer.close() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to the library", tint = TextMain)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Now playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextMain)
                    Spacer(Modifier.width(16.dp))
                    Text("B: back · Y: menu", style = MaterialTheme.typography.labelMedium, color = TextDim)
                }
                Spacer(Modifier.height(8.dp))
                if (!lyricsHere) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    controls(Modifier.fillMaxWidth().widthIn(max = 760.dp))
                } else if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    top(Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp))
                    controls(Modifier.weight(1.1f))
                } else {
                    top(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp))
                    controls(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The song's album art filling the view, blurred and a little darkened (Settings > Album art background). */
@Composable
private fun BlurredArt(albumId: Long, songId: Long, blurDp: Int) {
    val context = LocalContext.current
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(com.dualamp.library.Artwork.cached(albumId, 400), albumId) {
        value = com.dualamp.library.Artwork.cached(albumId, 400) ?: com.dualamp.library.Artwork.load(context, albumId, songId, 400)
    }
    val img = image ?: return
    androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize().let { if (blurDp > 0) it.blur(blurDp.dp) else it }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Dp, focused: Boolean, onClick: () -> Unit, filled: Boolean = false, on: Boolean = false) {
    Box(
        Modifier.size(size).highlight(focused).clip(CircleShape)
            .background(if (filled) Accent else Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = when { filled -> MaterialTheme.colorScheme.onPrimary; on -> Accent; else -> TextMain },
            modifier = Modifier.size(size * 0.5f))
    }
}

/** The lyrics on the bottom screen: every line, the one being sung bright and kept in the middle. */
@Composable
private fun BottomLyrics(song: com.dualamp.library.Song, durationMs: Long, modifier: Modifier, bg: Color = Surface2) {
    val context = LocalContext.current
    val revision by LyricsRepository.revision.collectAsState()
    val loaded by produceState<Pair<Boolean, Lyrics?>>(false to null, song.id, revision) {
        value = false to null
        value = true to LyricsRepository.load(context, song)
    }
    val (done, lyrics) = loaded
    val offset by Prefs.lyricsOffsetMs.state.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lyrics) { while (lyrics != null) { pos = PlayerConnection.positionMs; delay(100) } }
    val index = lyrics?.indexAt(pos + offset, durationMs) ?: -1
    val list = rememberLazyListState()
    // Keep the line being sung about a third of the way down.
    var height by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(index, lyrics) { if (lyrics != null && index >= 0) list.animateScrollToItem(index, -(height / 3).toInt()) }

    Box(modifier.clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) {
        when {
            !done -> Text("Looking for lyrics…", color = TextDim)
            lyrics == null -> Text("No lyrics for this song", color = TextDim)
            else -> LazyColumn(
                state = list,
                contentPadding = PaddingValues(vertical = 40.dp, horizontal = 16.dp),
                modifier = Modifier.fillMaxSize().onSizeChanged { height = it.height.toFloat() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(lyrics.lines) { i, line ->
                    val now = i == index
                    Text(
                        line.text.ifBlank { "♪" },
                        color = if (now) TextMain else TextDim.copy(alpha = if (i < index) 0.55f else 0.85f),
                        style = if (now) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    )
                }
            }
        }
    }
}
