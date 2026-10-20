package com.dualamp.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.dualamp.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Reads the music library from Android's MediaStore and groups it every way the browser needs. */
object LibraryRepository {
    private const val TAG = "LibraryRepository"
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    private val _library = MutableStateFlow<Library?>(null)
    /** Null until the first scan finishes. */
    val library: StateFlow<Library?> = _library.asStateFlow()

    private val _pcLibrary = MutableStateFlow<Library?>(null)
    /** Music from DualAmp Server on a PC, shown in its own tab; null when no PC is connected. */
    val pcLibrary: StateFlow<Library?> = _pcLibrary.asStateFlow()

    private val _permission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _permission.asStateFlow()

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun init(context: Context) {
        app = context.applicationContext
        app.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh(debounceMs = 1500)
            },
        )
        refresh()
        // Keeps an eye on the PC (DualAmp Server): the PC tab's spinner / checkmark and the "Can't reach your PC" pop-up.
        scope.launch {
            RemoteLibrary.watch({ if (RemoteLibrary.active.value) Prefs.serverUrl.value else "" }) { wasLost -> if (wasLost || _pcLibrary.value == null) refresh() }
        }
    }

    /** Library folders as absolute paths. */
    val musicFolders: List<String> get() = Prefs.musicFolders.value.split('\n').map { it.trimEnd('/') }.filter { it.isNotBlank() }

    fun addFolder(path: String) {
        if (path in musicFolders) return
        Prefs.musicFolders.value = (musicFolders + path).joinToString("\n")
        refresh()
    }

    fun removeFolder(path: String) {
        Prefs.musicFolders.value = (musicFolders - path).joinToString("\n")
        refresh()
    }

    /** Turns a folder picked in the system file picker into a file path. */
    fun pathFromTree(uri: android.net.Uri): String? {
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val rest = id.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        return if (rest.isEmpty()) base else "$base/$rest"
    }

    fun refresh(debounceMs: Long = 0) {
        val granted = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        _permission.value = granted
        if (!granted) return
        scanJob?.cancel()
        scanJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            try {
                _library.value = scan()
            } catch (e: Exception) {
                Log.e(TAG, "Library scan failed", e)
                if (_library.value == null) _library.value = Library.EMPTY
            }
        }
    }

    private suspend fun scan(): Library {
        // Only songs inside the folders the user picked count as their library.
        val roots = musicFolders
        val local = querySongs().filter { s -> roots.any { s.path.startsWith("$it/") } }
        // Songs shared by DualAmp Server on a PC go to their own "PC" tab, not this library.
        val server = Prefs.serverUrl.value
        val remote = if (server.isBlank() || !RemoteLibrary.active.value) emptyList() else RemoteLibrary.fetchSongs(app, server)
        Log.i(TAG, "Library: ${local.size} local, ${remote.size} on PC ($server)")
        remote.forEach { Artwork.registerRemote(it.albumId, it.remoteArtUrl) }
        val devicePlaylists = if (Prefs.showPlaylistFiles.value) queryDevicePlaylists(roots) else emptyList()
        // Each library can still look up the other's songs (queue, playlists, now playing).
        _pcLibrary.value = if (remote.isEmpty()) null else group(remote, emptyList(), lookup = local)
        return group(local, devicePlaylists, lookup = remote)
    }

    /** Groups songs into albums, artists, genres, years and folders. */
    private fun group(input: List<Song>, devicePlaylists: List<Playlist>, lookup: List<Song>): Library {
        val songs = input.sortedBy { it.title.lowercase() }
        val albums = songs.groupBy { it.albumId }.map { (id, list) ->
            val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() }))
            val first = sorted.first()
            Album(
                id = id,
                title = first.album,
                artist = first.primaryArtist,
                year = list.maxOf { it.year },
                genre = list.groupingBy { it.genre }.eachCount().maxByOrNull { it.value }?.key.orEmpty(),
                songs = sorted,
            )
        }.sortedBy { it.title.lowercase() }

        // Same artist whatever the capitalization; shown with its most common spelling. With several artists on a tag
        // (Settings > Multiple artists) an album goes under each album artist, and a song under everyone credited on it.
        val ownAlbums = LinkedHashMap<String, MutableList<Album>>()
        val credits = HashMap<String, MutableList<Song>>()
        val spellings = HashMap<String, MutableMap<String, Int>>()
        fun spelled(name: String): String {
            val key = artistKey(name)
            spellings.getOrPut(key) { HashMap() }.merge(name.trim(), 1, Int::plus)
            return key
        }
        for (al in albums) {
            for (name in ArtistNames.split(al.artist).ifEmpty { listOf(al.artist) }) ownAlbums.getOrPut(spelled(name)) { ArrayList() } += al
            for (s in al.songs) for (name in ArtistNames.credited(s)) credits.getOrPut(spelled(name)) { ArrayList() } += s
        }
        val albumOf = albums.associateBy { it.id }
        val artists = (ownAlbums.keys + credits.keys).distinct().map { key ->
            val name = spellings[key]!!.maxByOrNull { it.value }!!.key
            val own = ownAlbums[key].orEmpty()
            val ownIds = own.map { it.id }.toSet()
            val featured = credits[key].orEmpty().filter { it.albumId !in ownIds }
            val appearsOn = featured.map { it.albumId }.distinct().mapNotNull { albumOf[it] }
            // A one-artist album shows that artist's usual spelling; a shared one keeps its tag ("JAY-Z & Kanye West").
            fun named(al: Album) = if (ArtistNames.split(al.artist).size <= 1) al.copy(artist = name) else al
            Artist(name, own.sortedByDescending { it.year }.map(::named), own.flatMap { it.songs } + featured,
                appearsOn.sortedByDescending { it.year })
        }.sortedBy { sortKey(it.name) }
        val albumArtist = artists.flatMap { a -> a.albums.filter { ArtistNames.split(it.artist).size <= 1 }.map { it.id to a.name } }.toMap()
        val unifiedAlbums = albums.map { al -> albumArtist[al.id]?.let { al.copy(artist = it) } ?: al }

        val genres = songs.filter { it.genre.isNotBlank() }.groupBy { it.genre.trim().lowercase() }.map { (_, list) ->
            val name = list.groupingBy { it.genre.trim() }.eachCount().maxByOrNull { it.value }!!.key
            val ids = list.map { it.albumId }.toSet()
            Genre(name, unifiedAlbums.filter { it.id in ids }, list)
        }.sortedBy { it.name.lowercase() }

        val years = unifiedAlbums.filter { it.year > 0 }.groupBy { it.year }.map { (year, list) ->
            YearGroup(year, list.sortedBy { it.title.lowercase() }, list.flatMap { it.songs })
        }.sortedByDescending { it.year }

        return Library(songs, unifiedAlbums, artists, genres, years, buildFolders(songs), devicePlaylists, lookup)
    }

    private fun querySongs(): List<Song> {
        val cols = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.BITRATE,
        )
        val out = ArrayList<Song>()
        app.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            fun str(i: Int) = c.getString(i)?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty().trim()
            while (c.moveToNext()) {
                val path = str(10)
                val trackRaw = c.getInt(8)
                out += Song(
                    id = c.getLong(0),
                    title = str(1).ifBlank { path.substringAfterLast('/').substringBeforeLast('.') },
                    artist = str(2).ifBlank { "Unknown Artist" },
                    album = str(3).ifBlank { "Unknown Album" },
                    albumId = c.getLong(4),
                    albumArtist = str(5),
                    genre = str(6),
                    year = c.getInt(7),
                    track = trackRaw % 1000,
                    disc = (trackRaw / 1000).coerceAtLeast(1),
                    durationMs = c.getLong(9),
                    path = path,
                    mime = str(11),
                    dateAdded = c.getLong(12),
                    bitrate = c.getInt(13),
                )
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }

    private fun queryDevicePlaylists(roots: List<String>): List<Playlist> = try {
        @Suppress("DEPRECATION")
        val uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
        val lists = ArrayList<Playlist>()
        @Suppress("DEPRECATION")
        app.contentResolver.query(
            uri, arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME, MediaStore.Audio.Playlists.DATE_ADDED, MediaStore.Audio.Playlists.DATA),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val file = c.getString(3).orEmpty()
                if (roots.none { file.startsWith("$it/") }) continue
                val ids = ArrayList<Long>()
                @Suppress("DEPRECATION")
                app.contentResolver.query(
                    MediaStore.Audio.Playlists.Members.getContentUri("external", id),
                    arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID), null, null,
                    MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                )?.use { m -> while (m.moveToNext()) ids += m.getLong(0) }
                lists += Playlist("device:$id", c.getString(1) ?: "Playlist", ids, c.getLong(2) * 1000, readOnly = true)
            }
        }
        lists
    } catch (e: Exception) {
        Log.w(TAG, "Could not read device playlists", e)
        emptyList()
    }

    /** Builds a folder tree, with each storage volume as a top-level entry. */
    private fun buildFolders(songs: List<Song>): Folder {
        class Node(val path: String, val name: String) {
            val children = LinkedHashMap<String, Node>()
            val songs = ArrayList<Song>()
        }

        val root = Node("", "Storage")
        for (song in songs) {
            val dir = song.folder
            if (dir.isEmpty()) continue
            val (volumePath, volumeName) = volumeOf(dir)
            var node = root.children.getOrPut(volumePath) { Node(volumePath, volumeName) }
            val rest = dir.removePrefix(volumePath).trim('/')
            if (rest.isNotEmpty()) {
                var path = volumePath
                for (part in rest.split('/')) {
                    path = "$path/$part"
                    node = node.children.getOrPut(part) { Node(path, part) }
                }
            }
            node.songs += song
        }

        fun convert(n: Node): Folder = Folder(
            path = n.path,
            name = n.name,
            folders = n.children.values.map(::convert).sortedBy { it.name.lowercase() },
            songs = n.songs.sortedWith(compareBy({ it.disc }, { it.track }, { it.path.lowercase() })),
        )
        // With a single storage volume, start browsing inside it.
        return convert(root.children.values.singleOrNull() ?: root)
    }

    private fun volumeOf(dir: String): Pair<String, String> {
        val internal = "/storage/emulated/0"
        if (dir.startsWith(internal)) return internal to "Internal storage"
        val parts = dir.trim('/').split('/')
        if (parts.size >= 2 && parts[0] == "storage") return "/storage/${parts[1]}" to "SD card (${parts[1]})"
        return "/" + parts.first() to parts.first()
    }


    /** Sort key that ignores a leading "The ". */
    fun sortKey(name: String): String = name.lowercase().removePrefix("the ").trim()
}
