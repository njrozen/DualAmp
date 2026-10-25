package com.dualamp.library

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

/**
 * Edit song info (Y / long-press a song > Edit song info; Settings > Music library > Edit song info): title, artist,
 * album artist, album, release date, genre, track and disc number, and the cover art.
 *
 * Android does not let apps change other apps' media files directly, so saving asks once per song ("Allow DualAmp to
 * modify this audio file?"). The file is copied into DualAmp's own storage, its tags changed there (jaudiotagger), and
 * written back over the original. Then Android rescans it and the library reloads.
 */
object TagEditor {
    private const val TAG = "TagEditor"

    /** One song's fields as text, as shown and typed in the editor. */
    data class Fields(
        val title: String, val artist: String, val albumArtist: String, val album: String,
        val date: String, val genre: String, val track: String, val disc: String,
    )

    /** A new cover picked from your files or photos. */
    class Cover(val bytes: ByteArray, val mime: String)

    /** The song being edited, its fields so far, and a new cover if one was picked ([com.dualamp.ui.library.Page.EditSong]). */
    class Session(val song: Song, val original: Fields, val fields: Fields, val cover: Cover? = null) {
        val changed: Boolean get() = fields != original || cover != null
    }

    /** The edit in progress (the page rebuilds when it changes). */
    var session by mutableStateOf<Session?>(null)

    fun start(song: Song) {
        val f = Fields(
            title = song.title, artist = song.artist, albumArtist = song.albumArtist, album = song.album,
            date = if (song.year > 0) song.year.toString() else "", genre = song.genre,
            track = if (song.track > 0) song.track.toString() else "", disc = if (song.disc > 1) song.disc.toString() else "",
        )
        session = Session(song, f, f)
    }

    fun update(change: (Fields) -> Fields) { session = session?.let { Session(it.song, it.original, change(it.fields), it.cover) } }
    fun setCover(cover: Cover) { session = session?.let { Session(it.song, it.original, it.fields, cover) } }

    /** Only files on this device can be edited (not songs streamed from a PC). */
    fun canEdit(song: Song) = !song.isRemote && song.id >= 0

    /** True when DualAmp may already write [song]'s file (otherwise ask with [writeRequest]). */
    fun canWrite(context: Context, song: Song): Boolean =
        context.checkUriPermission(song.uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Android's "allow DualAmp to modify this audio file?" prompt. */
    fun writeRequest(context: Context, song: Song) = MediaStore.createWriteRequest(context.contentResolver, listOf(song.uri))

    /** Checks a field before saving: null when fine, otherwise what is wrong. */
    fun problem(f: Fields): String? = when {
        f.title.isBlank() -> "The title can't be empty"
        f.track.isNotBlank() && !f.track.trim().matches(Regex("""\d{1,3}(/\d{1,3})?""")) -> "Track number: a number, like 3 or 3/12"
        f.disc.isNotBlank() && !f.disc.trim().matches(Regex("""\d{1,2}(/\d{1,2})?""")) -> "Disc number: a number, like 1 or 1/2"
        f.date.isNotBlank() && !f.date.trim().matches(Regex("""\d{4}(-\d{2}(-\d{2})?)?""")) -> "Release date: a year (2021) or a date (2021-05-14)"
        else -> null
    }

    /** Writes the session's changes into the song's file. Needs write permission ([canWrite]). */
    suspend fun save(context: Context, s: Session): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val ext = s.song.path.substringAfterLast('.', "mp3").lowercase()
            val dir = File(app.cacheDir, "tagedit").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val temp = File(dir, "song.$ext")
            app.contentResolver.openInputStream(s.song.uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: error("Can't open the song's file")

            TagOptionSingleton.getInstance().isAndroid = true
            val audio = AudioFileIO.read(temp)
            val tag = audio.tagOrCreateAndSetDefault
            fun put(key: FieldKey, value: String, old: String) {
                if (value == old) return
                if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value.trim())
            }
            val f = s.fields; val o = s.original
            put(FieldKey.TITLE, f.title, o.title)
            put(FieldKey.ARTIST, f.artist, o.artist)
            put(FieldKey.ALBUM_ARTIST, f.albumArtist, o.albumArtist)
            put(FieldKey.ALBUM, f.album, o.album)
            put(FieldKey.YEAR, f.date, o.date)
            put(FieldKey.GENRE, f.genre, o.genre)
            // "3/12": number and total.
            if (f.track != o.track) {
                tag.deleteField(FieldKey.TRACK); tag.deleteField(FieldKey.TRACK_TOTAL)
                f.track.trim().split('/').let { p ->
                    if (p[0].isNotBlank()) tag.setField(FieldKey.TRACK, p[0])
                    if (p.size > 1 && p[1].isNotBlank()) tag.setField(FieldKey.TRACK_TOTAL, p[1])
                }
            }
            if (f.disc != o.disc) {
                tag.deleteField(FieldKey.DISC_NO); tag.deleteField(FieldKey.DISC_TOTAL)
                f.disc.trim().split('/').let { p ->
                    if (p[0].isNotBlank()) tag.setField(FieldKey.DISC_NO, p[0])
                    if (p.size > 1 && p[1].isNotBlank()) tag.setField(FieldKey.DISC_TOTAL, p[1])
                }
            }
            s.cover?.let { c ->
                tag.deleteArtworkField()
                val art = ArtworkFactory.getNew()
                art.binaryData = c.bytes
                art.mimeType = c.mime
                art.pictureType = 3 // front cover
                tag.setField(art)
            }
            audio.tag = tag
            audio.commit()

            // Back over the original ("wt": the old contents are replaced, not appended to).
            app.contentResolver.openOutputStream(s.song.uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: error("Can't write the song's file")
            temp.delete()

            // Android reads the new tags; then the library reloads with them, and the old cover is forgotten.
            MediaScannerConnection.scanFile(app, arrayOf(s.song.path), null) { _, _ ->
                Artwork.forget(s.song.albumId)
                LibraryRepository.refresh()
            }
            Log.i(TAG, "Saved the tags of ${s.song.path}")
            Unit
        }.onFailure { Log.w(TAG, "Couldn't save the tags of ${s.song.path}", it) }
    }
}
