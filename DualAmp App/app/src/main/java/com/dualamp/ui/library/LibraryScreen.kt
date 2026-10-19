package com.dualamp.ui.library

import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.runtime.SideEffect
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ArrowDropDown
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.dualamp.viz.VizFolders
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.rounded.ClearAll
import com.dualamp.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualamp.AppEvents
import com.dualamp.input.ControllerBus
import com.dualamp.library.LibraryRepository
import com.dualamp.library.PlaylistStore
import com.dualamp.library.Song
import com.dualamp.playback.PlayerConnection
import com.dualamp.settings.Prefs
import com.dualamp.ui.Accent
import com.dualamp.ui.IconTint
import com.dualamp.ui.TextMain
import com.dualamp.ui.AlbumArt
import com.dualamp.ui.Placeholder
import com.dualamp.ui.holdKeyFocus
import com.dualamp.ui.Surface1
import com.dualamp.ui.Surface2
import com.dualamp.ui.Surface3
import com.dualamp.ui.TextDim
import com.dualamp.viz.PresetManager
import com.dualamp.viz.VizControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One level of the browser's back stack, with its own scroll position and gamepad cursor. */
private class StackEntry(val page: Page) {
    val list = LazyListState()
    val grid = LazyGridState()
    val flow = LazyListState()
    var cursor by mutableIntStateOf(0)
}

private class PendingVizFolder(val preset: PresetManager.Preset?)

/** An open "rename" dialog. */
private class RenamePrompt(val title: String, val current: String, val confirm: String = "Rename", val onRename: (String) -> Unit)

private class MenuOption(val label: String, val icon: ImageVector, val run: () -> Unit)
private class MenuState(val title: String, val options: List<MenuOption>) {
    var cursor by mutableIntStateOf(0)
}

/**
 * Bottom screen: Spotify-style library with category chips, sort and view switches, and
 * iPod-style drill-down (Artist → Album → Song). Works by touch or entirely by gamepad.
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier, showMiniPlayer: Boolean = true) {
    // Without its own player bar the library shares the screen with the player (single-screen mode).
    singleScreenLayout = !showMiniPlayer
    com.dualamp.ui.LibraryTheme { LibraryScreenBody(modifier, showMiniPlayer) }
}

@Composable
private fun LibraryScreenBody(modifier: Modifier, showMiniPlayer: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localLib by LibraryRepository.library.collectAsState()
    val pcLib by LibraryRepository.pcLibrary.collectAsState()
    val hasPermission by LibraryRepository.hasPermission.collectAsState()
    val playlists by PlaylistStore.playlists.collectAsState()
    val player by PlayerConnection.state.collectAsState()
    val presets by PresetManager.presets.collectAsState()
    val currentPreset by VizControl.currentPreset.collectAsState()
    val importStatus by PresetManager.importStatus.collectAsState()
    val viewModes by Prefs.viewModes.state.collectAsState()
    val sortModes by Prefs.sortModes.state.collectAsState()
    var settingsTick by remember { mutableIntStateOf(0) }

    // Each tab has its own back stack, so switching keeps your place.
    var tab by remember { mutableStateOf(BottomTab.LIBRARY) }
    // The header tab to go back to when Settings or Visualizers is closed.
    var lastHeaderTab by remember { mutableStateOf(BottomTab.LIBRARY) }
    LaunchedEffect(tab) { if (tab.inHeader) lastHeaderTab = tab }
    /** The gear / sparkle buttons: open that tab, or close it again when it is open. */
    fun toggleTab(t: BottomTab) { tab = if (tab == t) lastHeaderTab else t }
    val stacks = remember {
        val cat = runCatching { Category.valueOf(Prefs.libraryCategory.value) }.getOrDefault(Category.ALBUMS)
        mapOf(
            BottomTab.PC to mutableStateListOf(StackEntry(Page.Root(Category.ALBUMS))),
            BottomTab.LIBRARY to mutableStateListOf(StackEntry(Page.Root(cat))),
            BottomTab.EQUALIZER to mutableStateListOf(StackEntry(Page.Dsp)),
            BottomTab.VISUALIZERS to mutableStateListOf(StackEntry(Page.Presets)),
            BottomTab.SETTINGS to mutableStateListOf(StackEntry(Page.Settings)),
        )
    }
    val stack = stacks.getValue(tab)
    // The PC tab browses the PC's music with the same pages; everything else uses the Thor's library.
    val lib = if (tab == BottomTab.PC) pcLib else localLib
    val top = stack.last()
    var usingPad by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuState?>(null) }
    var newPlaylistSongs by remember { mutableStateOf<List<Song>?>(null) }
    // Multi-select: entries picked across pages, in the order they were picked.
    val selection = remember { mutableStateListOf<Entry>() }
    SideEffect { selectedKeys.value = selection.map { it.key }.toSet() }
    fun selectable(e: Entry) = e.song != null || (e.kind == Kind.CONTAINER && e.songs().isNotEmpty())
    fun toggleSelected(e: Entry) {
        val i = selection.indexOfFirst { it.key == e.key }
        if (i >= 0) selection.removeAt(i) else selection.add(e)
    }
    fun clearSelection() = selection.clear()
    fun selectedSongs(): List<Song> = selection.flatMap { it.songs() }.distinctBy { it.id }
    // Non-null while the "new visualizer folder" dialog is open; holds the preset to put in it.
    var newVizFolder by remember { mutableStateOf<PendingVizFolder?>(null) }
    var renamePrompt by remember { mutableStateOf<RenamePrompt?>(null) }
    // Search keyboard (gamepad + touch). One state for the whole search, kept while typing.
    var searchKb by remember { mutableStateOf<KeyboardState?>(null) }
    var keyboardOpen by remember { mutableStateOf(false) }
    // The header item the gamepad cursor is on (null: the cursor is on the page).
    var headerFocus by remember { mutableStateOf<HeaderSpot?>(null) }
    // The gamepad cursor is on the category chips (Playlists, Artists, Albums...) of a Library / PC page.
    var chipsFocus by remember { mutableStateOf(false) }
    val vizFolders by VizFolders.folders.collectAsState()
    val activeVizFolder by Prefs.activeVizFolder.state.collectAsState()
    val presetMode by Prefs.presetMode.state.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    // Controls guide: shown once per app start unless turned off.
    var helpOpen by remember { mutableStateOf(Prefs.showControlsAtLaunch.value && !ControlsHelp.shownThisLaunch) }
    var helpDontShow by remember { mutableStateOf(false) }
    val helpScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    var aboutOpen by remember { mutableStateOf(false) }
    // The PC (DualAmp Server) could not be reached: a pop-up with what to check.
    val pcProblem by com.dualamp.library.RemoteLibrary.problem.collectAsState()
    // Connected to the PC this launch (Settings > PC music).
    val pcActive by com.dualamp.library.RemoteLibrary.active.collectAsState()
    fun retryPc() { com.dualamp.library.RemoteLibrary.dismissProblem(quietMs = 0); com.dualamp.library.RemoteLibrary.checkNow(); LibraryRepository.refresh(); Toast.makeText(context, "Trying your PC again…", Toast.LENGTH_SHORT).show() }
    fun closeHelp() {
        helpOpen = false
        ControlsHelp.shownThisLaunch = true
        // Settings > Help can also turn the launch guide back on.
        Prefs.showControlsAtLaunch.value = !helpDontShow
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let(LibraryRepository::pathFromTree) ?: return@rememberLauncherForActivityResult
        LibraryRepository.addFolder(path)
        Toast.makeText(context, "Added $path", Toast.LENGTH_SHORT).show()
    }
    val musicFolders by Prefs.musicFolders.state.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(PresetManager::importFolder)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LibraryRepository.refresh()
    }
    LaunchedEffect(importStatus) {
        importStatus?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    fun confirmClearQueue() = MenuState("Clear the queue? The current song keeps playing.", listOf(
        MenuOption("Yes, clear the queue", Icons.Rounded.ClearAll) {
            PlayerConnection.clearQueue()
            Toast.makeText(context, "Queue cleared", Toast.LENGTH_SHORT).show()
        },
        MenuOption("Cancel", Icons.Rounded.Close) {},
    ))

    fun connectServer(s: com.dualamp.library.RemoteLibrary.Server) {
        Prefs.serverUrl.value = s.url
        com.dualamp.library.RemoteLibrary.active.value = true
        LibraryRepository.refresh()
        settingsTick++
        Toast.makeText(context, "Connected to ${s.name}. Loading its library…", Toast.LENGTH_LONG).show()
    }

    val actions = remember {
        UiActions(
            importPresets = { run { AppEvents.markOwnLaunch(); importLauncher.launch(null) } },
            relaunch = { AppEvents.relaunch(context) },
            rescan = {
                LibraryRepository.refresh()
                Toast.makeText(context, "Rescanning library…", Toast.LENGTH_SHORT).show()
            },
            deletePresets = { scope.launch { PresetManager.deleteImported() } },
            newPlaylist = { newPlaylistSongs = emptyList() },
            newVizFolder = { newVizFolder = PendingVizFolder(it) },
            sleepKeysEnabled = { com.dualamp.input.SleepKeysService.isEnabled(context) },
            clearQueue = { menu = confirmClearQueue() },
            addMusicFolder = { run { AppEvents.markOwnLaunch(); folderLauncher.launch(null) } },
            findServer = {
                Toast.makeText(context, "Looking for DualAmp Server…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val found = com.dualamp.library.RemoteLibrary.discover(context)
                    when {
                        found.isEmpty() -> Toast.makeText(context, "No PC found. Is DualAmp Server running? You can also enter its address.", Toast.LENGTH_LONG).show()
                        found.size == 1 -> connectServer(found.first())
                        else -> menu = MenuState("Choose a PC", found.map { s -> MenuOption("${s.name} (${s.url})", Icons.Rounded.Computer) { connectServer(s) } })
                    }
                }
            },
            enterServer = { renamePrompt = RenamePrompt("PC address", Prefs.serverUrl.value.removePrefix("http://").ifBlank { "192.168." }, "Connect") { addr ->
                scope.launch {
                    val s = com.dualamp.library.RemoteLibrary.probe(addr)
                    if (s == null) Toast.makeText(context, "No DualAmp Server at $addr", Toast.LENGTH_LONG).show() else connectServer(s)
                }
            } },
            forgetServer = { com.dualamp.library.RemoteLibrary.active.value = false; Prefs.serverUrl.value = ""; LibraryRepository.refresh(); settingsTick++ },
            connectLast = {
                val url = Prefs.serverUrl.value
                scope.launch {
                    val s = com.dualamp.library.RemoteLibrary.probe(url)
                    if (s == null) com.dualamp.library.RemoteLibrary.reportProblem("DualAmp couldn't reach your PC (${url.removePrefix("http://")}).", always = true)
                    else connectServer(s)
                }
            },
            disconnectServer = { com.dualamp.library.RemoteLibrary.active.value = false; LibraryRepository.refresh(); settingsTick++ },
            showAbout = { aboutOpen = true },
            showHelp = { helpDontShow = !Prefs.showControlsAtLaunch.value; helpOpen = true },
            openAccessibility = {
                AppEvents.markOwnLaunch()
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(context, "Turn on DualAmp under Installed apps", Toast.LENGTH_LONG).show()
            },
        )
    }

    // First-launch prompts, one after the other. Each can be skipped and done later in Settings.
    val onboarding = remember {
        mutableStateListOf<Onboarding>().apply {
            if (!Prefs.onboardingDone.value) {
                if (LibraryRepository.musicFolders.isEmpty()) add(Onboarding.FOLDER)
                if (!com.dualamp.input.SleepKeysService.isEnabled(context)) add(Onboarding.ACCESSIBILITY)
            }
        }
    }
    fun nextOnboarding() {
        if (onboarding.isNotEmpty()) onboarding.removeAt(0)
        if (onboarding.isEmpty()) Prefs.onboardingDone.value = true
    }
    fun onboardingPrimary() {
        when (onboarding.firstOrNull()) {
            Onboarding.FOLDER -> run { AppEvents.markOwnLaunch(); folderLauncher.launch(null) }
            Onboarding.ACCESSIBILITY -> actions.openAccessibility()
            null -> Unit
        }
        nextOnboarding()
    }

    // Opening search starts a fresh keyboard; leaving search drops it.
    val onSearch = top.page is Page.Search
    LaunchedEffect(onSearch) {
        if (onSearch && searchKb == null) { searchKb = KeyboardState((top.page as Page.Search).query); keyboardOpen = true }
        if (!onSearch) { searchKb = null; keyboardOpen = false }
    }
    // Typed text drives the search results.
    LaunchedEffect(searchKb?.text) {
        val text = searchKb?.text ?: return@LaunchedEffect
        val t = stack.lastOrNull()?.page
        if (t is Page.Search && t.query != text) stack[stack.lastIndex] = StackEntry(Page.Search(text))
    }
    SideEffect { ControllerBus.textInput = (keyboardOpen && onSearch) || openPrompt.value != null }

    // Sort and view choices are remembered per kind of page.
    val typeKey = top.page.typeKey
    val sortSpec = Prefs.mapGet(Prefs.sortModes, typeKey)
    val chosenSort = sortSpec?.substringBefore(':')?.let { runCatching { Sort.valueOf(it) }.getOrNull() }
    val desc = sortSpec?.endsWith(":desc") == true
    val queueKey = if (top.page == Page.Root(Category.QUEUE)) player else null
    val vizPage = top.page == Page.Presets || top.page is Page.PresetCategory || top.page is Page.VizFolderPage
    val presetKey = if (vizPage) listOf(presets, currentPreset, vizFolders, activeVizFolder) else null
    // The DSP page shows the preset name, which depends on every band.
    val eqValues = Prefs.eqBands.map { it.state.collectAsState().value }
    val settingsKey = when (top.page) {
        Page.Settings -> listOf(presetMode, viewModes, Prefs.sameViewEverywhere.state.collectAsState().value,
            com.dualamp.ui.AppTheme.presetName, com.dualamp.ui.AppTheme.glowName, Prefs.coverGlowShape.state.collectAsState().value,
            Prefs.coverGlowAnim.state.collectAsState().value, Prefs.launchViewRoot.state.collectAsState().value, Prefs.launchViewPage.state.collectAsState().value)
        Page.Dsp -> eqValues
        else -> null
    }

    // The PC music rows say whether the PC is connected.
    val pcLink by com.dualamp.library.RemoteLibrary.connection.collectAsState()
    val data = remember(lib, tab, playlists, top.page, sortModes, settingsTick, queueKey, presetKey, settingsKey, musicFolders, pcLink) {
        lib?.let { buildPage(top.page, it, playlists, player, chosenSort, desc, actions) }
    }
    val fixedList = top.page == Page.Settings || top.page == Page.PcMusic || top.page == Page.MusicFolders || top.page == Page.Dsp || top.page == Page.Controls || vizPage || top.page == Page.Root(Category.QUEUE) || top.page is Page.Search
    // One view for the library categories and one for the pages inside them (artist, album, playlist...),
    // or the same for both (Settings > Library view). Grid unless changed.
    val rootPage = top.page is Page.Root
    val view = if (fixedList) ViewMode.LIST
    else runCatching { ViewMode.valueOf(com.dualamp.settings.LibraryView.get(rootPage)) }.getOrDefault(ViewMode.GRID)
    @Suppress("UNUSED_EXPRESSION") viewModes
    @Suppress("UNUSED_EXPRESSION") Prefs.sameViewEverywhere.state.collectAsState().value

    /** Opens [page] in the Library tab (switching to it), e.g. Go to album / artist. */
    fun goTo(page: Page) {
        // Albums / artists that only exist on the PC open in the PC tab.
        val onPc = pcLib != null && when (page) {
            is Page.AlbumPage -> page.albumId < 0
            is Page.ArtistPage -> localLib?.artist(page.name) == null && pcLib?.artist(page.name) != null
            else -> false
        }
        tab = if (onPc) BottomTab.PC else BottomTab.LIBRARY
        stacks.getValue(tab).add(StackEntry(page))
    }

    fun push(page: Page) {
        stack.add(StackEntry(page))
    }

    /** Search, in the library tab you are in (from Equalizer, Settings or Visualizers: Library, or PC if you came from it). */
    /** Settings > PC music (from the PC tab when no PC is connected). */
    fun openPcSettings() {
        tab = BottomTab.SETTINGS
        val s = stacks.getValue(BottomTab.SETTINGS)
        s.clear()
        s.add(StackEntry(Page.Settings))
        s.add(StackEntry(Page.PcMusic))
    }

    fun openSearch() {
        if (tab != BottomTab.PC && tab != BottomTab.LIBRARY) tab = if (lastHeaderTab == BottomTab.PC) BottomTab.PC else BottomTab.LIBRARY
        val s = stacks.getValue(tab)
        if (s.last().page is Page.Search) keyboardOpen = true else s.add(StackEntry(Page.Search("")))
    }

    fun back(): Boolean {
        if (menu != null) { menu = null; return true }
        if (selection.isNotEmpty()) { clearSelection(); return true }
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }
        if (!tab.inHeader) { tab = lastHeaderTab; return true }
        return false
    }

    fun selectCategory(c: Category) {
        // Category chips switch within the PC tab when it is open, otherwise within Library.
        val target = if (tab == BottomTab.PC) BottomTab.PC else BottomTab.LIBRARY
        tab = target
        val list = stacks.getValue(target)
        list.clear()
        list.add(StackEntry(Page.Root(c)))
        if (target == BottomTab.LIBRARY) Prefs.libraryCategory.value = c.name
    }

    fun activate(e: Entry) {
        if (e.kind == Kind.HEADER) return
        // While selecting, A / tap ticks items instead of opening them.
        if (selection.isNotEmpty() && selectable(e)) { toggleSelected(e); return }
        // Drop-down rows: a list in place for touch, the regular menu for the gamepad.
        if (e.choices != null) {
            if (usingPad) menu = MenuState(e.title, e.choices.map { c ->
                MenuOption(c, if (c == e.trailing) Icons.Rounded.Check else Icons.Rounded.Equalizer) { e.onChoose?.invoke(c) }
            }) else openDropdown.value = e.key
            return
        }
        val d = data ?: return
        when {
            e.action != null -> {
                e.action.invoke()
                if (top.page == Page.Settings || top.page == Page.Dsp) settingsTick++
            }
            e.target != null -> push(e.target)
            e.song != null -> {
                val idx = d.playable.indexOfFirst { it.id == e.song.id }
                val collection = top.page is Page.AlbumPage || top.page is Page.PlaylistPage
                if (idx >= 0) PlayerConnection.playSongs(d.playable, idx, fromCollection = collection) else PlayerConnection.playSongs(listOf(e.song))
            }
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun playlistChooser(songs: List<Song>): MenuState = MenuState(
        "Add to playlist",
        listOf(MenuOption("New playlist…", Icons.Rounded.Add) { newPlaylistSongs = songs }) +
            playlists.map { p ->
                MenuOption(p.name, if (p.id == PlaylistStore.LIKED_ID) Icons.Rounded.Favorite else Icons.AutoMirrored.Rounded.QueueMusic) {
                    PlaylistStore.add(p.id, songs.map { it.id })
                    toast("Added to ${p.name}")
                }
            },
    )

    fun vizFolderChooser(preset: PresetManager.Preset): MenuState = MenuState(
        "Add to visualizer folder",
        listOf(MenuOption("New folder…", Icons.Rounded.Add) { newVizFolder = PendingVizFolder(preset) }) +
            vizFolders.map { f ->
                MenuOption(f.name, Icons.Rounded.Folder) { VizFolders.add(f.id, preset.path); toast("Added to ${f.name}") }
            },
    )

    /** Long-press on the player bar's song (and similar): where to go from the song playing now. */
    fun songMenu(s: Song): MenuState {
        val liked = PlaylistStore.isLiked(s.id)
        return MenuState(s.title, listOf(
            MenuOption("Go to album", Icons.Rounded.Album) { goTo(Page.AlbumPage(s.albumId)) },
            MenuOption("Go to artist", Icons.Rounded.Person) { goTo(Page.ArtistPage(s.primaryArtist)) },
            MenuOption("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { menu = playlistChooser(listOf(s)) },
            MenuOption(if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                if (liked) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite) { PlaylistStore.toggleLike(s.id) },
        ))
    }

    // The top screen can ask for a page (its art menu's Go to album / artist).
    LaunchedEffect(Unit) { LibraryNav.requests.collect { goTo(it) } }

    fun menuFor(e: Entry): MenuState? {
        if (e.kind == Kind.HEADER) return null
        e.musicFolder?.let { path ->
            return MenuState(path, listOf(
                MenuOption("Remove from library", Icons.Rounded.RemoveCircleOutline) { LibraryRepository.removeFolder(path); toast("Removed $path") },
            ))
        }
        e.preset?.let { p ->
            val opts = arrayListOf(
                MenuOption("Show now", Icons.Rounded.AutoAwesome) { VizControl.loadPreset(p) },
                MenuOption("Add to visualizer folder…", Icons.Rounded.CreateNewFolder) { menu = vizFolderChooser(p) },
            )
            val page = top.page
            if (page is Page.VizFolderPage) {
                opts += MenuOption("Remove from this folder", Icons.Rounded.RemoveCircleOutline) { VizFolders.remove(page.id, p.path) }
            }
            return MenuState(p.name, opts)
        }
        e.vizFolder?.let { f ->
            val isActive = VizFolders.active?.id == f.id
            return MenuState(f.name, listOf(
                MenuOption(if (isActive) "Stop using this folder" else "Use only this folder", Icons.Rounded.Shuffle) {
                    VizFolders.setActive(if (isActive) null else f.id)
                },
                MenuOption("Rename", Icons.Rounded.Edit) { renamePrompt = RenamePrompt("Rename visualizer folder", f.name) { VizFolders.rename(f.id, it) } },
                MenuOption("Delete folder", Icons.Rounded.Delete) { VizFolders.delete(f.id); toast("Deleted ${f.name}") },
            ))
        }
        e.queueIndex?.let { i ->
            val s = e.song ?: return null
            return MenuState(s.title, listOf(
                MenuOption("Play now", Icons.Rounded.PlayArrow) { PlayerConnection.playQueueIndex(i) },
                MenuOption("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { PlayerConnection.moveToNext(i); toast("Playing next") },
                MenuOption("Remove from queue", Icons.Rounded.RemoveCircleOutline) { PlayerConnection.removeFromQueue(i) },
                MenuOption("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { menu = playlistChooser(listOf(s)) },
                MenuOption("Go to album", Icons.Rounded.Album) { goTo(Page.AlbumPage(s.albumId)) },
                MenuOption("Go to artist", Icons.Rounded.Person) { goTo(Page.ArtistPage(s.primaryArtist)) },
            ))
        }
        if (e.kind == Kind.ACTION || e.kind == Kind.SETTING) return null
        val songs = e.songs()
        val opts = ArrayList<MenuOption>()
        if (selectable(e)) opts += MenuOption("Select multiple", Icons.Rounded.CheckCircle) { toggleSelected(e) }
        if (e.kind == Kind.CONTAINER) {
            val collection = e.target is Page.AlbumPage || e.target is Page.PlaylistPage
            opts += MenuOption("Play", Icons.Rounded.PlayArrow) { PlayerConnection.playSongs(songs, fromCollection = collection) }
            opts += MenuOption("Shuffle", Icons.Rounded.Shuffle) { PlayerConnection.playSongs(songs, shuffle = true, fromCollection = collection) }
        }
        opts += MenuOption("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { PlayerConnection.playNext(songs); toast("Playing next") }
        opts += MenuOption("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { PlayerConnection.addToQueue(songs); toast("Added to queue") }
        opts += MenuOption("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { menu = playlistChooser(songs) }
        e.song?.let { s ->
            val liked = PlaylistStore.isLiked(s.id)
            opts += MenuOption(if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                if (liked) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite) { PlaylistStore.toggleLike(s.id) }
            opts += MenuOption("Go to album", Icons.Rounded.Album) { goTo(Page.AlbumPage(s.albumId)) }
            opts += MenuOption("Go to artist", Icons.Rounded.Person) { goTo(Page.ArtistPage(s.primaryArtist)) }
            val page = top.page
            if (page is Page.PlaylistPage && !page.id.startsWith("device:")) {
                opts += MenuOption("Remove from this playlist", Icons.Rounded.RemoveCircleOutline) { PlaylistStore.remove(page.id, s.id) }
            }
        }
        e.playlist?.let { p ->
            if (!p.readOnly && p.id != PlaylistStore.LIKED_ID) {
                opts += MenuOption("Rename playlist", Icons.Rounded.Edit) { renamePrompt = RenamePrompt("Rename playlist", p.name) { PlaylistStore.rename(p.id, it.trim()) } }
                opts += MenuOption("Delete playlist", Icons.Rounded.Delete) { PlaylistStore.delete(p.id); toast("Deleted ${p.name}") }
            }
        }
        return MenuState(e.title, opts)
    }

    BackHandler(enabled = pcProblem != null || aboutOpen || onboarding.isNotEmpty() || helpOpen || menu != null || stack.size > 1 || !tab.inHeader) {
        when {
            onboarding.isNotEmpty() -> nextOnboarding()
            pcProblem != null -> com.dualamp.library.RemoteLibrary.dismissProblem()
            aboutOpen -> aboutOpen = false
            helpOpen -> closeHelp()
            else -> back()
        }
    }

    // ----- Gamepad navigation -------------------------------------------------------------------
    var columns by remember { mutableIntStateOf(3) }
    val currentData by rememberUpdatedState(data)
    val currentView by rememberUpdatedState(view)
    val handleNav: (ControllerBus.Nav) -> Unit = handle@{ nav ->
        openPrompt.value?.let { p ->
            if (nav == ControllerBus.Nav.BACK) p.cancel() else if (p.kb.handle(nav)) p.confirm()
            return@handle
        }
        val kb = searchKb
        if (kb != null && keyboardOpen && stack.last().page is Page.Search && menu == null) {
            if (nav == ControllerBus.Nav.BACK) {
                keyboardOpen = false
                // Nothing typed: B leaves search straight away (no second press needed).
                if (kb.text.isEmpty()) back()
            } else if (kb.handle(nav)) keyboardOpen = false
            return@handle
        }
        if (nav == ControllerBus.Nav.OPEN_SETTINGS) {
            if (helpOpen) closeHelp()
            menu = null
            toggleTab(BottomTab.SETTINGS)
            return@handle
        }
        // Hold Start: the visualizer folders. Hold Select: search, with the keyboard open.
        // Hold Y: like the song playing or add it to a playlist, from anywhere.
        if (nav == ControllerBus.Nav.NOW_PLAYING_MENU) {
            val s = PlayerConnection.state.value.songId?.let { id -> localLib?.songById?.get(id) ?: pcLib?.songById?.get(id) }
            if (s == null) { toast("Nothing playing"); return@handle }
            if (helpOpen) closeHelp()
            val liked = PlaylistStore.isLiked(s.id)
            menu = MenuState("Now playing: ${s.title}", listOf(
                MenuOption(if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                    if (liked) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite) {
                    PlaylistStore.toggleLike(s.id); toast(if (liked) "Removed from Liked Songs" else "Added to Liked Songs")
                },
                MenuOption("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { menu = playlistChooser(listOf(s)) },
            ))
            return@handle
        }
        if (nav == ControllerBus.Nav.OPEN_VISUALIZERS) {
            if (helpOpen) closeHelp()
            menu = null
            tab = BottomTab.VISUALIZERS
            return@handle
        }
        // PC tab, not connected: Y opens Settings > PC music (the button on the screen).
        if (nav == ControllerBus.Nav.MENU && tab == BottomTab.PC && pcLib == null && stack.last().page is Page.Root &&
            !com.dualamp.library.RemoteLibrary.active.value) {
            openPcSettings()
            return@handle
        }
        // Select: the next tab of the header switch (also on pages with nothing to browse, like the PC tab).
        if (nav == ControllerBus.Nav.NEXT_CATEGORY) {
            val tabs = BottomTab.entries.filter { it.inHeader }
            tab = tabs[(tabs.indexOf(lastHeaderTab) + 1).mod(tabs.size)]
            chipsFocus = false
            if (headerFocus != null) headerFocus = headerSpotFor(tab)
            return@handle
        }
        if (nav == ControllerBus.Nav.OPEN_SEARCH) {
            if (helpOpen) closeHelp()
            menu = null
            openSearch()
            return@handle
        }
        // Y while items are selected: what to do with them.
        if (selection.isNotEmpty() && menu == null && !helpOpen && nav == ControllerBus.Nav.MENU) {
            val songs = selectedSongs()
            menu = MenuState("${selection.size} selected · ${songs.size} songs", listOf(
                MenuOption("Play", Icons.Rounded.PlayArrow) { PlayerConnection.playSongs(songs); clearSelection() },
                MenuOption("Shuffle", Icons.Rounded.Shuffle) { PlayerConnection.playSongs(songs, shuffle = true); clearSelection() },
                MenuOption("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { PlayerConnection.playNext(songs); toast("Playing next"); clearSelection() },
                MenuOption("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { PlayerConnection.addToQueue(songs); toast("Added to queue"); clearSelection() },
                MenuOption("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { menu = playlistChooser(songs); clearSelection() },
                MenuOption("Clear selection", Icons.Rounded.Close) { clearSelection() },
            ))
            return@handle
        }
        if (onboarding.isNotEmpty()) {
            when (nav) {
                ControllerBus.Nav.SELECT -> onboardingPrimary()
                ControllerBus.Nav.BACK -> nextOnboarding()
                else -> Unit
            }
            return@handle
        }
        if (pcProblem != null && onboarding.isEmpty()) {
            if (nav == ControllerBus.Nav.SELECT) retryPc()
            if (nav == ControllerBus.Nav.BACK) com.dualamp.library.RemoteLibrary.dismissProblem()
            return@handle
        }
        if (aboutOpen) {
            if (nav == ControllerBus.Nav.SELECT || nav == ControllerBus.Nav.BACK) aboutOpen = false
            return@handle
        }
        if (helpOpen) {
            when (nav) {
                ControllerBus.Nav.SELECT, ControllerBus.Nav.BACK -> closeHelp()
                ControllerBus.Nav.MENU -> helpDontShow = !helpDontShow
                ControllerBus.Nav.DOWN -> scope.launch { helpScroll.animateScrollBy(260f) }
                ControllerBus.Nav.UP -> scope.launch { helpScroll.animateScrollBy(-260f) }
                else -> Unit
            }
            return@handle
        }
        if (nav == ControllerBus.Nav.CLEAR_QUEUE) {
            if (player.upNext.isNotEmpty()) menu = confirmClearQueue()
            return@handle
        }
        // L3: the next view (grid, list, Cover Flow) on pages that have one.
        if (nav == ControllerBus.Nav.CHANGE_VIEW) {
            if (!fixedList) com.dualamp.settings.LibraryView.set(rootPage, ViewMode.entries[(view.ordinal + 1) % ViewMode.entries.size].name)
            return@handle
        }
        val m = menu
        if (m != null) {
            when (nav) {
                ControllerBus.Nav.UP -> m.cursor = (m.cursor - 1).coerceAtLeast(0)
                ControllerBus.Nav.DOWN -> m.cursor = (m.cursor + 1).coerceAtMost(m.options.lastIndex)
                ControllerBus.Nav.SELECT -> { menu = null; m.options.getOrNull(m.cursor)?.run?.invoke() }
                ControllerBus.Nav.BACK, ControllerBus.Nav.MENU, ControllerBus.Nav.LEFT -> menu = null
                else -> Unit
            }
            return@handle
        }
        // Category chips: up from the top row of a Library / PC page. Left / right switch category straight away,
        // up goes on to the header, down / A / B go back to the page.
        if (chipsFocus && (nav in DIRECTIONS || nav == ControllerBus.Nav.SELECT || nav == ControllerBus.Nav.BACK)) {
            val cur = (stack.last().page as? Page.Root)?.category
            when (nav) {
                ControllerBus.Nav.LEFT, ControllerBus.Nav.RIGHT -> cur?.let {
                    val step = if (nav == ControllerBus.Nav.RIGHT) 1 else -1
                    selectCategory(Category.entries[(it.ordinal + step).mod(Category.entries.size)])
                }
                ControllerBus.Nav.UP -> { chipsFocus = false; headerFocus = headerSpotFor(tab) }
                else -> chipsFocus = false
            }
            if (cur == null) chipsFocus = false
            return@handle
        }
        // The header (tabs, search, visualizers, settings): D-pad up from the top row of a page gets here;
        // left / right move along it, A picks, down or B goes back to the page.
        // (Other buttons, like the Select button or Y, keep their usual jobs while the cursor is up there.)
        headerFocus?.takeIf { nav in DIRECTIONS || nav == ControllerBus.Nav.SELECT || nav == ControllerBus.Nav.BACK }?.let { f ->
            val spots = HeaderSpot.entries.filter { it != HeaderSpot.SEARCH || tab != BottomTab.EQUALIZER }
            val i = spots.indexOf(f).coerceAtLeast(0)
            when (nav) {
                ControllerBus.Nav.LEFT -> headerFocus = spots[(i - 1).coerceAtLeast(0)]
                ControllerBus.Nav.RIGHT -> headerFocus = spots[(i + 1).coerceAtMost(spots.lastIndex)]
                ControllerBus.Nav.DOWN, ControllerBus.Nav.BACK -> {
                    headerFocus = null
                    if (nav == ControllerBus.Nav.DOWN && stack.last().page is Page.Root) chipsFocus = true
                }
                ControllerBus.Nav.SELECT -> when (f) {
                    HeaderSpot.PC -> tab = BottomTab.PC
                    HeaderSpot.LIBRARY -> tab = BottomTab.LIBRARY
                    HeaderSpot.EQUALIZER -> tab = BottomTab.EQUALIZER
                    HeaderSpot.SEARCH -> { headerFocus = null; openSearch() }
                    HeaderSpot.VISUALIZERS -> toggleTab(BottomTab.VISUALIZERS)
                    HeaderSpot.SETTINGS -> toggleTab(BottomTab.SETTINGS)
                }
                else -> Unit
            }
            return@handle
        }
        // A page with nothing to move through (e.g. the PC tab before connecting): up goes straight to the header.
        if (nav == ControllerBus.Nav.UP && stack.last().page !is Page.Search && currentData?.entries.isNullOrEmpty()) {
            usingPad = true
            if (stack.last().page is Page.Root) chipsFocus = true else headerFocus = headerSpotFor(tab)
            return@handle
        }
        val d = currentData ?: return@handle
        val entries = d.entries
        val t = stack.last()
        val atRoot = t.page is Page.Root
        if (!usingPad) {
            usingPad = true
            t.cursor = t.cursor.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
            // In lists the cursor may be off screen, so the first press only shows it. Cover Flow
            // always has its cursor in the middle, so the press acts straight away.
            if (currentView != ViewMode.COVERFLOW && nav in DIRECTIONS) return@handle
        }
        // Never rest on a section title: move to the first row after it.
        if (entries.getOrNull(t.cursor)?.kind == Kind.HEADER) {
            t.cursor = (t.cursor until entries.size).firstOrNull { entries[it].kind != Kind.HEADER } ?: t.cursor
        }
        // Cover Flow: its buttons (Shuffle, All songs, Albums...) are one row and the covers another.
        val rows = if (currentView == ViewMode.COVERFLOW) coverFlowRows(entries)
            else navRows(entries, if (currentView == ViewMode.GRID) columns else 1)
        val flow = currentView == ViewMode.COVERFLOW
        val (row, col) = locate(rows, t.cursor)
        fun cycleCategory(step: Int) {
            val cur = (t.page as? Page.Root)?.category ?: Category.ALBUMS
            selectCategory(Category.entries[(cur.ordinal + step).mod(Category.entries.size)])
        }
        // Left/right on a slider row nudges the value instead of navigating.
        val slider = entries.getOrNull(t.cursor)?.slider
        if (slider != null && (nav == ControllerBus.Nav.LEFT || nav == ControllerBus.Nav.RIGHT)) {
            val delta = if (nav == ControllerBus.Nav.RIGHT) slider.step else -slider.step
            slider.setting.value = (slider.setting.value + delta).coerceIn(slider.min, slider.max)
            return@handle
        }
        when (nav) {
            ControllerBus.Nav.UP -> if (row > 0) t.cursor = rows[row - 1].let { it[col.coerceAtMost(it.lastIndex)] }
                else if (t.page is Page.Root) chipsFocus = true
                else if (t.page !is Page.Search) headerFocus = headerSpotFor(tab)
            ControllerBus.Nav.DOWN -> if (row < rows.lastIndex) t.cursor = rows[row + 1].let { it[col.coerceAtMost(it.lastIndex)] }
            ControllerBus.Nav.LEFT -> when {
                rows.isNotEmpty() && col > 0 -> t.cursor = rows[row][col - 1]
                flow -> Unit // the first cover or button: stay (no tab switch)
                atRoot -> cycleCategory(-1)
                else -> back()
            }
            ControllerBus.Nav.RIGHT -> when {
                rows.isNotEmpty() && col < rows[row].lastIndex -> t.cursor = rows[row][col + 1]
                flow -> Unit
                atRoot -> cycleCategory(1)
                else -> entries.getOrNull(t.cursor)?.takeIf { it.target != null }?.let(::activate)
            }
            ControllerBus.Nav.SELECT -> entries.getOrNull(t.cursor)?.let(::activate)
            ControllerBus.Nav.BACK -> back()
            ControllerBus.Nav.MENU -> entries.getOrNull(t.cursor)?.let { menu = menuFor(it) }
            else -> Unit
        }
    }
    // The collector lives for the whole screen, so it always calls the latest handler (and with it
    // the current page data) rather than the one from the first composition.
    val latestHandleNav by rememberUpdatedState(handleNav)
    LaunchedEffect(Unit) { ControllerBus.nav.collect { latestHandleNav(it) } }
    // Keep the gamepad cursor fully on screen (names included), all the way to the last row.
    val headerItems = if (data?.hero != null) 1 else 0
    LaunchedEffect(top, top.cursor, usingPad, view) {
        if (!usingPad || view == ViewMode.COVERFLOW) return@LaunchedEffect
        val index = top.cursor + headerItems
        val margin = 12f
        // The first row (Shuffle on an artist, Play on an album): show the top of the page, art and name included.
        if (top.cursor == 0) {
            if (view == ViewMode.GRID) top.grid.animateScrollToItem(0) else top.list.animateScrollToItem(0)
            return@LaunchedEffect
        }
        if (view == ViewMode.GRID) {
            val info = top.grid.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            if (item == null) { top.grid.animateScrollToItem(index); return@LaunchedEffect }
            val below = item.offset.y + item.size.height - (info.viewportEndOffset - info.afterContentPadding)
            val above = info.viewportStartOffset - item.offset.y
            if (below > 0) top.grid.animateScrollBy(below + margin)
            else if (above > 0) top.grid.animateScrollBy(-(above + margin))
        } else {
            val info = top.list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            if (item == null) { top.list.animateScrollToItem((index - 1).coerceAtLeast(0)); return@LaunchedEffect }
            val below = item.offset + item.size - (info.viewportEndOffset - info.afterContentPadding)
            val above = info.viewportStartOffset - item.offset
            if (below > 0) top.list.animateScrollBy(below + margin)
            else if (above > 0) top.list.animateScrollBy(-(above + margin))
        }
    }

    // ----- Layout -------------------------------------------------------------------------------
    Box(
        modifier
            .fillMaxSize()
            .background(Surface1)
            .holdKeyFocus()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    usingPad = false
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrowHeader = maxWidth < 380.dp
            Header(
                compact = narrowHeader,
                page = top.page,
                title = if (tab == BottomTab.PC && top.page is Page.Root) "PC music" else data?.title ?: "Your Library",
                canGoBack = stack.size > 1 || !tab.inHeader,
                onBack = { back() },
                onSearch = ::openSearch,
                onQuery = { q -> stack[stack.lastIndex] = StackEntry(Page.Search(q)) },
                searchState = searchKb,
                onOpenKeyboard = { keyboardOpen = true },
                showSearch = tab != BottomTab.EQUALIZER,
                tab = tab,
                onTab = { tab = it },
                onSettings = { toggleTab(BottomTab.SETTINGS) },
                onVisualizers = { toggleTab(BottomTab.VISUALIZERS) },
                onAbout = { aboutOpen = true },
                focus = if (usingPad) headerFocus else null,
            )
            }
            if (top.page is Page.Root) {
                CategoryChips((top.page as Page.Root).category, ::selectCategory, focused = usingPad && chipsFocus)
            }
            if (data != null && (data.sorts.isNotEmpty() || !fixedList)) {
                SortRow(
                    sorts = data.sorts,
                    current = chosenSort ?: data.defaultSort,
                    desc = desc,
                    view = view,
                    canChangeView = !fixedList,
                    menuOpen = sortMenuOpen,
                    onMenu = { sortMenuOpen = it },
                    onSort = { s ->
                        val newDesc = if (s == (chosenSort ?: data.defaultSort)) !desc else false
                        Prefs.mapPut(Prefs.sortModes, typeKey, "${s.name}:${if (newDesc) "desc" else "asc"}")
                    },
                    onView = {
                        val next = ViewMode.entries[(view.ordinal + 1) % ViewMode.entries.size]
                        com.dualamp.settings.LibraryView.set(rootPage, next.name)
                    },
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !hasPermission -> PermissionPrompt {
                        AppEvents.markOwnLaunch()
                        permissionLauncher.launch(arrayOf(LibraryRepository.permission, Manifest.permission.POST_NOTIFICATIONS))
                    }
                    musicFolders.isBlank() && tab == BottomTab.LIBRARY && top.page is Page.Root -> FolderPrompt { run { AppEvents.markOwnLaunch(); folderLauncher.launch(null) } }
                    tab == BottomTab.PC && pcLib == null && top.page is Page.Root ->
                        if (pcActive) Loading() else PcPrompt(::openPcSettings)
                    data == null -> Loading()
                    data.entries.isEmpty() && data.hero == null -> EmptyState(top.page)
                    else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Squares per row: Settings > Grid size (3-6; its own value in single-screen mode).
                        val cols = (if (singleScreenLayout) Prefs.gridColumnsSingle else Prefs.gridColumns).state.collectAsState().value.coerceIn(3, 6)
                        LaunchedEffect(cols) { columns = cols }
                        PageContent(
                            data = data,
                            view = view,
                            columns = cols,
                            entry = top,
                            highlight = if (usingPad && headerFocus == null && !chipsFocus) top.cursor else -1,
                            nowPlaying = player.songId,
                            onClick = { i, e -> top.cursor = i; activate(e) },
                            onLongClick = { i, e -> top.cursor = i; if (selection.isNotEmpty() && selectable(e)) toggleSelected(e) else menu = menuFor(e) },
                        )
                    }
                }
            }
            if (selection.isNotEmpty()) SelectionBar(
                count = selection.size,
                onPlay = { PlayerConnection.playSongs(selectedSongs()); clearSelection() },
                onNext = { PlayerConnection.playNext(selectedSongs()); toast("Playing next"); clearSelection() },
                onQueue = { PlayerConnection.addToQueue(selectedSongs()); toast("Added to queue"); clearSelection() },
                onPlaylist = { menu = playlistChooser(selectedSongs()); clearSelection() },
                onCancel = ::clearSelection,
            )
            if (keyboardOpen && searchKb != null && top.page is Page.Search) {
                GamepadKeyboard(searchKb!!, highlight = true, onDone = { keyboardOpen = false })
            } else if (showMiniPlayer && top.page !is Page.Search) MiniPlayer(
                onOpenQueue = { selectCategory(Category.QUEUE) },
                onAddToPlaylist = { song -> menu = playlistChooser(listOf(song)) },
                onSongMenu = { song -> menu = songMenu(song) },
            )
        }

        menu?.let { m -> MenuOverlay(m, highlight = usingPad, onDismiss = { menu = null }) { opt -> menu = null; opt.run() } }
        // First launch: music folder, then the asleep-buttons permission, then the controls guide.
        if (onboarding.isNotEmpty()) OnboardingOverlay(onboarding.first(), onPrimary = ::onboardingPrimary, onSecondary = ::nextOnboarding)
        else if (helpOpen) ControlsOverlay(helpDontShow, onToggleDontShow = { helpDontShow = !helpDontShow }, onClose = ::closeHelp, listState = helpScroll)
        if (aboutOpen) AboutOverlay(onClose = { aboutOpen = false })
        pcProblem?.let { problem ->
            if (onboarding.isEmpty()) PromptOverlay(
                "Can't reach your PC", pcProblemText(problem), "Try again (A)", "OK (B)",
                onPrimary = ::retryPc, onSecondary = { com.dualamp.library.RemoteLibrary.dismissProblem() },
            )
        }
    }

    renamePrompt?.let { r ->
        NameDialog(
            title = r.title,
            initial = r.current,
            onDismiss = { renamePrompt = null },
            onCreate = { name -> if (name.isNotBlank()) r.onRename(name); renamePrompt = null; if (r.confirm == "Rename") toast("Renamed to $name") },
            confirmLabel = r.confirm,
        )
    }

    newVizFolder?.let { pending ->
        NameDialog(
            title = "New visualizer folder",
            initial = "My visualizers #${vizFolders.size + 1}",
            onDismiss = { newVizFolder = null },
            onCreate = { name ->
                val f = VizFolders.create(name, listOfNotNull(pending.preset?.path))
                newVizFolder = null
                toast("Created ${f.name}")
            },
        )
    }

    newPlaylistSongs?.let { songs ->
        NameDialog(
            title = "New playlist",
            initial = "My playlist #${playlists.size}",
            onDismiss = { newPlaylistSongs = null },
            onCreate = { name ->
                val p = PlaylistStore.create(name, songs.map { it.id })
                newPlaylistSongs = null
                toast("Created ${p.name}")
            },
        )
    }
}

/** Splits entries into the rows they occupy on screen, for up/down movement in grids. */
/** Cover Flow's gamepad rows: the buttons above the covers, then the covers. */
private fun coverFlowRows(entries: List<Entry>): List<List<Int>> {
    val stops = entries.indices.filter { entries[it].kind != Kind.HEADER }
    return listOf(stops.filter { entries[it].fullWidth }, stops.filterNot { entries[it].fullWidth }).filter { it.isNotEmpty() }
}

private fun navRows(entries: List<Entry>, columns: Int): List<List<Int>> {
    val rows = ArrayList<List<Int>>()
    var current = ArrayList<Int>()
    entries.forEachIndexed { i, e ->
        if (e.kind == Kind.HEADER) return@forEachIndexed // section titles are not stops
        if (e.fullWidth || columns == 1) {
            if (current.isNotEmpty()) { rows += current; current = ArrayList() }
            rows += listOf(i)
        } else {
            current += i
            if (current.size == columns) { rows += current; current = ArrayList() }
        }
    }
    if (current.isNotEmpty()) rows += current
    return rows
}

private fun locate(rows: List<List<Int>>, index: Int): Pair<Int, Int> {
    rows.forEachIndexed { r, row -> val c = row.indexOf(index); if (c >= 0) return r to c }
    return 0 to 0
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun Header(
    compact: Boolean,
    page: Page,
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onQuery: (String) -> Unit,
    searchState: KeyboardState? = null,
    onOpenKeyboard: () -> Unit = {},
    showSearch: Boolean,
    tab: BottomTab,
    onTab: (BottomTab) -> Unit,
    onSettings: () -> Unit,
    onVisualizers: () -> Unit,
    onAbout: () -> Unit,
    /** The header item the gamepad cursor is on, if any. */
    focus: HeaderSpot? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        } else {
            // App logo (same artwork as the launcher icon).
            Box(
                Modifier.padding(horizontal = 6.dp).size(36.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF120F1F))
                    .clickable(onClick = onAbout),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), "DualAmp", Modifier.requiredSize(54.dp))
            }
        }
        if (page is Page.Search) {
            if (searchState != null) KeyboardTextDisplay(searchState, onTap = onOpenKeyboard, modifier = Modifier.weight(1f))
            // Closes the keyboard and leaves search (B does the same).
            TextButton(onClick = onBack) { Text("Cancel", color = TextMain) }
            return@Row
        } else {
            // A title too long for its space scrolls; on narrow screens the tabs and buttons scroll sideways.
            Text(
                title,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp).scrollIfLong(),
            )
            val buttonsScroll = rememberScrollState()
            Row(
                Modifier.weight(if (compact) 2.4f else 1.6f, fill = false).horizontalScroll(buttonsScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            HeaderTabs(tab, onTab, compact, focus)
            // Slides in and out (the tabs move over to make room) when switching to or from Equalizer.
            androidx.compose.animation.AnimatedVisibility(
                visible = showSearch,
                enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
            ) {
                IconButton(onClick = onSearch, modifier = Modifier.highlight(focus == HeaderSpot.SEARCH)) { Icon(Icons.Rounded.Search, "Search") }
            }
            // Highlighted while their tab is open; pressing again goes back.
            IconButton(onClick = onVisualizers, modifier = Modifier.highlight(focus == HeaderSpot.VISUALIZERS)) {
                Icon(Icons.Rounded.AutoAwesome, "Visualizer folders", tint = if (tab == BottomTab.VISUALIZERS) Accent else IconTint)
            }
            IconButton(onClick = onSettings, modifier = Modifier.highlight(focus == HeaderSpot.SETTINGS)) {
                Icon(Icons.Rounded.Settings, "Settings", tint = if (tab == BottomTab.SETTINGS) Accent else IconTint)
            }
            }
            // Keep the gamepad's header item in view.
            LaunchedEffect(focus) { if (focus == HeaderSpot.SETTINGS || focus == HeaderSpot.VISUALIZERS) buttonsScroll.animateScrollTo(buttonsScroll.maxValue) else if (focus != null && focus.ordinal <= HeaderSpot.EQUALIZER.ordinal) buttonsScroll.animateScrollTo(0) }
        }
    }
}

@Composable
private fun CategoryChips(selected: Category, onSelect: (Category) -> Unit, focused: Boolean = false) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { state.animateScrollToItem((selected.ordinal - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        items(Category.entries) { c ->
            val on = c == selected
            Text(
                c.label,
                color = if (on) Color.Black else TextMain,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Accent else Surface3)
                    // Gamepad cursor on the chips: a ring round the selected one.
                    .then(if (on && focused) Modifier.border(2.dp, TextMain, RoundedCornerShape(50)) else Modifier)
                    .clickable { onSelect(c) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SortRow(
    sorts: List<Sort>,
    current: Sort?,
    desc: Boolean,
    view: ViewMode,
    canChangeView: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onSort: (Sort) -> Unit,
    onView: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sorts.isNotEmpty() && current != null) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable { onMenu(true) }.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (desc) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(current.label, style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                    sorts.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, color = if (s == current) Accent else TextMain) },
                            trailingIcon = if (s == current) ({
                                Icon(if (desc) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, tint = Accent)
                            }) else null,
                            onClick = { onSort(s); onMenu(false) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (canChangeView) {
            IconButton(onClick = onView, modifier = Modifier.size(34.dp)) {
                Icon(
                    when (view) {
                        ViewMode.LIST -> Icons.AutoMirrored.Rounded.ViewList
                        ViewMode.GRID -> Icons.Rounded.GridView
                        ViewMode.COVERFLOW -> Icons.Rounded.ViewCarousel
                    },
                    "Change view", Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageContent(
    data: PageData,
    view: ViewMode,
    columns: Int,
    entry: StackEntry,
    highlight: Int,
    nowPlaying: Long?,
    onClick: (Int, Entry) -> Unit,
    onLongClick: (Int, Entry) -> Unit,
) {
    val bottomPad = PaddingValues(bottom = 12.dp)
    if (view == ViewMode.COVERFLOW) {
        CoverFlow(
            data = data,
            state = entry.flow,
            cursor = entry.cursor,
            padActive = highlight >= 0,
            nowPlaying = nowPlaying,
            onCursor = { entry.cursor = it },
            onActivate = onClick,
            onLongPress = onLongClick,
        )
    } else if (view == ViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = entry.grid,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            data.hero?.let { hero -> item(span = { GridItemSpan(maxLineSpan) }) { HeroHeader(hero) } }
            itemsIndexed(data.entries, key = { _, e -> e.key }, span = { _, e -> GridItemSpan(if (e.fullWidth) maxLineSpan else 1) }) { i, e ->
                val mod = Modifier.combinedClickable(onClick = { onClick(i, e) }, onLongClick = { onLongClick(i, e) })
                if (e.fullWidth) EntryRow(e, i == highlight, false, mod)
                else EntryTile(e, i == highlight, mod)
            }
        }
    } else {
        LazyColumn(state = entry.list, contentPadding = bottomPad, modifier = Modifier.fillMaxSize()) {
            data.hero?.let { hero -> item { HeroHeader(hero) } }
            itemsIndexed(data.entries, key = { _, e -> e.key }) { i, e ->
                val mod = Modifier.combinedClickable(onClick = { onClick(i, e) }, onLongClick = { onLongClick(i, e) })
                val playing = e.song != null && e.song.id == nowPlaying
                EntryRow(e, i == highlight, playing, mod)
            }
        }
    }
}

/** Text too long for its space scrolls sideways now and then, so all of it can be read. */
internal fun Modifier.scrollIfLong(): Modifier =
    basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2500)

internal fun Modifier.highlight(on: Boolean): Modifier =
    if (!on) this else this
        .background(Accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
        .border(1.5.dp, Accent, RoundedCornerShape(8.dp))

@Composable
internal fun ArtBox(art: Art, round: Boolean, modifier: Modifier) {
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    when (art) {
        is Art.Cover -> AlbumArt(art.albumId, art.songId, modifier, shape = shape)
        is Art.Symbol -> Placeholder(art.seed, modifier.aspectRatio(1f).clip(shape), icon = art.icon)
        is Art.TrackNo -> Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
            Text(if (art.number > 0) art.number.toString() else "–", color = TextDim, style = MaterialTheme.typography.bodyLarge)
        }
        Art.None -> Spacer(modifier)
    }
}

@Composable
private fun EntryRow(e: Entry, highlighted: Boolean, playing: Boolean, modifier: Modifier) {
    if (e.slider != null) return SliderRow(e, e.slider, highlighted, modifier)
    if (e.kind == Kind.HEADER) {
        Text(e.title.uppercase(), color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp))
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .highlight(highlighted)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtBox(e.art, e.round, Modifier.size(if (e.kind == Kind.SETTING) 36.dp else 48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) {
                    Icon(Icons.Rounded.Equalizer, null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                EntryBadge(e)
                Text(
                    e.title,
                    color = if (playing) Accent else TextMain,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (e.kind == Kind.ACTION) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.scrollIfLong(),
                )
            }
            if (e.subtitle.isNotEmpty()) {
                Text(e.subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.scrollIfLong())
            }
        }
        if (e.choices != null) {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.trailing.orEmpty(), color = Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = Accent)
                }
                DropdownMenu(expanded = openDropdown.value == e.key, onDismissRequest = { openDropdown.value = null }) {
                    e.choices.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c, color = if (c == e.trailing) Accent else TextMain) },
                            trailingIcon = if (c == e.trailing) ({ Icon(Icons.Rounded.Check, null, tint = Accent) }) else null,
                            onClick = { e.onChoose?.invoke(c); openDropdown.value = null },
                        )
                    }
                }
            }
        } else e.trailing?.let {
            Text(it, color = if (e.kind == Kind.SETTING) Accent else TextDim, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp))
        }
        if (e.key in selectedKeys.value) SelectedMark(Modifier.padding(start = 8.dp))
        if (e.kind == Kind.CONTAINER) Icon(Icons.Rounded.ChevronRight, null, tint = TextDim)
    }
}

/** The entry's kind icon (artist / album) before its title, when it has one. */
@Composable
private fun EntryBadge(e: Entry) {
    val icon = e.badge ?: return
    Icon(icon, null /* the subtitle says Artist / Album / Single */, tint = TextDim, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
}

/** A setting with a slider under its title, e.g. seconds per visualizer (20-120). */
@Composable
private fun SliderRow(e: Entry, spec: SliderSpec, highlighted: Boolean, modifier: Modifier) {
    val value by spec.setting.state.collectAsState()
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .highlight(highlighted)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtBox(e.art, false, Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.scrollIfLong())
                if (e.subtitle.isNotEmpty()) {
                    Text(e.subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.scrollIfLong())
                }
            }
            Text(if (spec.minLabel != null && value <= spec.min) spec.minLabel else spec.label?.invoke(value) ?: "$value ${spec.unit}", color = Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
        val steps = ((spec.max - spec.min) / spec.step - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = { spec.setting.value = it.toInt().coerceIn(spec.min, spec.max) },
            valueRange = spec.min.toFloat()..spec.max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            modifier = Modifier.padding(start = 48.dp),
        )
    }
}

@Composable
private fun EntryTile(e: Entry, highlighted: Boolean, modifier: Modifier) {
    Column(modifier.padding(4.dp).highlight(highlighted).padding(4.dp)) {
        Box {
            // Songs show their album art (the list view shows track numbers instead).
            val art = if (e.song != null && e.art is Art.TrackNo) Art.Cover(e.song.albumId, e.song.id) else e.art
            ArtBox(art, e.round, Modifier.fillMaxWidth())
            if (e.key in selectedKeys.value) SelectedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(e.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
            textAlign = if (e.round) androidx.compose.ui.text.style.TextAlign.Center else null,
            modifier = Modifier.fillMaxWidth().scrollIfLong())
        Text(e.subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            textAlign = if (e.round) androidx.compose.ui.text.style.TextAlign.Center else null, modifier = Modifier.fillMaxWidth().scrollIfLong())
    }
}

@Composable
private fun HeroHeader(h: Hero) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtBox(h.art, h.round, Modifier.size(96.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(h.subtitle, color = TextMain, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.detail, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MenuOverlay(m: MenuState, highlight: Boolean, onDismiss: () -> Unit, onPick: (MenuOption) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Surface2,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(0.85f).heightIn(max = 520.dp).clickable(enabled = false) {},
        ) {
            val state = rememberLazyListState()
            LaunchedEffect(m.cursor) { if (highlight) state.animateScrollToItem((m.cursor - 2).coerceAtLeast(0)) }
            LazyColumn(state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
                itemsIndexed(m.options) { i, opt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(opt) }
                            .padding(horizontal = 8.dp)
                            .highlight(highlight && i == m.cursor)
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(opt.icon, null, tint = TextDim, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniPlayer(onOpenQueue: () -> Unit, onAddToPlaylist: (Song) -> Unit, onSongMenu: (Song) -> Unit) {
    val player by PlayerConnection.state.collectAsState()
    val lib by LibraryRepository.library.collectAsState()
    val song = player.songId?.let { lib?.songById?.get(it) } ?: return
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player.durationMs) {
        while (true) {
            progress = if (player.durationMs > 0) (PlayerConnection.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Surface(color = Surface2, modifier = Modifier.fillMaxWidth().padding(6.dp).clip(RoundedCornerShape(8.dp))) {
        Column {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = onOpenQueue, onLongClick = { onSongMenu(song) }).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(song.albumId, song.id, Modifier.size(40.dp), shape = RoundedCornerShape(4.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onAddToPlaylist(song) }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") }
                IconButton(onClick = PlayerConnection::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                IconButton(onClick = PlayerConnection::togglePlay) {
                    Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause")
                }
                IconButton(onClick = PlayerConnection::next) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
            LinearProgressIndicator(
                progress = { progress },
                color = TextMain,
                trackColor = TextMain.copy(alpha = 0.15f),
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("DualAmp needs access to your music", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Songs are read from this device's music library.", color = TextDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Allow access") }
    }
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(12.dp))
        Text("Scanning your library…", color = TextDim)
    }
}

@Composable
private fun EmptyState(page: Page) {
    val text = when (page) {
        is Page.Search -> if (page.query.isBlank()) "Search your library" else "No results for \"${page.query}\""
        Page.Root(Category.QUEUE) -> "The queue is empty. Pick something to play."
        Page.Presets -> "No presets yet. Import a folder of .milk files in Settings."
        else -> "Nothing here yet"
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextDim, fontSize = 15.sp)
    }
}

/** PC tab while no PC is connected: what to do, and a button to Settings > PC music. */
@Composable
private fun PcPrompt(onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Computer, null, tint = TextDim, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Not connected to a PC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Run DualAmp Server on your PC, then connect to it to play your PC's music here.",
            color = TextDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpen) { Text("Connect in Settings > PC music (Y)") }
    }
}

/** First launch: no music folder chosen yet. */
@Composable
private fun FolderPrompt(onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Add your music", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Choose the folder that holds your music, for example a folder on your SD card. You can add more folders later in Settings > Music folders.",
            color = TextDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPick) { Text("Choose music folder") }
    }
}

/**
 * Search box. A plain EditText is used so the keyboard can be told not to go full-screen: on the
 * Thor's wide bottom screen Android otherwise covers the whole app with its own text box, leaving no
 * way back except the keyboard's own button.
 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val latest by rememberUpdatedState(onQuery)
    AndroidView(
        modifier = modifier.heightIn(min = 44.dp),
        factory = { ctx ->
            android.widget.EditText(ctx).apply {
                isSingleLine = true
                hint = "Artists, albums, songs"
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF9E9E9E.toInt())
                textSize = 16f
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF2A2A2A.toInt())
                    cornerRadius = 8 * resources.displayMetrics.density
                }
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                doAfterTextChanged { latest(it?.toString().orEmpty()) }
                setOnEditorActionListener { v, _, _ ->
                    ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                    true
                }
                requestFocus()
                post {
                    ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        },
        update = { if (it.text.toString() != query) it.setText(query) },
    )
}

/**
 * The bottom screen's sections, each with its own pages. [inHeader] ones are the header's tab switch; Settings
 * (the gear) and Visualizers (the sparkle button) are tabs of their own too, so opening them never takes over
 * Library, PC or Equalizer.
 */
internal enum class BottomTab(val label: String, val icon: ImageVector, val inHeader: Boolean = true) {
    PC("PC", Icons.Rounded.Computer),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    EQUALIZER("Equalizer", Icons.Rounded.Equalizer),
    VISUALIZERS("Visualizers", Icons.Rounded.AutoAwesome, inHeader = false),
    SETTINGS("Settings", Icons.Rounded.Settings, inHeader = false),
}


/** The header's gamepad stops, left to right (D-pad up from the top of a page reaches them). */
internal enum class HeaderSpot { PC, LIBRARY, EQUALIZER, SEARCH, VISUALIZERS, SETTINGS }

/** The header stop for the tab you are in. */
internal fun headerSpotFor(tab: BottomTab) = HeaderSpot.valueOf(tab.name)

private val DIRECTIONS = setOf(ControllerBus.Nav.UP, ControllerBus.Nav.DOWN, ControllerBus.Nav.LEFT, ControllerBus.Nav.RIGHT)

/** Key of the row whose drop-down list is open (only one at a time). */
private val openDropdown = mutableStateOf<String?>(null)

/** PC / Library / Equalizer switch in the header, as icons (the selected one highlighted). */
@Composable
private fun HeaderTabs(selected: BottomTab, onSelect: (BottomTab) -> Unit, compact: Boolean = false, focus: HeaderSpot? = null) {
    val link by com.dualamp.library.RemoteLibrary.connection.collectAsState()
    Row(
        Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(50)).background(Surface2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomTab.entries.filter { it.inHeader }.forEach { t ->
            val on = t == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Surface3 else Color.Transparent)
                    .then(if (focus?.name == t.name) Modifier.border(2.dp, Accent, RoundedCornerShape(50)) else Modifier)
                    .clickable { onSelect(t) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // PC: a spinner while looking for the PC (or after losing it), a checkmark while connected,
                // nothing when you have not connected this time.
                if (t == BottomTab.PC && link != com.dualamp.library.RemoteLibrary.Connection.NONE) {
                    if (link == com.dualamp.library.RemoteLibrary.Connection.CONNECTED)
                        Icon(Icons.Rounded.Check, "Connected", tint = Accent, modifier = Modifier.size(16.dp))
                    else androidx.compose.material3.CircularProgressIndicator(
                        color = if (link == com.dualamp.library.RemoteLibrary.Connection.LOST) Color(0xFFFFB74D) else TextDim,
                        strokeWidth = 2.dp, modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                // Icons only: the title on the left already names the tab you are in.
                Icon(t.icon, t.label, tint = if (on) Accent else TextDim, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Keys of the entries currently picked in multi-select (read by the row and tile composables). */
internal val selectedKeys = mutableStateOf<Set<String>>(emptySet())

@Composable
internal fun SelectedMark(modifier: Modifier = Modifier) {
    Icon(Icons.Rounded.CheckCircle, "Selected", tint = Accent,
        modifier = modifier.size(24.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape))
}

/** Shown while items are selected: what to do with all of them at once. */
@Composable
private fun SelectionBar(
    count: Int,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onPlaylist: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = Accent.copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp))) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Clear selection") }
            Text("$count selected", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = onPlay) { Text("Play", color = TextMain) }
            TextButton(onClick = onNext) { Text("Next", color = TextMain) }
            TextButton(onClick = onQueue) { Text("Queue", color = TextMain) }
            TextButton(onClick = onPlaylist) { Text("Playlist", color = TextMain) }
        }
    }
}

/** A text prompt that is open, so the gamepad types into it (see [NameDialog]). */
private class OpenPrompt(val kb: KeyboardState, val confirm: () -> Unit, val cancel: () -> Unit)
private val openPrompt = mutableStateOf<OpenPrompt?>(null)

/**
 * Asks for a name or address with DualAmp's own keyboard (gamepad or touch), not the system one,
 * which on the Thor's wide bottom screen covers the whole app.
 */
@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onCreate: (String) -> Unit, confirmLabel: String = "Create") {
    val kb = remember { KeyboardState(initial) }
    val confirm = { if (kb.text.isNotBlank()) onCreate(kb.text.trim()) }
    DisposableEffect(Unit) {
        openPrompt.value = OpenPrompt(kb, confirm, onDismiss)
        ControllerBus.textInput = true
        onDispose { openPrompt.value = null; ControllerBus.textInput = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = Surface2, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    KeyboardTextDisplay(kb, onTap = {}, modifier = Modifier.fillMaxWidth(), placeholder = "")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel (B)", color = TextMain) }
                        TextButton(onClick = confirm) { Text("$confirmLabel (Start)") }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            GamepadKeyboard(kb, highlight = true, onDone = confirm)
        }
    }
}

/** The library is half of a single-screen layout (Settings then show the single-screen grid size). */
internal var singleScreenLayout = false
