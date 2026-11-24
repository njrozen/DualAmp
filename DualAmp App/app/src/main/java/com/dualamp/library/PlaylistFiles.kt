package com.dualamp.library

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playlist files (.m3u, .m3u8, .pls, .wpl...) that Android found in the music folders (Settings > Music library > Playlist
 * files). Songs are added through Android's media store, which writes them into the file itself. The file belongs to
 * whoever made it, so Android asks first ("Allow DualAmp to modify...?", once per file).
 */
object PlaylistFiles {
    private const val TAG = "PlaylistFiles"

    fun isFile(p: Playlist) = p.id.startsWith("device:")

    private fun mediaId(p: Playlist) = p.id.removePrefix("device:").toLongOrNull()

    /** The storage the file is on ("external_primary", or an SD card's id): Android checks permission per volume. */
    private val volumes = HashMap<Long, String>()

    @Suppress("DEPRECATION")
    private fun volume(context: Context, id: Long): String = synchronized(volumes) { volumes[id] } ?: run {
        val v = runCatching {
            context.contentResolver.query(ContentUris.withAppendedId(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, id),
                arrayOf(MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: MediaStore.VOLUME_EXTERNAL
        synchronized(volumes) { volumes[id] = v }
        v
    }

    @Suppress("DEPRECATION")
    private fun uri(context: Context, p: Playlist): Uri? = mediaId(p)?.let {
        ContentUris.withAppendedId(MediaStore.Audio.Playlists.getContentUri(volume(context, it)), it)
    }

    /** True when DualAmp may already change [p]'s file (otherwise ask with [writeRequest]). */
    fun canWrite(context: Context, p: Playlist): Boolean {
        val u = uri(context, p) ?: return false
        return context.checkUriPermission(u, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    /** Android's "allow DualAmp to modify this playlist?" prompt. */
    fun writeRequest(context: Context, p: Playlist) = writeRequest(context, listOf(p))

    /** One prompt for several playlist files. */
    fun writeRequest(context: Context, list: List<Playlist>) = MediaStore.createWriteRequest(context.contentResolver, list.mapNotNull { uri(context, it) })

    /** Songs that can go in a playlist file: ones on this device (not streamed from a PC). */
    fun addable(songs: List<Song>) = songs.filter { !it.isRemote && it.id >= 0 }

    /**
     * Adds [songs] to the end of [p]'s file (ones already in it are left out). Returns how many were added, or a
     * failure. Then the library reloads, so the playlist shows them.
     */
    @Suppress("DEPRECATION")
    suspend fun add(context: Context, p: Playlist, songs: List<Song>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val id = mediaId(p) ?: error("Not a playlist file")
            val fresh = addable(songs).distinctBy { it.id }.filter { it.id !in p.songIds }
            if (fresh.isEmpty()) return@runCatching 0
            val path = filePath(context, id)
            val n = if (path != null && path.substringAfterLast('.').lowercase().startsWith("m3u")) appendM3u(context, p, path, fresh)
            else insertMembers(context, id, fresh)
            Log.i(TAG, "Added $n songs to ${p.name}")
            LibraryRepository.refresh()
            n
        }.onFailure { Log.w(TAG, "Couldn't add to ${p.name}", it) }
    }

    @Suppress("DEPRECATION")
    private fun filePath(context: Context, id: Long): String? = runCatching {
        context.contentResolver.query(ContentUris.withAppendedId(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, id),
            arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * .m3u / .m3u8: the songs are added as lines at the end, written like the file's own lines (\ or /, Windows or
     * Unix line ends), as paths from the playlist's folder (so a playlist made on a PC keeps working there). Everything
     * already in the file stays exactly as it was.
     */
    private fun appendM3u(context: Context, p: Playlist, path: String, songs: List<Song>): Int {
        val u = uri(context, p) ?: error("Not a playlist file")
        val old = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
        val text = String(old, Charsets.UTF_8)
        val nl = if (text.contains("\r\n") || text.isEmpty()) "\r\n" else "\n"
        val entries = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val backslash = entries.count { '\\' in it } > entries.count { '/' in it }
        val dir = java.io.File(path).parentFile
        val lines = songs.map { s ->
            val target = java.io.File(s.path)
            val rel = if (dir != null && volumeRoot(dir.path) == volumeRoot(s.path)) dir.toPath().relativize(target.toPath()).toString() else s.path
            if (backslash) rel.replace('/', '\\') else rel
        }
        val add = StringBuilder()
        if (text.isNotEmpty() && !text.endsWith("\n")) add.append(nl)
        lines.forEach { add.append(it).append(nl) }
        context.contentResolver.openOutputStream(u, "wa")?.use { it.write(add.toString().toByteArray(Charsets.UTF_8)) }
            ?: error("Can't open the playlist file")
        // Android reads the file again (and so the playlist's songs).
        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        return songs.size
    }

    /** "/storage/emulated/0" or "/storage/1E17-2F20": paths on different storage can't be relative to each other. */
    private fun volumeRoot(path: String): String {
        val parts = path.trim('/').split('/')
        return if (parts.getOrNull(1) == "emulated") parts.take(3).joinToString("/") else parts.take(2).joinToString("/")
    }

    /** Other formats (.pls, .wpl...): through Android's media store, which writes the file itself (at the end). */
    @Suppress("DEPRECATION")
    private fun insertMembers(context: Context, id: Long, songs: List<Song>): Int {
        val members = MediaStore.Audio.Playlists.Members.getContentUri(volume(context, id), id)
        val values = songs.mapIndexed { i, s ->
            ContentValues().apply {
                put(MediaStore.Audio.Playlists.Members.AUDIO_ID, s.id)
                put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, 1_000_000 + i)
            }
        }
        val n = context.contentResolver.bulkInsert(members, values.toTypedArray())
        if (n <= 0) error("Android didn't change the playlist")
        return n
    }
}
