package com.dualamp.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.draw.blur
import com.dualamp.ui.library.Page
import com.dualamp.ui.library.LibraryNav
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import com.dualamp.audio.Waveform
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Category
import com.dualamp.viz.VizFolders
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.NavigateBefore
import androidx.compose.material.icons.rounded.NavigateNext
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import com.dualamp.audio.AudioTap
import com.dualamp.library.LibraryRepository
import com.dualamp.library.PlaylistStore
import com.dualamp.library.Song
import com.dualamp.playback.PlayerConnection
import com.dualamp.settings.Prefs
import com.dualamp.viz.MilkSurfaceView
import com.dualamp.viz.VizControl
import kotlinx.coroutines.delay

/**
 * Top screen: MilkDrop visualizer filling the screen, with the song info layered over it.
 * The info fades out after a few seconds without input; touching the screen brings it back.
 */
@Composable
fun NowPlayingScreen(modifier: Modifier = Modifier) {
    val player by PlayerConnection.state.collectAsState()
    val library by LibraryRepository.library.collectAsState()
    val timeoutSec by Prefs.overlayTimeoutSec.state.collectAsState()
    val song = player.songId?.let { library?.songById?.get(it) }

    var overlayVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun wake() {
        overlayVisible = true
        lastTouch = System.currentTimeMillis()
    }

    val preset by VizControl.currentPreset.collectAsState()
    // Only fade to the visualizer while music plays and a preset is running.
    val vizOn by Prefs.vizEnabled.state.collectAsState()
    val canFade = vizOn && player.isPlaying && preset != null && vizMenuOpen.value == VizMenu.NONE
    LaunchedEffect(canFade) { if (!canFade) wake() }
    LaunchedEffect(lastTouch, overlayVisible, timeoutSec, canFade) {
        if (overlayVisible && timeoutSec > 0 && canFade) {
            delay(timeoutSec * 1000L)
            overlayVisible = false
        }
    }
    LaunchedEffect(player.songId) { wake() }
    LaunchedEffect(Unit) {
        VizControl.overlayRequests.collect { req ->
            if (req == VizControl.OverlayRequest.TOGGLE && overlayVisible) overlayVisible = false else wake()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .holdKeyFocus()
            // Any touch, even on a button, restarts the fade-out timer.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    lastTouch = System.currentTimeMillis()
                }
            }
    ) {
        // Visualizer, or (when switched off) the album art blurred across the screen.
        if (vizOn) Visualizer(Modifier.fillMaxSize())
        else if (song != null) AlbumArt(song.albumId, song.id, Modifier.fillMaxSize().blur(40.dp), shape = RoundedCornerShape(0.dp))
        GestureLayer(onTap = { if (overlayVisible) overlayVisible = false else wake() })
        PresetToast(overlayVisible)

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(900)),
        ) {
            Overlay(song, player.isPlaying, player.durationMs, player.shuffle, player.repeatMode,
                queuePos = if (player.queue.isEmpty()) null else player.queueIndex + 1 to player.queue.size)
        }
    }
}

@Composable
private fun Visualizer(modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val quality by Prefs.vizQuality.state.collectAsState()
    var view by remember { mutableStateOf<MilkSurfaceView?>(null) }
    AndroidView(factory = { MilkSurfaceView(it).also { v -> view = v } }, modifier = modifier)
    LaunchedEffect(quality) { view?.applyQuality() }
    DisposableEffect(lifecycle, view) {
        val v = view
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.onResume()
                Lifecycle.Event.ON_PAUSE -> v?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** Tap toggles the info; a horizontal swipe changes the visualizer preset. */
@Composable
private fun GestureLayer(onTap: () -> Unit) {
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged < -threshold) VizControl.nextPreset()
                        else if (dragged > threshold) VizControl.previousPreset()
                    },
                    onHorizontalDrag = { _, dx -> dragged += dx },
                )
            }
    )
}

/** Shows the preset name for a moment when it changes while the info is hidden. */
@Composable
private fun PresetToast(overlayVisible: Boolean) {
    val preset by VizControl.currentPreset.collectAsState()
    val show by Prefs.showPresetName.state.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(preset) {
        if (preset == null) return@LaunchedEffect
        visible = true
        delay(2500)
        visible = false
    }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
        AnimatedVisibility(show && visible && !overlayVisible, enter = fadeIn(), exit = fadeOut(tween(800))) {
            Text(
                preset?.name.orEmpty(),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Overlay(
    song: Song?,
    isPlaying: Boolean,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: Int,
    queuePos: Pair<Int, Int>?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidth = this.maxWidth
        val maxHeight = this.maxHeight
        val landscape = maxWidth > maxHeight * 1.15f
        // Adjustable dark layer (Settings > Song info background), plus gradients at the edges,
        // keep the white text readable over bright presets.
        val backdrop by Prefs.infoBackdrop.state.collectAsState()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = backdrop.coerceIn(0, 100) / 100f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f), 0.35f to Color.Transparent,
                    0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f),
                )
            )
        )
        val pad = if (maxHeight < 300.dp) 12.dp else 24.dp
        Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = pad * 0.75f)) {
            TopRow(queuePos)
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ArtCard(song, Modifier.fillMaxHeight(0.92f))
                    Spacer(Modifier.width(pad * 1.2f))
                    SongInfo(song, Modifier.weight(1f), large = maxHeight > 340.dp)
                }
            } else if (maxHeight > maxWidth * 1.3f) {
                // Tall screen (Thor turned upright): big cover on top, song info underneath.
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ArtCard(song, Modifier.size(minOf(maxWidth - pad * 2, maxHeight * 0.45f)))
                    Spacer(Modifier.height(pad))
                    SongInfo(song, Modifier.fillMaxWidth(), large = maxWidth > 400.dp)
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtCard(song, Modifier.size(minOf(maxWidth * 0.36f, maxHeight * 0.4f)))
                        Spacer(Modifier.width(16.dp))
                        SongInfo(song, Modifier.weight(1f), large = false)
                    }
                }
            }
            SeekBar(song, durationMs)
            // Narrow screens get two rows so every button fits.
            Controls(song, isPlaying, shuffle, repeatMode, compact = maxWidth < 420.dp, twoRows = maxWidth < 620.dp)
        }
    }
}

@Composable
private fun TopRow(queuePos: Pair<Int, Int>?) {
    val preset by VizControl.currentPreset.collectAsState()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (queuePos != null) {
            Text("${queuePos.first} of ${queuePos.second}", color = TextDim, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.weight(1f))
        // Tapping the preset name opens "add to visualizer folder".
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = preset != null) { menuPreset.value = preset; vizMenuOpen.value = VizMenu.ADD_PRESET }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = AccentViz, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (!Prefs.vizEnabled.state.collectAsState().value) "Visualizer off" else preset?.name ?: "projectM",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
            AddPresetMenu(menuPreset.value)
        }
    }
}

/** Which visualizer menu is open on the top screen; the song info stays up while one is. */
// BAR_PLAYLIST: the add-to-playlist button next to Like (its list opens at the button).
private enum class VizMenu { NONE, ADD_PRESET, NEW_FOLDER, CHOOSE_FOLDER, CHOOSE_TYPE, SONG, SONG_PLAYLIST, BAR_PLAYLIST, SONG_NEW_PLAYLIST }
private val vizMenuOpen = mutableStateOf(VizMenu.NONE)
/** The preset that was showing when its name was tapped (presets may change while the menu is open). */
private val menuPreset = mutableStateOf<com.dualamp.viz.PresetManager.Preset?>(null)

@Composable
private fun AddPresetMenu(preset: com.dualamp.viz.PresetManager.Preset?) {
    val folders by VizFolders.folders.collectAsState()
    val context = LocalContext.current
    DropdownMenu(expanded = vizMenuOpen.value == VizMenu.ADD_PRESET && preset != null, onDismissRequest = { vizMenuOpen.value = VizMenu.NONE }) {
        Text("Add to visualizer folder", color = TextDim, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        folders.forEach { f ->
            val inside = preset != null && preset.path in f.presetPaths
            DropdownMenuItem(
                text = { Text(f.name) },
                leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                trailingIcon = if (inside) ({ Icon(Icons.Rounded.Check, null, tint = Accent) }) else null,
                onClick = {
                    preset?.let { if (inside) VizFolders.remove(f.id, it.path) else VizFolders.add(f.id, it.path) }
                    Toast.makeText(context, if (inside) "Removed from ${f.name}" else "Added to ${f.name}", Toast.LENGTH_SHORT).show()
                    vizMenuOpen.value = VizMenu.NONE
                },
            )
        }
        DropdownMenuItem(
            text = { Text("New folder…") },
            leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
            onClick = { vizMenuOpen.value = VizMenu.NEW_FOLDER },
        )
    }
    if (vizMenuOpen.value == VizMenu.NEW_FOLDER && preset != null) {
        var name by remember { mutableStateOf("My visualizers #${folders.size + 1}") }
        AlertDialog(
            onDismissRequest = { vizMenuOpen.value = VizMenu.NONE },
            title = { Text("New visualizer folder") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val f = VizFolders.create(name.trim(), listOf(preset.path))
                    Toast.makeText(context, "Created ${f.name}", Toast.LENGTH_SHORT).show()
                    vizMenuOpen.value = VizMenu.NONE
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { vizMenuOpen.value = VizMenu.NONE }) { Text("Cancel") } },
            containerColor = Surface2,
        )
    }
}

/** Picks which visualizer folder the top screen cycles through. */
@Composable
private fun ChooseFolderButton() {
    val folders by VizFolders.folders.collectAsState()
    val active by Prefs.activeVizFolder.state.collectAsState()
    Box {
        IconButton(onClick = { vizMenuOpen.value = VizMenu.CHOOSE_FOLDER }) {
            Icon(Icons.Rounded.Folder, "Visualizer folder", tint = if (active.isNotEmpty()) AccentViz else Color.White.copy(alpha = 0.8f))
        }
        DropdownMenu(expanded = vizMenuOpen.value == VizMenu.CHOOSE_FOLDER, onDismissRequest = { vizMenuOpen.value = VizMenu.NONE }) {
            Text("Cycle through", color = TextDim, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem(
                text = { Text("All presets") },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                trailingIcon = if (active.isEmpty()) ({ Icon(Icons.Rounded.Check, null, tint = Accent) }) else null,
                onClick = { VizFolders.setActive(null); vizMenuOpen.value = VizMenu.NONE },
            )
            // The bundled pack's own categories (Dancer, Drawing, ...) live one level down.
            val activeType = active.removePrefix(VizFolders.TYPE_PREFIX).takeIf { active.startsWith(VizFolders.TYPE_PREFIX) }
            DropdownMenuItem(
                text = { Text(if (activeType != null) "Visualizer types: $activeType" else "Visualizer types") },
                leadingIcon = { Icon(Icons.Rounded.Category, null) },
                trailingIcon = { Icon(Icons.Rounded.ChevronRight, null) },
                onClick = { vizMenuOpen.value = VizMenu.CHOOSE_TYPE },
            )
            folders.forEach { f ->
                DropdownMenuItem(
                    text = { Text("${f.name} (${f.presetPaths.size})") },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                    trailingIcon = if (active == f.id) ({ Icon(Icons.Rounded.Check, null, tint = Accent) }) else null,
                    enabled = f.presetPaths.isNotEmpty(),
                    onClick = {
                        VizFolders.setActive(f.id)
                        VizControl.nextPreset() // switch to one of its presets right away
                        vizMenuOpen.value = VizMenu.NONE
                    },
                )
            }
            if (folders.isEmpty()) {
                Text("Tap the preset name (top right) to start a folder", color = TextDim, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        // Second level: the visualizer types.
        val presets by com.dualamp.viz.PresetManager.presets.collectAsState()
        val types = remember(presets) { presets.groupingBy { it.category }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER) }
        DropdownMenu(
            expanded = vizMenuOpen.value == VizMenu.CHOOSE_TYPE,
            onDismissRequest = { vizMenuOpen.value = VizMenu.NONE },
            modifier = Modifier.heightIn(max = 520.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Back", color = TextDim) },
                leadingIcon = { Icon(Icons.Rounded.NavigateBefore, null) },
                onClick = { vizMenuOpen.value = VizMenu.CHOOSE_FOLDER },
            )
            types.forEach { (type, count) ->
                DropdownMenuItem(
                    text = { Text("$type ($count)") },
                    leadingIcon = { Icon(Icons.Rounded.Category, null) },
                    trailingIcon = if (active == VizFolders.TYPE_PREFIX + type) ({ Icon(Icons.Rounded.Check, null, tint = Accent) }) else null,
                    onClick = {
                        VizFolders.setActiveType(type)
                        VizControl.nextPreset()
                        vizMenuOpen.value = VizMenu.NONE
                    },
                )
            }
        }
    }
}

/** Album cover. Long-press opens the song menu (queue, playlist, liked, go to album / artist). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtCard(song: Song?, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.aspectRatio(1f)) {
        Surface(
            Modifier.fillMaxSize().shadow(18.dp, shape).clip(shape)
                .combinedClickable(onClick = {}, onLongClick = { if (song != null) vizMenuOpen.value = VizMenu.SONG }),
            shape = shape, color = Surface2,
        ) {
            if (song != null) AlbumArt(song.albumId, song.id, shape = shape)
            else Placeholder("none", Modifier.fillMaxSize())
        }
        if (song != null) SongMenu(song)
    }
}

@Composable
private fun SongMenu(song: Song) {
    val context = LocalContext.current
    val playlists by PlaylistStore.playlists.collectAsState()
    val liked = playlists.firstOrNull { it.id == PlaylistStore.LIKED_ID }?.songIds?.contains(song.id) == true
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun close() { vizMenuOpen.value = VizMenu.NONE }
    DropdownMenu(expanded = vizMenuOpen.value == VizMenu.SONG, onDismissRequest = ::close) {
        Text(song.title, color = TextDim, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 320.dp))
        DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
            onClick = { PlayerConnection.playNext(listOf(song)); toast("Playing next"); close() })
        DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
            onClick = { PlayerConnection.addToQueue(listOf(song)); toast("Added to queue"); close() })
        DropdownMenuItem(text = { Text("Add to playlist…") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
            onClick = { vizMenuOpen.value = VizMenu.SONG_PLAYLIST })
        DropdownMenuItem(text = { Text(if (liked) "Remove from Liked Songs" else "Add to Liked Songs") },
            leadingIcon = { Icon(if (liked) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite, null) },
            onClick = { PlaylistStore.toggleLike(song.id); close() })
        DropdownMenuItem(text = { Text("Go to album") }, leadingIcon = { Icon(Icons.Rounded.Album, null) },
            onClick = { LibraryNav.open(Page.AlbumPage(song.albumId)); close() })
        DropdownMenuItem(text = { Text("Go to artist") }, leadingIcon = { Icon(Icons.Rounded.Person, null) },
            onClick = { LibraryNav.open(Page.ArtistPage(song.primaryArtist)); close() })
    }
    PlaylistMenu(song, VizMenu.SONG_PLAYLIST)
    if (vizMenuOpen.value == VizMenu.SONG_NEW_PLAYLIST) {
        var name by remember { mutableStateOf("My playlist #${playlists.size}") }
        AlertDialog(
            onDismissRequest = ::close,
            title = { Text("New playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val p = PlaylistStore.create(name.trim(), listOf(song.id)); toast("Created ${p.name}"); close()
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
            containerColor = Surface2,
        )
    }
}

/** "Add to playlist" list for [song], open while the menu state is [which]. */
@Composable
private fun PlaylistMenu(song: Song, which: VizMenu) {
    val context = LocalContext.current
    val playlists by PlaylistStore.playlists.collectAsState()
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun close() { vizMenuOpen.value = VizMenu.NONE }
    DropdownMenu(expanded = vizMenuOpen.value == which, onDismissRequest = ::close) {
        Text("Add to playlist", color = TextDim, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        DropdownMenuItem(text = { Text("New playlist…") }, leadingIcon = { Icon(Icons.Rounded.Add, null) },
            onClick = { vizMenuOpen.value = VizMenu.SONG_NEW_PLAYLIST })
        playlists.forEach { p ->
            DropdownMenuItem(text = { Text(p.name) },
                leadingIcon = { Icon(if (p.id == PlaylistStore.LIKED_ID) Icons.Rounded.Favorite else Icons.AutoMirrored.Rounded.QueueMusic, null) },
                onClick = { PlaylistStore.add(p.id, listOf(song.id)); toast("Added to ${p.name}"); close() })
        }
    }
}

@Composable
private fun SongInfo(song: Song?, modifier: Modifier, large: Boolean) {
    Column(modifier) {
        Text(
            song?.title ?: "Nothing playing",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) 34.sp else 22.sp,
            lineHeight = if (large) 38.sp else 26.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            song?.artist ?: "Pick something from your library",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = if (large) 22.sp else 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (song != null) {
            Spacer(Modifier.height(4.dp))
            val albumLine = listOfNotNull(song.album, song.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
            Text(albumLine, color = TextDim, fontSize = if (large) 18.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (song.genre.isNotBlank()) Chip(song.genre)
                Chip(formatLabel(song))
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.9f),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private val LOSSLESS = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "AIF", "WV")

private fun formatLabel(song: Song): String {
    val rate = AudioTap.sampleRate
    val khz = if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(rate / 1000f)
    val detail = if (song.codec in LOSSLESS) "${AudioTap.bitDepth}-bit"
    else if (song.bitrate > 0) "${song.bitrate / 1000} kbps" else null
    return listOfNotNull(song.codec, "$khz kHz", detail).joinToString(" · ")
}

@Composable
private fun SeekBar(song: Song?, durationMs: Long) {
    var position by remember { mutableLongStateOf(PlayerConnection.positionMs) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (dragging == null) position = PlayerConnection.positionMs
            delay(250)
        }
    }
    val fraction = dragging ?: if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(dragging?.let { (it * durationMs).toLong() } ?: position), color = TextDim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        WaveformBar(
            song = song,
            fraction = fraction,
            onDrag = { dragging = it },
            onRelease = {
                dragging?.let { PlayerConnection.seekTo((it * durationMs).toLong()) }
                dragging = null
            },
            modifier = Modifier.weight(1f).height(44.dp),
        )
        Text(formatTime(durationMs), color = TextDim, fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 8.dp))
    }
}

@Composable
private fun Controls(song: Song?, isPlaying: Boolean, shuffle: Boolean, repeatMode: Int, compact: Boolean, twoRows: Boolean = false) {
    val playlists by PlaylistStore.playlists.collectAsState()
    val liked = song != null && playlists.firstOrNull { it.id == PlaylistStore.LIKED_ID }?.songIds?.contains(song.id) == true
    val locked = Prefs.presetMode.state.collectAsState().value == VizControl.Mode.LOCKED.name
    val iconSize: Dp = if (compact) 26.dp else 32.dp

    val likeButton: @Composable () -> Unit = {
        IconButton(onClick = { song?.let { PlaylistStore.toggleLike(it.id) } }) {
            Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like",
                tint = if (liked) Accent else Color.White, modifier = Modifier.size(iconSize * 0.85f))
        }
        // Add to playlist, with its list opening at the button.
        Box {
            IconButton(onClick = { if (song != null) vizMenuOpen.value = VizMenu.BAR_PLAYLIST }) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = Color.White, modifier = Modifier.size(iconSize * 0.9f))
            }
            if (song != null) PlaylistMenu(song, VizMenu.BAR_PLAYLIST)
        }
    }
    val transport: @Composable () -> Unit = {
        IconButton(onClick = PlayerConnection::toggleShuffle) {
            Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) Accent else Color.White, modifier = Modifier.size(iconSize * 0.85f))
        }
        IconButton(onClick = PlayerConnection::previous) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(iconSize * 1.2f))
        }
        FilledIconButton(
            onClick = PlayerConnection::togglePlay,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
            modifier = Modifier.padding(horizontal = if (compact) 2.dp else 10.dp).size(iconSize * 1.9f),
        ) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", modifier = Modifier.size(iconSize * 1.2f))
        }
        IconButton(onClick = PlayerConnection::next) {
            Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(iconSize * 1.2f))
        }
        IconButton(onClick = PlayerConnection::cycleRepeat) {
            Icon(
                if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White else Accent,
                modifier = Modifier.size(iconSize * 0.85f),
            )
        }
    }
    val vizButtons: @Composable (Boolean) -> Unit = { showPrevious ->
        if (showPrevious) {
            IconButton(onClick = VizControl::previousPreset) {
                Icon(Icons.Rounded.NavigateBefore, "Previous preset", tint = Color.White.copy(alpha = 0.8f))
            }
        }
        val vizOn = Prefs.vizEnabled.state.collectAsState().value
        IconButton(onClick = { Prefs.vizEnabled.value = !vizOn }) {
            Icon(if (vizOn) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Visualizer on/off",
                tint = if (vizOn) Color.White.copy(alpha = 0.8f) else AccentViz)
        }
        ChooseFolderButton()
        IconButton(onClick = { VizControl.setLocked(!locked) }) {
            Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "Lock preset",
                tint = if (locked) AccentViz else Color.White.copy(alpha = 0.8f))
        }
        IconButton(onClick = VizControl::nextPreset) {
            Icon(Icons.Rounded.NavigateNext, "Next preset", tint = Color.White.copy(alpha = 0.8f))
        }
    }

    if (twoRows) {
        // Portrait: playback controls centred on their own row, everything else below.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) { transport() }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                likeButton()
                Spacer(Modifier.weight(1f))
                vizButtons(true)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { likeButton() }
        transport()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            vizButtons(!compact)
        }
    }
}

/**
 * Seek bar drawn as the song's waveform: played part bright, the rest dim. Tap or drag to seek.
 * Shows a flat line until the waveform has been read (a second or two for a new song).
 */
@Composable
private fun WaveformBar(
    song: Song?,
    fraction: Float,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val bars by produceState(song?.let { Waveform.cached(it.id) }, song?.id) {
        value = song?.let { Waveform.cached(it.id) ?: Waveform.load(context, it) }
    }
    val latestDrag by rememberUpdatedState(onDrag)
    val latestRelease by rememberUpdatedState(onRelease)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        latestDrag((offset.x / size.width).coerceIn(0f, 1f))
                        tryAwaitRelease()
                        latestRelease()
                    },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { latestRelease() },
                    onDragCancel = { latestRelease() },
                ) { change, _ -> latestDrag((change.position.x / size.width).coerceIn(0f, 1f)) }
            }
    ) {
        val data = bars
        // About one bar per 5 dp, so the outline reads clearly at any width.
        val count = (size.width / 5.dp.toPx()).toInt().coerceIn(20, Waveform.BARS)
        fun level(i: Int): Float {
            val d = data ?: return 0.06f
            val from = i * d.size / count
            val to = maxOf(from + 1, (i + 1) * d.size / count)
            var sum = 0f
            for (j in from until to) sum += d[j]
            return sum / (to - from)
        }
        val slot = size.width / count
        val barWidth = (slot * 0.6f).coerceAtLeast(1f)
        val mid = size.height / 2f
        val playedX = fraction * size.width
        for (i in 0 until count) {
            val x = i * slot + slot / 2f
            val h = level(i) * size.height
            val color = if (x <= playedX) Color.White else Color.White.copy(alpha = 0.3f)
            drawLine(color, Offset(x, mid - h / 2f), Offset(x, mid + h / 2f), strokeWidth = barWidth, cap = StrokeCap.Round)
        }
        // Playhead.
        drawLine(Accent, Offset(playedX, 0f), Offset(playedX, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}
