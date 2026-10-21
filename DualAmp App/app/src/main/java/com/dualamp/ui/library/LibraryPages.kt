package com.dualamp.ui.library

import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.ui.graphics.vector.ImageVector
import com.dualamp.library.Album
import com.dualamp.library.Artist
import com.dualamp.library.Folder
import com.dualamp.library.Genre
import com.dualamp.library.Library
import com.dualamp.library.LibraryRepository
import com.dualamp.library.LibraryRepository.sortKey
import com.dualamp.library.Playlist
import com.dualamp.library.PlaylistStore
import com.dualamp.library.Song
import com.dualamp.library.YearGroup
import com.dualamp.playback.PlayerConnection
import com.dualamp.playback.PlayerState
import com.dualamp.ui.formatDurationLong
import com.dualamp.ui.formatTime
import com.dualamp.ui.plural
import com.dualamp.settings.Prefs
import com.dualamp.viz.ART_PARAMS
import com.dualamp.viz.ART_STYLES
import com.dualamp.viz.AlbumArtViz
import com.dualamp.viz.PresetManager
import com.dualamp.viz.VizControl
import com.dualamp.viz.VizFolders
import com.dualamp.viz.VizStream

enum class Category(val label: String) {
    PLAYLISTS("Playlists"), ARTISTS("Artists"), ALBUMS("Albums"), SINGLES("Singles"), SONGS("Songs"),
    GENRES("Genres"), YEARS("Years"), FOLDERS("Folders"), QUEUE("Queue"),
}

/** Order here is the order the view button cycles through. */
/** The ways to show a page: list (track number / art, length on the right), grid of squares, Cover Flow. */
enum class ViewMode { LIST, GRID, COVERFLOW }

enum class Sort(val label: String) {
    NAME("Name"), ARTIST("Artist"), ALBUM("Album"), YEAR("Release year"), ADDED("Recently added"),
    DURATION("Length"), COUNT("Most songs"), ORDER("Track order"),
}

/** One screen in the browser's back stack. */
sealed interface Page {
    data class Root(val category: Category) : Page
    data class AlbumPage(val albumId: Long) : Page
    data class ArtistPage(val name: String) : Page
    /** kind: "all", "albums" or "singles". */
    data class ArtistReleases(val artist: String, val kind: String) : Page
    data class SongsOf(val title: String, val kind: String, val key: String) : Page
    data class GenrePage(val name: String) : Page
    data class YearPage(val year: Int) : Page
    data class PlaylistPage(val id: String) : Page
    data class FolderPage(val path: String) : Page
    data class Search(val query: String) : Page
    data object Settings : Page
    data object Presets : Page
    data object MusicFolders : Page
    /** Settings > Multiple artists: the separators ([keep] false) or the names never split ([keep] true). */
    data class ArtistSplitList(val keep: Boolean) : Page
    data object Dsp : Page
    data object PcMusic : Page
    data object Controls : Page
    data class PresetCategory(val name: String) : Page
    data class VizFolderPage(val id: String) : Page

    /** Pages of the same kind share their view and sort choice. */
    val typeKey: String
        get() = when (this) {
            is Root -> "root_${category.name}"
            is AlbumPage -> "album"
            is ArtistPage -> "artist"
            is ArtistReleases -> "artist_releases"
            is SongsOf -> "songs_of"
            is GenrePage -> "genre"
            is YearPage -> "year"
            is PlaylistPage -> "playlist"
            is FolderPage -> "folder"
            is Search -> "search"
            Settings -> "settings"
            Presets -> "presets"
            MusicFolders -> "music_folders"
            is ArtistSplitList -> "artist_split"
            Dsp -> "dsp"
            PcMusic -> "pc_music"
            Controls -> "controls"
            is PresetCategory -> "preset_category"
            is VizFolderPage -> "viz_folder"
        }
}

sealed interface Art {
    data class Cover(val albumId: Long, val songId: Long) : Art
    data class Symbol(val icon: ImageVector, val seed: String) : Art
    data class TrackNo(val number: Int) : Art
    data object None : Art
}

/** HEADER: a section title (Settings); the gamepad cursor skips it. */
enum class Kind { SONG, CONTAINER, ACTION, SETTING, HEADER }

data class Entry(
    val key: String,
    val title: String,
    val subtitle: String,
    val art: Art,
    val kind: Kind,
    val round: Boolean = false,
    val trailing: String? = null,
    val song: Song? = null,
    val target: Page? = null,
    val action: (() -> Unit)? = null,
    /** Songs this entry stands for, used by Play / Queue / Add to playlist. */
    val songs: () -> List<Song> = { listOfNotNull(song) },
    val playlist: Playlist? = null,
    val preset: PresetManager.Preset? = null,
    val vizFolder: VizFolders.VizFolder? = null,
    /** Drawn as a slider row; left/right on the gamepad nudge it. */
    val slider: SliderSpec? = null,
    /** When set, the row opens a drop-down list of these choices; the current one is [trailing]. */
    val choices: List<String>? = null,
    val onChoose: ((String) -> Unit)? = null,
    /** A library folder on the Music folders page (its menu offers Remove). */
    val musicFolder: String? = null,
    /** Position in the play queue, for entries on the Queue page. */
    val queueIndex: Int? = null,
    /** Small icon before the title saying what the entry is (artist / album), used in search results. */
    val badge: ImageVector? = null,
    /** A full-width row even in the grid (e.g. the artist page's All songs / Albums / Singles, so their names show). */
    val wide: Boolean = false,
) {
    /** Actions and settings are drawn as full-width rows even in grid view. */
    val fullWidth: Boolean get() = wide || kind == Kind.ACTION || kind == Kind.SETTING || kind == Kind.HEADER
}

/** A setting shown as a slider. With [minLabel], the leftmost position reads as that word (e.g. "Never") instead of a number. */
/** [label]: how the value reads (default "<value> <unit>"). */
class SliderSpec(val setting: com.dualamp.settings.Prefs.Setting<Int>, val min: Int, val max: Int, val step: Int, val unit: String, val minLabel: String? = null,
    val label: ((Int) -> String)? = null)

data class Hero(val title: String, val subtitle: String, val detail: String, val art: Art, val round: Boolean = false)

data class PageData(
    val title: String,
    val entries: List<Entry>,
    /** Songs queued when one of this page's songs is picked. */
    val playable: List<Song> = emptyList(),
    val hero: Hero? = null,
    val sorts: List<Sort> = emptyList(),
    val defaultSort: Sort? = null,
    val defaultView: ViewMode = ViewMode.LIST,
)

/** Callbacks the settings page needs from the hosting screen. */
class UiActions(
    val importPresets: () -> Unit,
    val relaunch: () -> Unit,
    val rescan: () -> Unit,
    val deletePresets: () -> Unit,
    val newPlaylist: () -> Unit,
    /** Asks for a name, then creates a visualizer folder (holding [preset], if given). */
    val newVizFolder: (PresetManager.Preset?) -> Unit,
    val sleepKeysEnabled: () -> Boolean,
    val openAccessibility: () -> Unit,
    /** Asks "are you sure?" before clearing the queue. */
    val clearQueue: () -> Unit,
    val addMusicFolder: () -> Unit,
    /** PC music: search the Wi-Fi network for DualAmp Server / type an address / forget the server. */
    val findServer: () -> Unit,
    val enterServer: () -> Unit,
    val forgetServer: () -> Unit,
    /** Connect again to the PC used last time. */
    val connectLast: () -> Unit,
    val disconnectServer: () -> Unit,
    /** About box: name, version, device and credits. */
    val showAbout: () -> Unit,
    /** Help guide: what DualAmp does, how to use it, and the controls. */
    val showHelp: () -> Unit,
    /** Asks for a line of text (title, starting text, button), then calls back with it (blank: removed). */
    val askText: (String, String, String, (String) -> Unit) -> Unit,
)

// ---------------------------------------------------------------------------------------------
// Entry builders

fun songEntry(s: Song, trackNo: Boolean = false, showAlbum: Boolean = true) = Entry(
    key = "song:${s.id}",
    title = s.title,
    subtitle = if (showAlbum) "${s.artist} · ${s.album}" else s.artist,
    art = if (trackNo) Art.TrackNo(s.track) else Art.Cover(s.albumId, s.id),
    kind = Kind.SONG,
    trailing = formatTime(s.durationMs),
    song = s,
)

fun albumEntry(a: Album) = Entry(
    key = "album:${a.id}",
    title = a.title,
    subtitle = listOfNotNull(a.artist, a.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
    art = Art.Cover(a.id, a.coverSongId),
    kind = Kind.CONTAINER,
    target = Page.AlbumPage(a.id),
    songs = { a.songs },
)

fun artistEntry(a: Artist) = Entry(
    key = "artist:${a.name}",
    title = a.name,
    // Only featured on others' songs (Settings > Multiple artists): say so, and show one of those covers.
    subtitle = if (a.albums.isEmpty() && a.appearsOn.isNotEmpty()) "Featured on ${plural(a.songs.size, "song")}"
        else "${plural(a.albums.size, "album")} · ${plural(a.songs.size, "song")}",
    art = (a.albums.firstOrNull() ?: a.appearsOn.firstOrNull())?.let { Art.Cover(it.id, it.coverSongId) } ?: Art.Symbol(Icons.Rounded.Person, a.name),
    kind = Kind.CONTAINER,
    round = true,
    target = Page.ArtistPage(a.name),
    songs = { a.songs },
)

fun genreEntry(g: Genre) = Entry(
    key = "genre:${g.name}",
    title = g.name,
    subtitle = "${plural(g.albums.size, "album")} · ${plural(g.songs.size, "song")}",
    art = Art.Symbol(Icons.Rounded.Category, g.name),
    kind = Kind.CONTAINER,
    target = Page.GenrePage(g.name),
    songs = { g.songs },
)

fun yearEntry(y: YearGroup) = Entry(
    key = "year:${y.year}",
    title = y.year.toString(),
    subtitle = "${plural(y.albums.size, "album")} · ${plural(y.songs.size, "song")}",
    art = y.albums.firstOrNull()?.let { Art.Cover(it.id, it.coverSongId) } ?: Art.Symbol(Icons.Rounded.CalendarMonth, "${y.year}"),
    kind = Kind.CONTAINER,
    target = Page.YearPage(y.year),
    songs = { y.songs },
)

fun folderEntry(f: Folder) = Entry(
    key = "folder:${f.path}",
    title = f.name,
    subtitle = listOfNotNull(
        f.folders.size.takeIf { it > 0 }?.let { plural(it, "folder") },
        f.songs.size.takeIf { it > 0 }?.let { plural(it, "song") },
    ).joinToString(" · "),
    art = Art.Symbol(Icons.Rounded.Folder, f.path),
    kind = Kind.CONTAINER,
    target = Page.FolderPage(f.path),
    songs = { f.allSongs },
)

fun playlistEntry(p: Playlist, lib: Library): Entry {
    val songs = p.songIds.mapNotNull { lib.songById[it] }
    val first = songs.firstOrNull()
    return Entry(
        key = "playlist:${p.id}",
        title = p.name,
        subtitle = (if (p.readOnly) "Device playlist" else "Playlist") + " · " + plural(songs.size, "song"),
        art = when {
            p.id == PlaylistStore.LIKED_ID -> Art.Symbol(Icons.Rounded.Favorite, "liked-songs")
            first != null -> Art.Cover(first.albumId, first.id)
            else -> Art.Symbol(Icons.Rounded.QueueMusic, p.id)
        },
        kind = Kind.CONTAINER,
        target = Page.PlaylistPage(p.id),
        songs = { p.songIds.mapNotNull { lib.songById[it] } },
        playlist = p,
    )
}

private fun actionEntry(key: String, title: String, icon: ImageVector, subtitle: String = "", action: () -> Unit) =
    Entry(key, title, subtitle, Art.Symbol(icon, key), Kind.ACTION, action = action)

private fun playActions(songs: List<Song>, shuffleOnly: Boolean = false, collection: Boolean = false): List<Entry> {
    if (songs.isEmpty()) return emptyList()
    val shuffle = actionEntry("act:shuffle", "Shuffle", Icons.Rounded.Shuffle, plural(songs.size, "song")) {
        PlayerConnection.playSongs(songs, shuffle = true, fromCollection = collection)
    }
    if (shuffleOnly) return listOf(shuffle)
    return listOf(actionEntry("act:play", "Play", Icons.Rounded.PlayArrow) { PlayerConnection.playSongs(songs, fromCollection = collection) }, shuffle)
}

// ---------------------------------------------------------------------------------------------
// Sorting

private fun <T> List<T>.sortedBy(desc: Boolean, cmp: Comparator<T>) = sortedWith(if (desc) cmp.reversed() else cmp)

private fun sortSongs(list: List<Song>, sort: Sort, desc: Boolean): List<Song> = when (sort) {
    Sort.NAME -> list.sortedBy(desc, compareBy { it.title.lowercase() })
    Sort.ARTIST -> list.sortedBy(desc, compareBy({ sortKey(it.artist) }, { it.album.lowercase() }, { it.disc }, { it.track }))
    Sort.ALBUM -> list.sortedBy(desc, compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
    Sort.YEAR -> list.sortedBy(desc, compareBy({ it.year }, { it.album.lowercase() }, { it.disc }, { it.track }))
    Sort.ADDED -> list.sortedBy(!desc, compareBy { it.dateAdded })
    Sort.DURATION -> list.sortedBy(desc, compareBy { it.durationMs })
    else -> if (desc) list.reversed() else list
}

private fun sortAlbums(list: List<Album>, sort: Sort, desc: Boolean): List<Album> = when (sort) {
    Sort.NAME -> list.sortedBy(desc, compareBy { it.title.lowercase() })
    Sort.ARTIST -> list.sortedBy(desc, compareBy({ sortKey(it.artist) }, { it.year }))
    Sort.YEAR -> list.sortedBy(desc, compareBy({ it.year }, { it.title.lowercase() }))
    Sort.ADDED -> list.sortedBy(!desc, compareBy { a -> a.songs.maxOf { it.dateAdded } })
    Sort.COUNT -> list.sortedBy(!desc, compareBy { it.songs.size })
    else -> list
}

// ---------------------------------------------------------------------------------------------
// Pages

fun buildPage(
    page: Page,
    lib: Library,
    playlists: List<Playlist>,
    player: PlayerState,
    sort: Sort?,
    desc: Boolean,
    actions: UiActions,
): PageData = when (page) {
    is Page.Root -> buildRoot(page.category, lib, playlists, player, sort, desc, actions)

    is Page.AlbumPage -> {
        val a = lib.albumById[page.albumId]
        if (a == null) PageData("Album", emptyList()) else {
            val multiDisc = a.songs.any { it.disc > 1 }
            PageData(
                title = a.title,
                hero = Hero(a.title, a.artist,
                    listOfNotNull(a.year.takeIf { it > 0 }?.toString(), a.genre.ifBlank { null },
                        plural(a.songs.size, "song"), formatDurationLong(a.durationMs)).joinToString(" · "),
                    Art.Cover(a.id, a.coverSongId)),
                entries = playActions(a.songs, collection = true) + a.songs.map {
                    songEntry(it, trackNo = true, showAlbum = false).let { e ->
                        if (multiDisc) e.copy(subtitle = "Disc ${it.disc} · ${it.artist}") else e
                    }
                },
                playable = a.songs,
            )
        }
    }

    is Page.ArtistPage -> {
        val a = lib.artist(page.name)
        if (a == null) PageData(page.name, emptyList()) else {
            val (albums, singles) = artistReleases(a)
            val heroArt = (a.albums.firstOrNull() ?: a.appearsOn.firstOrNull())?.let { Art.Cover(it.id, it.coverSongId) } ?: Art.Symbol(Icons.Rounded.Person, a.name)
            fun group(kind: String, title: String, list: List<Album>) =
                Entry("rel:$kind:${a.name}", title, plural(list.size, "release"), Art.Symbol(Icons.Rounded.Album, "$kind${a.name}"),
                    Kind.CONTAINER, target = Page.ArtistReleases(a.name, kind), songs = { list.flatMap { it.songs } }, wide = true)
            PageData(
                title = a.name,
                hero = Hero(a.name, "Artist", "${plural(albums.size, "album")} · ${plural(singles.size, "single")} · ${plural(a.songs.size, "song")}",
                    heroArt, round = true),
                entries = playActions(a.songs, shuffleOnly = true) +
                    Entry("all:${a.name}", "All songs", plural(a.songs.size, "song"), Art.Symbol(Icons.Rounded.LibraryMusic, a.name),
                        Kind.CONTAINER, target = Page.SongsOf(a.name, "artist", a.name), songs = { a.songs }, wide = true) +
                    listOfNotNull(
                        group("all", "All releases", albums + singles).takeIf { singles.isNotEmpty() && albums.isNotEmpty() },
                        group("albums", "Albums", albums).takeIf { albums.isNotEmpty() },
                        group("singles", "Singles", singles).takeIf { singles.isNotEmpty() },
                        // Other artists' releases with songs this artist is on (Settings > Multiple artists).
                        group("appears", "Appears on", a.appearsOn).takeIf { a.appearsOn.isNotEmpty() },
                    ) +
                    // All releases inline: albums newest first, then singles newest first, then what they appear on.
                    albums.map { releaseEntry(it, "Album") } + singles.map { releaseEntry(it, "Single") } +
                    a.appearsOn.map { appearsEntry(it) },
                defaultView = ViewMode.GRID,
            )
        }
    }

    is Page.ArtistReleases -> {
        val a = lib.artist(page.artist)
        if (a == null) PageData(page.artist, emptyList()) else {
            val (albums, singles) = artistReleases(a)
            val (title, list) = when (page.kind) {
                "albums" -> "Albums" to albums.map { releaseEntry(it, "Album") }
                "singles" -> "Singles" to singles.map { releaseEntry(it, "Single") }
                "appears" -> "Appears on" to a.appearsOn.map { appearsEntry(it) }
                else -> "All releases" to (albums.map { releaseEntry(it, "Album") } + singles.map { releaseEntry(it, "Single") })
            }
            PageData(title = "${a.name} · $title", entries = list, defaultView = ViewMode.GRID)
        }
    }

    is Page.SongsOf -> {
        val songs = when (page.kind) {
            "artist" -> lib.artist(page.key)?.songs
            "genre" -> lib.genreByName[page.key]?.songs
            "year" -> lib.yearByValue[page.key.toIntOrNull()]?.songs
            else -> null
        }.orEmpty()
        val sorted = sortSongs(songs, sort ?: Sort.ALBUM, desc)
        PageData(
            title = page.title,
            hero = Hero(page.title, "All songs", plural(songs.size, "song"), Art.Symbol(Icons.Rounded.LibraryMusic, page.title)),
            entries = playActions(sorted) + sorted.map { songEntry(it) },
            playable = sorted,
            sorts = listOf(Sort.ALBUM, Sort.NAME, Sort.ARTIST, Sort.YEAR, Sort.ADDED, Sort.DURATION),
            defaultSort = Sort.ALBUM,
        )
    }

    is Page.GenrePage -> {
        val g = lib.genreByName[page.name]
        if (g == null) PageData(page.name, emptyList()) else PageData(
            title = g.name,
            hero = Hero(g.name, "Genre", "${plural(g.albums.size, "album")} · ${plural(g.songs.size, "song")}", Art.Symbol(Icons.Rounded.Category, g.name)),
            entries = playActions(g.songs, shuffleOnly = true) +
                Entry("all:${g.name}", "All songs", plural(g.songs.size, "song"), Art.Symbol(Icons.Rounded.LibraryMusic, g.name),
                    Kind.CONTAINER, target = Page.SongsOf(g.name, "genre", g.name), songs = { g.songs }) +
                sortAlbums(g.albums, sort ?: Sort.ARTIST, desc).map(::albumEntry),
            sorts = listOf(Sort.ARTIST, Sort.NAME, Sort.YEAR, Sort.ADDED),
            defaultSort = Sort.ARTIST,
            defaultView = ViewMode.GRID,
        )
    }

    is Page.YearPage -> {
        val y = lib.yearByValue[page.year]
        if (y == null) PageData(page.year.toString(), emptyList()) else PageData(
            title = y.year.toString(),
            hero = Hero(y.year.toString(), "Release year", "${plural(y.albums.size, "album")} · ${plural(y.songs.size, "song")}", Art.Symbol(Icons.Rounded.CalendarMonth, "${y.year}")),
            entries = playActions(y.songs, shuffleOnly = true) +
                Entry("all:${y.year}", "All songs", plural(y.songs.size, "song"), Art.Symbol(Icons.Rounded.LibraryMusic, "${y.year}"),
                    Kind.CONTAINER, target = Page.SongsOf(y.year.toString(), "year", y.year.toString()), songs = { y.songs }) +
                sortAlbums(y.albums, sort ?: Sort.ARTIST, desc).map(::albumEntry),
            sorts = listOf(Sort.ARTIST, Sort.NAME, Sort.ADDED),
            defaultSort = Sort.ARTIST,
            defaultView = ViewMode.GRID,
        )
    }

    is Page.PlaylistPage -> {
        val p = (playlists + lib.devicePlaylists).firstOrNull { it.id == page.id }
        if (p == null) PageData("Playlist", emptyList()) else {
            val songs = sortSongs(p.songIds.mapNotNull { lib.songById[it] }, sort ?: Sort.ORDER, desc)
            val first = songs.firstOrNull()
            PageData(
                title = p.name,
                hero = Hero(p.name, if (p.readOnly) "Device playlist" else "Playlist",
                    "${plural(songs.size, "song")} · ${formatDurationLong(songs.sumOf { it.durationMs })}",
                    if (p.id == PlaylistStore.LIKED_ID) Art.Symbol(Icons.Rounded.Favorite, "liked-songs")
                    else first?.let { Art.Cover(it.albumId, it.id) } ?: Art.Symbol(Icons.Rounded.QueueMusic, p.id)),
                entries = playActions(songs, collection = true) + songs.map { songEntry(it) },
                playable = songs,
                sorts = listOf(Sort.ORDER, Sort.NAME, Sort.ARTIST, Sort.ALBUM, Sort.YEAR, Sort.DURATION),
                defaultSort = Sort.ORDER,
            )
        }
    }

    is Page.FolderPage -> {
        val f = (if (page.path.isEmpty()) lib.rootFolder else lib.findFolder(page.path)) ?: lib.rootFolder
        val songs = sortSongs(f.songs, sort ?: Sort.ORDER, desc)
        PageData(
            title = f.name,
            entries = playActions(f.allSongs, shuffleOnly = f.songs.isEmpty()) + f.folders.map(::folderEntry) + songs.map { songEntry(it) },
            playable = songs,
            sorts = listOf(Sort.ORDER, Sort.NAME, Sort.ARTIST, Sort.ADDED),
            defaultSort = Sort.ORDER,
        )
    }

    is Page.Search -> buildSearch(page.query, lib)

    Page.Settings -> buildSettings(actions)

    Page.Dsp -> buildDsp()

    Page.PcMusic -> {
        // DualAmp only talks to the PC after you connect here, once per launch.
        val url = com.dualamp.settings.Prefs.serverUrl.value
        val active = com.dualamp.library.RemoteLibrary.active.value
        val remote = LibraryRepository.pcLibrary.value?.songs?.size ?: 0
        PageData(
            title = "PC music",
            entries = listOfNotNull(
                if (active) Entry("pc:status", "${pcLinkLabel()}: $url", "$remote songs · see the PC tab",
                    Art.Symbol(Icons.Rounded.Computer, "pc"), Kind.SETTING)
                else Entry("pc:status", "Not connected",
                    if (url.isBlank()) "Run DualAmp Server.exe on your PC and choose your music in its window, then find it below"
                    else "Connect each time you want to play your PC's music",
                    Art.Symbol(Icons.Rounded.Computer, "pc"), Kind.SETTING),
                if (!active && url.isNotBlank()) actionEntry("act:connectpc", "Connect to ${url.removePrefix("http://")}",
                    Icons.Rounded.Computer, "The PC you used last time", action = actions.connectLast) else null,
                actionEntry("act:findpc", "Find PC on Wi-Fi", Icons.Rounded.Search, "Looks for DualAmp Server on this network", action = actions.findServer),
                actionEntry("act:enterpc", "Enter PC address", Icons.Rounded.Edit, "e.g. 192.168.1.20", action = actions.enterServer),
                if (active) actionEntry("act:refreshpc", "Refresh PC library", Icons.Rounded.Refresh, action = { LibraryRepository.refresh() }) else null,
                if (active) actionEntry("act:disconnectpc", "Disconnect", Icons.Rounded.Close, "PC songs leave until you connect again", action = actions.disconnectServer) else null,
                if (url.isNotBlank()) actionEntry("act:forgetpc", "Forget this PC", Icons.Rounded.Close, "Removes its address from DualAmp", action = actions.forgetServer) else null,
            ),
        )
    }

    Page.Controls -> PageData(
        title = "Controls",
        entries = ControlsHelp.sections.flatMap { (section, rows) ->
            rows.map { (control, what) ->
                Entry("ctl:$section:$control", control, what, Art.Symbol(Icons.Rounded.SportsEsports, section), Kind.SETTING, trailing = section.substringBefore(" ("))
            }
        },
    )

    Page.Presets -> buildVisualizers(actions)

    is Page.ArtistSplitList -> {
        val p = com.dualamp.settings.Prefs
        val setting = if (page.keep) p.artistKeep else p.artistSeparators
        val defaults = if (page.keep) com.dualamp.library.ArtistNames.DEFAULT_KEEP else com.dualamp.library.ArtistNames.DEFAULT_SEPARATORS
        val items = setting.value.split('\n').filter { it.isNotEmpty() }
        fun save(list: List<String>) { setting.value = list.joinToString("\n"); LibraryRepository.refresh() }
        // Spaces matter in separators (" x " is not "x"), so they show as ␣.
        fun shown(s: String) = if (page.keep) s else s.replace(' ', '␣')
        val noun = if (page.keep) "name" else "separator"
        PageData(
            title = if (page.keep) "Never split" else "Artist separators",
            entries = listOf(
                actionEntry("act:addsplit", "Add a $noun", Icons.Rounded.Add,
                    if (page.keep) "An artist whose name has a separator in it, e.g. Tyler, The Creator"
                    else "Text between two artists' names, spaces included, e.g. \" & \" or \", \"") {
                    actions.askText("Add a $noun", "", "Add") { v -> if (v.isNotEmpty() && v !in items) save(items + v) }
                },
                actionEntry("act:resetsplit", "Reset to the defaults", Icons.Rounded.Refresh, plural(defaults.size, noun)) { save(defaults) },
            ) + items.mapIndexed { i, item ->
                Entry("split:$i:$item", shown(item), "A: edit (clear it to remove)", Art.Symbol(Icons.Rounded.Edit, item), Kind.ACTION,
                    action = { actions.askText("Edit $noun", item, "Save") { v -> save(if (v.isEmpty()) items - item else items.map { if (it == item) v else it }) } })
            },
        )
    }

    Page.MusicFolders -> PageData(
        title = "Music folders",
        entries = listOf(
            actionEntry("act:addfolder", "Add music folder", Icons.Rounded.LibraryAdd, "Pick a folder that holds your music", action = actions.addMusicFolder),
        ) + LibraryRepository.musicFolders.map { path ->
            Entry("musicfolder:$path", path.substringAfterLast('/').ifBlank { path }, path, Art.Symbol(Icons.Rounded.Folder, path),
                Kind.ACTION, trailing = "Y: remove", musicFolder = path, action = {})
        } + Entry("set:m3u", "Show playlist files (.m3u)", "Playlists saved as files inside your music folders",
            Art.Symbol(Icons.Rounded.QueueMusic, "m3u"), Kind.SETTING,
            trailing = if (com.dualamp.settings.Prefs.showPlaylistFiles.value) "On" else "Off",
            action = { com.dualamp.settings.Prefs.showPlaylistFiles.value = !com.dualamp.settings.Prefs.showPlaylistFiles.value; LibraryRepository.refresh() }),
    )

    is Page.PresetCategory -> {
        val list = PresetManager.presets.value.filter { it.category == page.name }
        val isActive = VizFolders.activeType == page.name
        PageData(
            title = page.name,
            entries = listOf(
                actionEntry("act:usetype", if (isActive) "Stop using this type" else "Use only this type", Icons.Rounded.Shuffle,
                    if (isActive) "Visualizer cycles through this type now" else "Cycle through just these presets") {
                    if (isActive) VizFolders.setActive(null) else VizFolders.setActiveType(page.name)
                },
            ) + list.map { presetEntry(it) },
        )
    }

    is Page.VizFolderPage -> {
        val folder = VizFolders.folders.value.firstOrNull { it.id == page.id }
        if (folder == null) PageData("Visualizer folder", emptyList()) else {
            val byPath = PresetManager.presets.value.associateBy { it.path }
            val isActive = VizFolders.active?.id == folder.id
            PageData(
                title = folder.name,
                hero = Hero(folder.name, "Visualizer folder", plural(folder.presetPaths.size, "preset"), Art.Symbol(Icons.Rounded.AutoAwesome, folder.id)),
                entries = listOf(
                    actionEntry("act:usefolder", if (isActive) "Stop using this folder" else "Use only this folder",
                        Icons.Rounded.Shuffle, if (isActive) "Visualizer cycles through this folder now" else "Cycle through just these presets") {
                        VizFolders.setActive(if (isActive) null else folder.id)
                    },
                ) + folder.presetPaths.mapNotNull { byPath[it] }.map { presetEntry(it) },
            )
        }
    }
}

fun presetEntry(p: PresetManager.Preset): Entry {
    val playing = p.path == VizControl.currentPreset.value?.path
    return Entry("preset:${p.path}", p.name, p.category, Art.Symbol(Icons.Rounded.AutoAwesome, p.category),
        Kind.ACTION, trailing = if (playing) "Showing" else null, preset = p, action = { VizControl.loadPreset(p) })
}

/** Visualizers home: the preset showing now, your folders, then every preset category. */
private fun buildVisualizers(actions: UiActions): PageData {
    val presets = PresetManager.presets.value
    val active = VizFolders.active
    val current = VizControl.currentPreset.value
    val entries = ArrayList<Entry>()
    current?.takeUnless { it.isAlbumArt }?.let {
        entries += presetEntry(it).copy(key = "current", title = "Now showing: ${it.name}", subtitle = "Press Y or long-press to add it to a folder")
    }
    // Album art: the visualizer built from the cover of the song playing, instead of the presets.
    val albumArt = AlbumArtViz.Source.current() == AlbumArtViz.Source.ALBUM_ART
    entries += actionEntry("act:albumart", "Album art", Icons.Rounded.Album,
        if (albumArt) "Active · built from the cover of the song playing" else "Build the visualizer from the cover of the song playing") {
        Prefs.vizSource.value = (if (albumArt) AlbumArtViz.Source.PRESETS else AlbumArtViz.Source.ALBUM_ART).name
    }
    entries += actionEntry("act:allpresets", "Use all presets", Icons.Rounded.AutoAwesome,
        if (!albumArt && active == null && VizFolders.activeType == null) "Active · ${plural(presets.size, "preset")}" else plural(presets.size, "preset")) {
        VizFolders.setActive(null)
        if (albumArt) Prefs.vizSource.value = AlbumArtViz.Source.PRESETS.name
    }
    entries += actionEntry("act:newvizfolder", "New visualizer folder", Icons.Rounded.Add) { actions.newVizFolder(null) }
    entries += VizFolders.folders.value.map { f ->
        Entry("vizfolder:${f.id}", f.name, plural(f.presetPaths.size, "preset"), Art.Symbol(Icons.Rounded.Folder, f.id),
            Kind.CONTAINER, trailing = if (f.id == active?.id) "Active" else null, target = Page.VizFolderPage(f.id), vizFolder = f)
    }
    entries += presets.groupBy { it.category }.map { (cat, list) ->
        Entry("presetcat:$cat", cat, plural(list.size, "preset"), Art.Symbol(Icons.Rounded.Category, cat),
            Kind.CONTAINER, trailing = if (VizFolders.activeType == cat) "Active" else null, target = Page.PresetCategory(cat))
    }
    return PageData(title = "Visualizers", entries = entries)
}

private fun buildRoot(
    c: Category, lib: Library, playlists: List<Playlist>, player: PlayerState,
    sort: Sort?, desc: Boolean, actions: UiActions,
): PageData = when (c) {
    Category.PLAYLISTS -> {
        val s = sort ?: Sort.NAME
        val all = playlists.filter { it.id != PlaylistStore.LIKED_ID } + lib.devicePlaylists
        val sorted = when (s) {
            Sort.ADDED -> all.sortedBy(!desc, compareBy { it.created })
            Sort.COUNT -> all.sortedBy(!desc, compareBy { it.songIds.size })
            else -> all.sortedBy(desc, compareBy { it.name.lowercase() })
        }
        PageData(
            title = "Your Library",
            entries = listOf(actionEntry("act:newpl", "New playlist", Icons.Rounded.Add, action = actions.newPlaylist)) +
                playlists.filter { it.id == PlaylistStore.LIKED_ID }.map { playlistEntry(it, lib) } +
                sorted.map { playlistEntry(it, lib) },
            sorts = listOf(Sort.NAME, Sort.ADDED, Sort.COUNT),
            defaultSort = Sort.NAME,
            defaultView = ViewMode.GRID,
        )
    }
    Category.ARTISTS -> {
        val list = when (sort ?: Sort.NAME) {
            Sort.COUNT -> lib.artists.sortedBy(!desc, compareBy { it.songs.size })
            else -> lib.artists.sortedBy(desc, compareBy { sortKey(it.name) })
        }
        PageData("Your Library", list.map(::artistEntry), sorts = listOf(Sort.NAME, Sort.COUNT), defaultSort = Sort.NAME, defaultView = ViewMode.GRID)
    }
    Category.ALBUMS -> PageData(
        "Your Library", sortAlbums(lib.fullAlbums, sort ?: Sort.NAME, desc).map(::albumEntry),
        sorts = listOf(Sort.NAME, Sort.ARTIST, Sort.YEAR, Sort.ADDED, Sort.COUNT), defaultSort = Sort.NAME, defaultView = ViewMode.GRID,
    )
    Category.SINGLES -> {
        val sorted = sortSongs(lib.singles, sort ?: Sort.NAME, desc)
        PageData(
            "Your Library", playActions(sorted, shuffleOnly = true) + sorted.map { songEntry(it) }, playable = sorted,
            sorts = listOf(Sort.NAME, Sort.ARTIST, Sort.YEAR, Sort.ADDED, Sort.DURATION), defaultSort = Sort.NAME, defaultView = ViewMode.GRID,
        )
    }
    Category.SONGS -> {
        val sorted = sortSongs(lib.songs, sort ?: Sort.NAME, desc)
        PageData(
            "Your Library", playActions(sorted, shuffleOnly = true) + sorted.map { songEntry(it) }, playable = sorted,
            sorts = listOf(Sort.NAME, Sort.ARTIST, Sort.ALBUM, Sort.YEAR, Sort.ADDED, Sort.DURATION), defaultSort = Sort.NAME,
        )
    }
    Category.GENRES -> {
        val list = when (sort ?: Sort.NAME) {
            Sort.COUNT -> lib.genres.sortedBy(!desc, compareBy { it.songs.size })
            else -> lib.genres.sortedBy(desc, compareBy { it.name.lowercase() })
        }
        PageData("Your Library", list.map(::genreEntry), sorts = listOf(Sort.NAME, Sort.COUNT), defaultSort = Sort.NAME)
    }
    Category.YEARS -> {
        val list = when (sort ?: Sort.YEAR) {
            Sort.COUNT -> lib.years.sortedBy(!desc, compareBy { it.songs.size })
            // Newest first unless reversed.
            else -> lib.years.sortedBy(!desc, compareBy { it.year })
        }
        PageData("Your Library", list.map(::yearEntry), sorts = listOf(Sort.YEAR, Sort.COUNT), defaultSort = Sort.YEAR)
    }
    Category.FOLDERS -> buildPage(Page.FolderPage(""), lib, playlists, player, sort, desc, actions).copy(title = "Your Library")
    Category.QUEUE -> {
        // The song playing now sits at the top as a header; the list is only what comes next,
        // in play order. Each entry keeps its real position in the player's queue.
        val current = player.songId?.let { lib.songById[it] }
        val songs = player.upNext.mapNotNull { i -> player.queue.getOrNull(i)?.let { lib.songById[it] }?.let { i to it } }
        val clear = if (songs.isEmpty()) emptyList() else listOf(
            actionEntry("act:clearqueue", "Clear queue", Icons.Rounded.ClearAll, "Keeps the current song playing · hold L3 also clears", action = actions.clearQueue)
        )
        PageData(
            "Your Library",
            clear + songs.map { (i, s) ->
                songEntry(s).copy(
                    key = "queue:$i:${s.id}",
                    queueIndex = i,
                    action = { PlayerConnection.playQueueIndex(i) },
                )
            },
            hero = current?.let { Hero(it.title, it.artist, "Now playing · ${plural(songs.size, "song")} up next", Art.Cover(it.albumId, it.id)) },
        )
    }
}

private fun buildSearch(query: String, lib: Library): PageData {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return PageData("Search", emptyList())
    /** How well [s] matches: 0 exact, 1 starts with it, 2 a word starts with it, 3 anywhere; null if not at all. */
    fun score(s: String): Int? {
        val t = s.lowercase()
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            t.contains(" $q") || t.contains("($q") -> 2
            t.contains(q) -> 3
            else -> null
        }
    }
    /** Best match first; a match on an earlier field (the name) beats one on a later field (artist / album). */
    fun <T> ranked(items: List<T>, limit: Int, vararg fields: (T) -> String): List<T> =
        items.mapNotNull { item ->
            fields.withIndex().mapNotNull { (i, f) -> score(f(item))?.plus(i * 4) }.minOrNull()?.let { item to it }
        }.sortedBy { it.second }.take(limit).map { it.first }

    // Order: artists, albums, then singles and songs whose title matches, then the rest
    // (singles and songs that only match by artist or album). Each group best match first.
    val artists = ranked(lib.artists, 20, { it.name })
        .map { artistEntry(it).let { e -> e.copy(subtitle = "Artist · ${e.subtitle}", badge = Icons.Rounded.Person) } }
    // Artist and album art are often the same picture, so the badge tells them apart.
    val (singleAlbums, fullAlbums) = lib.albums.partition { it.isSingle }
    val albums = ranked(fullAlbums, 30, { it.title }, { it.artist })
        .map { albumEntry(it).let { e -> e.copy(subtitle = "Album · ${e.subtitle}", badge = Icons.Rounded.Album) } }
    fun single(a: Album) = albumEntry(a).let { e -> e.copy(subtitle = "Single · ${e.subtitle}", badge = Icons.Rounded.MusicNote) }

    val singleIds = singleAlbums.map { it.id }.toSet()
    val titleSingles = ranked(singleAlbums, 30, { it.title })
    // A single's song is not listed again under songs.
    val albumSongs = lib.songs.filter { it.albumId !in singleIds }
    val titleSongs = ranked(albumSongs, 100, { it.title })
    val restSingles = ranked(singleAlbums.filter { score(it.title) == null }, 30, { it.artist })
    val restSongs = ranked(albumSongs.filter { score(it.title) == null }, 100, { it.album }, { it.artist })
    val songs = (titleSongs + restSongs).take(100)
    return PageData(
        "Search",
        artists + albums + titleSingles.map(::single) + titleSongs.map { songEntry(it) } +
            restSingles.map(::single) + restSongs.map { songEntry(it) },
        playable = songs,
    )
}

private fun buildSettings(actions: UiActions): PageData {
    fun setting(key: String, title: String, value: String, subtitle: String = "", action: () -> Unit) =
        Entry("set:$key", title, subtitle, Art.Symbol(Icons.Rounded.Settings, key), Kind.SETTING, trailing = value, action = action)
    fun <T> cycle(options: List<T>, current: T): T = options[(options.indexOf(current) + 1).mod(options.size)]
    fun onOff(b: Boolean) = if (b) "On" else "Off"
    fun header(title: String) = Entry("hdr:$title", title, "", Art.None, Kind.HEADER)
    val p = com.dualamp.settings.Prefs
    val presetCount = PresetManager.presets.value.size
    // The slider goes from "Never" (stored as 2, all the way left) to 30 s; older versions stored never as 0.
    if (p.overlayTimeoutSec.value !in 2..30) p.overlayTimeoutSec.value = p.overlayTimeoutSec.value.coerceIn(2, 30)
    return PageData(
        title = "Settings",
        entries = listOfNotNull(
            header("Music library"),
            Entry("set:folders", "Music folders", plural(LibraryRepository.musicFolders.size, "folder") + " in your library", Art.Symbol(Icons.Rounded.Folder, "folders"), Kind.CONTAINER, target = Page.MusicFolders),
            Entry("set:pc", "PC music", if (com.dualamp.settings.Prefs.serverUrl.value.isBlank()) "Play your PC / foobar2000 library over Wi-Fi" else "${pcLinkLabel()} · ${com.dualamp.settings.Prefs.serverUrl.value}", Art.Symbol(Icons.Rounded.Computer, "pcmusic"), Kind.CONTAINER, target = Page.PcMusic),
            setting("rescan", "Rescan music library", "", action = actions.rescan),

            header("Multiple artists"),
            setting("splitartists", "Songs with several artists", onOff(p.splitArtists.value),
                "List them under each artist (\"A, B\", \"A feat. B\"...). Works best with tidy, consistent artist tags") {
                p.splitArtists.value = !p.splitArtists.value; LibraryRepository.refresh()
            },
            if (!p.splitArtists.value) null else Entry("set:separators", "Artist separators",
                com.dualamp.library.ArtistNames.separators().joinToString("   ") { it.replace(' ', '␣') },
                Art.Symbol(Icons.Rounded.Edit, "separators"), Kind.CONTAINER, target = Page.ArtistSplitList(keep = false)),
            if (!p.splitArtists.value) null else Entry("set:keepnames", "Never split",
                plural(com.dualamp.library.ArtistNames.keep().size, "artist") + " whose names have a separator, e.g. Tyler, The Creator",
                Art.Symbol(Icons.Rounded.Edit, "keepnames"), Kind.CONTAINER, target = Page.ArtistSplitList(keep = true)),
            if (!p.splitArtists.value) null else setting("titlefeat", "Features in song titles", onOff(p.artistsFromTitles.value),
                "\"Song (feat. X)\" also lists the song under X") { p.artistsFromTitles.value = !p.artistsFromTitles.value; LibraryRepository.refresh() },

            header("Library view"),
            libraryViewSetting(true, if (p.sameViewEverywhere.value) "Library view" else "Library view: categories",
                if (p.sameViewEverywhere.value) "Playlists, Artists, Albums... and the pages inside them" else "Playlists, Artists, Albums..."),
            if (p.sameViewEverywhere.value) null else libraryViewSetting(false, "Library view: inside", "An artist, album, playlist, genre..."),
            colorChoice("launchroot", if (p.sameViewEverywhere.value) "View on launch" else "View on launch: categories",
                "The view DualAmp starts with. Last used: whatever you had", listOf("Last used", "Grid", "List", "Cover Flow"), p.launchViewRoot.value) { p.launchViewRoot.value = it },
            if (p.sameViewEverywhere.value) null else colorChoice("launchpage", "View on launch: inside", "An artist, album, playlist... when DualAmp starts",
                listOf("Last used", "Grid", "List", "Cover Flow"), p.launchViewPage.value) { p.launchViewPage.value = it },
            setting("sameview", "Same view everywhere", onOff(p.sameViewEverywhere.value), "Off: categories and the pages inside them each keep their own view") {
                p.sameViewEverywhere.value = !p.sameViewEverywhere.value
            },
            Entry("set:gridsize", "Grid size", "Squares per row in the grid view",
                Art.Symbol(Icons.Rounded.Settings, "gridsize"), Kind.SETTING,
                slider = SliderSpec(if (singleScreenLayout) p.gridColumnsSingle else p.gridColumns, 3, 6, 1, "per row")),
            colorChoice("glowcolor", "Cover Flow glow", "Behind the cover the gamepad is on (Auto: white, or black in the Light theme)", com.dualamp.ui.AppTheme.glows.keys, com.dualamp.ui.AppTheme.glowName, com.dualamp.ui.AppTheme::setGlow),
            colorChoice("glowshape", "Cover Flow glow shape", "Circle, tall oval, tall diamond, or two thin diamonds crossed",
                listOf("Circle", "Oval", "Diamond", "Cross / X"), p.coverGlowShape.value) { p.coverGlowShape.value = it },
            colorChoice("glowanim", "Cover Flow glow animation", "How the glow appears: grows from the centre, drops in from the top, or just appears",
                listOf("Expand", "Drop", "None"), p.coverGlowAnim.value) { p.coverGlowAnim.value = it },
            Entry("set:glowopacity", "Cover Flow glow strength", "0: no glow. Above 100 %: bigger and brighter",
                Art.Symbol(Icons.Rounded.Settings, "glowopacity"), Kind.SETTING,
                slider = SliderSpec(p.coverGlowOpacity, 0, 200, 10, "%")),

            header("Theme"),
            colorChoice("theme", "Theme", "The colors of the bottom screen", com.dualamp.ui.AppTheme.presets.keys,
                com.dualamp.ui.AppTheme.presetName, com.dualamp.ui.AppTheme::applyPreset),
            colorChoice("accent", "Accent", "Selected tabs and buttons; like and shuffle on the top screen", com.dualamp.ui.AppTheme.accents.keys, com.dualamp.ui.AppTheme.accentName, com.dualamp.ui.AppTheme::setAccent),

            header("Screens"),
            // Shows the screens in use now: opened on the bottom screen (e.g. as a companion app) that is the bottom one alone.
            com.dualamp.DualScreen.screensLabel(com.dualamp.DualScreen.running ?: p.screens.value).let { now ->
                colorChoice("screens", "Use both screens", "Both: now playing on top, library below. Top or Bottom: everything on that screen",
                    com.dualamp.DualScreen.screenOptions.map(com.dualamp.DualScreen::screensLabel), now) { label ->
                    val value = com.dualamp.DualScreen.screenOptions.first { com.dualamp.DualScreen.screensLabel(it) == label }
                    p.screens.value = value
                    if (label != now) actions.relaunch()
                }
            },
            setting("swap", "Swap screens", onOff(p.swapScreens.value), "If the screens open the wrong way round") {
                p.swapScreens.value = !p.swapScreens.value; actions.relaunch()
            },
            colorChoice("singlelayout", "Single-screen layout",
                "Side by side: the player and the library both on screen. Tabs: the library with tabs; the player bar opens the big player",
                listOf("Side by side", "Tabs"), if (p.singleLayout.value == "TABS") "Tabs" else "Side by side") {
                p.singleLayout.value = if (it == "Tabs") "TABS" else "SPLIT"
                if (it == "Tabs") com.dualamp.ui.library.BigPlayer.open.value = false
            },
            if (p.singleLayout.value != "TABS") null else colorChoice("tabsposition", "Tab bar (single-screen tabs)",
                "Where PC, Library, Equalizer, Search, Visualizers and Settings sit", listOf("Bottom", "Top"),
                if (p.tabsPosition.value == "TOP") "Top" else "Bottom") { p.tabsPosition.value = if (it == "Top") "TOP" else "BOTTOM" },
            if (p.singleLayout.value == "TABS") null else setting("singleswap", "Swap sides (single-screen)", onOff(p.singleSwap.value), "Library on the left in landscape, on top in portrait") {
                p.singleSwap.value = !p.singleSwap.value
            },
            setting("orientation", "Screen orientation", com.dualamp.Orientation.label(p.orientation.value), "Both screens. Auto follows how you hold the Thor") {
                p.orientation.value = cycle(com.dualamp.Orientation.options, p.orientation.value)
            },

            header("Now playing - top screen"),
            Entry("set:overlay", "Fade to visualizer after", "While a song is playing, the song info fades after this long (all the way left: never)",
                Art.Symbol(Icons.Rounded.Settings, "overlay"), Kind.SETTING,
                slider = SliderSpec(p.overlayTimeoutSec, 2, 30, 1, "s", minLabel = "Never")),
            Entry("set:backdrop", "Song info background", "Darkens the visualizer behind the song info",
                Art.Symbol(Icons.Rounded.Settings, "backdrop"), Kind.SETTING,
                slider = SliderSpec(p.infoBackdrop, 0, 100, 5, "%")),
            setting("presetname", "Show preset name", onOff(p.showPresetName.value)) { p.showPresetName.value = !p.showPresetName.value },

            header("Lyrics"),
            setting("lyrics", "Lyrics", onOff(p.lyricsOn.value), "On the player, over the visualizer (also the lyrics button on the player, or double-click the right stick, R3)") { p.lyricsOn.value = !p.lyricsOn.value },
            Entry("set:lyricsbg", "Lyrics background", "Darkens the visualizer behind the lyrics so they are easy to read",
                Art.Symbol(Icons.Rounded.Settings, "lyricsbg"), Kind.SETTING,
                slider = SliderSpec(p.lyricsBackdrop, 0, 100, 5, "%")),
            Entry("set:lyricsoffset", "Lyrics timing", "For timed lyrics: show each line sooner (right) or later (left)",
                Art.Symbol(Icons.Rounded.Settings, "lyricsoffset"), Kind.SETTING,
                slider = SliderSpec(p.lyricsOffsetMs, -3000, 3000, 100, "ms") { v ->
                    when { v == 0 -> "On time"; v > 0 -> "%.1f s sooner".format(v / 1000f); else -> "%.1f s later".format(-v / 1000f) }
                }),
            setting("lyricsonline", "Find lyrics online", onOff(p.lyricsOnline.value),
                "For songs without a .lrc file or lyrics in their tags: asks lrclib.net (sends the title and artist)") {
                p.lyricsOnline.value = !p.lyricsOnline.value; com.dualamp.library.LyricsRepository.retryMissing()
            },

            header("Visualizer"),
            setting("vizon", "Visualizer", onOff(p.vizEnabled.value), "Off shows the album art instead (also the eye button on the top screen)") { p.vizEnabled.value = !p.vizEnabled.value },
            setting("vizsource", "Visualizer shows", AlbumArtViz.Source.current().label,
                "Album art builds the visualizer from the cover of the song playing; it still moves with the music") {
                p.vizSource.value = cycle(AlbumArtViz.Source.entries.toList(), AlbumArtViz.Source.current()).name
            },
            Entry("set:presets", "Visualizers", "$presetCount presets · your visualizer folders", Art.Symbol(Icons.Rounded.AutoAwesome, "presets"),
                Kind.CONTAINER, target = Page.Presets),
            setting("vizmode", "Change visualizer", VizControl.Mode.current().label) {
                VizControl.setMode(cycle(VizControl.Mode.entries.toList(), VizControl.Mode.current()))
            },
            Entry("set:duration", "Seconds per visualizer", "Used when changing every few seconds",
                Art.Symbol(Icons.Rounded.Settings, "duration"), Kind.SETTING,
                slider = SliderSpec(p.presetDurationSec, 20, 120, 5, "s")),
            setting("shuffle", "Shuffle presets", onOff(p.shufflePresets.value), "Automatic changes pick at random; the arrows always go in order") { p.shufflePresets.value = !p.shufflePresets.value },
            setting("l2", "L2 button", if (p.l2Random.value) "Random visualizer" else "Next visualizer", "Within the visualizer folder or type you are using. With album art, L2 always goes to the next style") { p.l2Random.value = !p.l2Random.value },
            setting("vizstream", "Stream visualizer",
                if (!p.vizStream.value) "Off" else if (VizStream.viewers.value > 0) "${VizStream.viewers.value} watching" else "On",
                if (!p.vizStream.value) "Watch the visualizer on a TV, PC or phone on the same Wi-Fi, in its web browser"
                else "Open " + (VizStream.address() ?: "this device's address, port ${VizStream.PORT}") + " in a browser on the same Wi-Fi (double-click: full screen)") {
                p.vizStream.value = !p.vizStream.value; VizStream.apply()
            },
            setting("hardcut", "Switch presets on big beats", onOff(p.hardCuts.value)) { p.hardCuts.value = !p.hardCuts.value },
            Entry("set:reactivity", "Visualizer reactivity", "How strongly it reacts to the music. Higher: bigger jumps on beats; lower: calmer",
                Art.Symbol(Icons.Rounded.Settings, "reactivity"), Kind.SETTING,
                slider = SliderSpec(p.vizReactivity, 50, 300, 25, "%")),
            setting("quality", "Visualizer resolution", "${p.vizQuality.value}%", "Lower saves battery") {
                p.vizQuality.value = cycle(listOf(100, 75, 50), p.vizQuality.value)
            },
            setting("fps", "Visualizer frame rate", p.vizFpsCap.value.let { if (it == 0) "Unlimited" else "$it fps" }) {
                p.vizFpsCap.value = cycle(listOf(60, 30, 0), p.vizFpsCap.value)
            },
            setting("import", "Import preset folder", "", "Copy a folder of .milk files and textures", actions.importPresets),
            setting("deletepresets", "Remove imported presets", "", action = actions.deletePresets),
        ) + (if (AlbumArtViz.Source.current() == AlbumArtViz.Source.PRESETS) emptyList() else albumArtSettings()) + listOfNotNull(

            header("Help & controls"),
            Entry("set:help", "Help", "What DualAmp does and how to use it", Art.Symbol(Icons.Rounded.Info, "help"), Kind.SETTING, action = actions.showHelp),
            Entry("set:controls", "Controls", "What every button and gesture does", Art.Symbol(Icons.Rounded.SportsEsports, "controls"), Kind.CONTAINER, target = Page.Controls),
            setting("controlsAtLaunch", "Show help at launch", onOff(p.showControlsAtLaunch.value), "The help guide when DualAmp starts") { p.showControlsAtLaunch.value = !p.showControlsAtLaunch.value },
            setting("sleepkeys", "Shoulder buttons while asleep", if (actions.sleepKeysEnabled()) "On" else "Off", "L1/R1 skip, R2 play/pause with the screen off. Opens Accessibility settings") { actions.openAccessibility() },
            setting("lidbuttons", "Lid-closed buttons", onOff(p.lidButtons.value),
                "Lid shut with music: screen stays on (black, dimmest) so L1 / R1, R2 and holding L2 / R2 work. Uses a little more battery") { p.lidButtons.value = !p.lidButtons.value },

            header("About"),
            Entry("set:about", "DualAmp ${com.dualamp.BuildConfig.VERSION_NAME}", "Version, device and credits", Art.Symbol(Icons.Rounded.Info, "about"), Kind.SETTING, action = actions.showAbout),
        ),
    )
}

/** Equalizer & DSP: on/off, preset, 10 band sliders, then tone, balance, width and limiter. */
private fun buildDsp(): PageData {
    val p = com.dualamp.settings.Prefs
    val dsp = com.dualamp.audio.DspSettings
    fun toggle(key: String, title: String, subtitle: String, setting: com.dualamp.settings.Prefs.Setting<Boolean>) =
        Entry("dsp:$key", title, subtitle, Art.Symbol(Icons.Rounded.Equalizer, key), Kind.SETTING,
            trailing = if (setting.value) "On" else "Off", action = { setting.value = !setting.value })
    fun slider(key: String, title: String, subtitle: String, setting: com.dualamp.settings.Prefs.Setting<Int>, min: Int, max: Int, step: Int, unit: String) =
        Entry("dsp:$key", title, subtitle, Art.Symbol(Icons.Rounded.Equalizer, key), Kind.SETTING,
            slider = SliderSpec(setting, min, max, step, unit))
    return PageData(
        title = "Equalizer & DSP",
        entries = listOf(
            toggle("enabled", "DSP", "Equalizer and effects on the music", p.dspEnabled),
            Entry("dsp:preset", "Equalizer preset", "Tap to choose a preset", Art.Symbol(Icons.Rounded.Equalizer, "preset"),
                Kind.SETTING, trailing = dsp.currentPresetName(),
                choices = dsp.presets.map { it.first }, onChoose = { dsp.applyPreset(it) }),
        ) + dsp.bands.mapIndexed { i, hz ->
            slider("eq$i", dsp.bandLabel(hz), "", p.eqBands[i], -12, 12, 1, "dB")
        } + listOf(
            slider("preamp", "Preamp", "Overall level before the equalizer", p.dspPreamp, -12, 12, 1, "dB"),
            slider("bass", "Bass", "Low shelf at 100 Hz", p.dspBass, -12, 12, 1, "dB"),
            slider("treble", "Treble", "High shelf at 10 kHz", p.dspTreble, -12, 12, 1, "dB"),
            slider("balance", "Balance", "Left (-100) to right (100)", p.dspBalance, -100, 100, 10, ""),
            slider("width", "Stereo width", "0 = mono, 100 = normal, 200 = extra wide", p.dspWidth, 0, 200, 10, "%"),
            toggle("mono", "Mono", "Mix both channels together", p.dspMono),
            toggle("limiter", "Limiter", "Prevents distortion when bands are boosted", p.dspLimiter),
            Entry("dsp:reset", "Reset DSP", "Flat equalizer, all effects back to default", Art.Symbol(Icons.Rounded.Equalizer, "reset"),
                Kind.SETTING, action = { dsp.resetAll() }),
        ),
    )
}

/** Lets other screens (e.g. the top screen's menu) open a page in the bottom screen's library. */
object LibraryNav {
    private val _requests = kotlinx.coroutines.flow.MutableSharedFlow<Page>(extraBufferCapacity = 4)
    val requests: kotlinx.coroutines.flow.SharedFlow<Page> get() = _requests
    fun open(page: Page) { _requests.tryEmit(page) }
}

/** An artist's albums and singles ([Album.isSingle]), each newest first. */
private fun artistReleases(a: Artist): Pair<List<Album>, List<Album>> {
    val newestFirst = compareByDescending<Album> { it.year }.thenBy { it.title.lowercase() }
    val (singles, albums) = a.albums.partition { it.isSingle }
    return albums.sortedWith(newestFirst) to singles.sortedWith(newestFirst)
}

/** A release by other artists that [Artist.appearsOn]: its artist in the subtitle. */
private fun appearsEntry(a: Album) =
    albumEntry(a).copy(subtitle = listOfNotNull("Appears on", a.artist.takeIf { it.isNotBlank() }, a.year.takeIf { it > 0 }?.toString()).joinToString(" · "))

/** Album entry whose subtitle says what kind of release it is. */
private fun releaseEntry(a: Album, kind: String) =
    albumEntry(a).copy(subtitle = listOfNotNull(kind, a.year.takeIf { it > 0 }?.toString()).joinToString(" · "))

/** "Connected", "Looking for your PC…" or "Can't reach your PC", for the PC music rows. */
private fun pcLinkLabel() = if (!com.dualamp.library.RemoteLibrary.active.value) "Not connected" else when (com.dualamp.library.RemoteLibrary.connection.value) {
    com.dualamp.library.RemoteLibrary.Connection.CONNECTED -> "Connected"
    com.dualamp.library.RemoteLibrary.Connection.LOST -> "Can't reach your PC"
    else -> "Looking for your PC…"
}

/** Settings row: the library view (list, grid or Cover Flow) for the categories ([root]) or the pages inside them. */
private fun libraryViewSetting(root: Boolean, title: String, subtitle: String): Entry {
    val names = mapOf("GRID" to "Grid", "LIST" to "List", "COVERFLOW" to "Cover Flow")
    return Entry("set:view:$root", title, subtitle, Art.Symbol(Icons.Rounded.Settings, "view$root"), Kind.SETTING,
        trailing = names[com.dualamp.settings.LibraryView.get(root)] ?: "Grid",
        choices = names.values.toList(),
        onChoose = { c -> names.entries.firstOrNull { it.value == c }?.let { com.dualamp.settings.LibraryView.set(root, it.key) } })
}

/** Settings row with a drop-down list of named colors (or presets). */
/** Settings > Album art visualizer: the style, whether it changes, and a slider for each element. */
private fun albumArtSettings(): List<Entry> {
    val p = Prefs
    val style = ART_STYLES[p.artStyle.value.coerceIn(ART_STYLES.indices)]
    return listOf(
        Entry("hdr:albumart", "Album art visualizer", "", Art.None, Kind.HEADER),
        colorChoice("artstyle", "Album art style",
            "Cover: pulsing in the middle · Melt: fills the screen and melts · Kaleidoscope · Tunnel · Living photo: the picture itself moves with the music",
            ART_STYLES, style) {
            p.artStyle.value = ART_STYLES.indexOf(it)
        },
        Entry("set:artcycle", "Change the style", "The arrows, L2, the timer and song changes step through the styles",
            Art.Symbol(Icons.Rounded.Settings, "artcycle"), Kind.SETTING,
            trailing = if (p.artCycleStyles.value) "With the visualizer" else "Never",
            action = { p.artCycleStyles.value = !p.artCycleStyles.value }),
        Entry("set:artbands", "Frequency detail",
            "Every album art style. 16 bands: each part follows its own frequencies. (MilkDrop presets always use lows, mids and highs)",
            Art.Symbol(Icons.Rounded.Settings, "artbands"), Kind.SETTING,
            trailing = if (p.artFineBands.value) "16 bands" else "Lows, mids, highs",
            action = { p.artFineBands.value = !p.artFineBands.value }),
    ) + ART_PARAMS.mapIndexed { i, param ->
        Entry("set:art${param.id}", param.label, param.subtitle, Art.Symbol(Icons.Rounded.Settings, "art${param.id}"), Kind.SETTING,
            slider = SliderSpec(p.artParams[i], 0, 100, 5, "%"))
    } + Entry("set:artreset", "Reset album art", "Every album art slider back to its default",
        Art.Symbol(Icons.Rounded.Refresh, "artreset"), Kind.SETTING, action = { AlbumArtViz.resetTweaks() })
}

private fun colorChoice(key: String, title: String, subtitle: String, names: Collection<String>, current: String, set: (String) -> Unit) =
    Entry("set:$key", title, subtitle, Art.Symbol(Icons.Rounded.Settings, key), Kind.SETTING,
        trailing = current, choices = names.toList(), onChoose = set)
