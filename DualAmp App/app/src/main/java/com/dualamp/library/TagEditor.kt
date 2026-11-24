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
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.BufferedInputStream
import java.io.File

/**
 * Edit song info (Y / long-press a song > Edit song info; Settings > Music library > Edit song info): title, artist,
 * album artist, album, release date, genre, track and disc number, comment, and the cover art; and taking out the
 * lyrics stored in the song (not .lrc files).
 *
 * Several songs at once (an album's menu, or songs picked with Select multiple): each field shows the songs' value when
 * they all share it, or "Various" when they don't. Only the fields you change are written, to every song; the others
 * keep each song's own value. Title and track number are each song's own, so they are left out then.
 *
 * Android does not let apps change other apps' media files directly, so saving asks first ("Allow DualAmp to modify
 * these audio files?", once for all of them). Each file is copied into DualAmp's own storage, its tags changed there
 * (jaudiotagger), and written back over the original. Then Android rescans them and the library reloads.
 */
object TagEditor {
    private const val TAG = "TagEditor"

    /** Shown for a field whose value differs between the songs (and is left alone unless you type a new one). */
    const val VARIOUS = "\u0000various"

    /** The fields as text, as shown and typed in the editor ([VARIOUS] where the songs differ). */
    data class Fields(
        val title: String, val artist: String, val albumArtist: String, val album: String,
        val date: String, val genre: String, val track: String, val disc: String, val comment: String,
    )

    /** A new cover picked from your files or photos. */
    class Cover(val bytes: ByteArray, val mime: String)

    /**
     * The songs being edited, their fields so far, a new cover if one was picked, and whether to take out their lyrics
     * ([com.dualamp.ui.library.Page.EditSong]).
     */
    class Session(
        val songs: List<Song>, val original: Fields, val fields: Fields,
        val cover: Cover? = null, val removeLyrics: Boolean = false,
    ) {
        val song: Song get() = songs.first()
        val multiple: Boolean get() = songs.size > 1
        val changed: Boolean get() = fields != original || cover != null || removeLyrics
        fun copy(fields: Fields = this.fields, cover: Cover? = this.cover, removeLyrics: Boolean = this.removeLyrics) =
            Session(songs, original, fields, cover, removeLyrics)
    }

    /** The edit in progress (the page rebuilds when it changes). */
    var session by mutableStateOf<Session?>(null)

    /** Starts editing [songs] (the ones on this device): reads their comments, so call it off the main thread's hot path. */
    suspend fun start(context: Context, songs: List<Song>) {
        val list = songs.filter(::canEdit).distinctBy { it.id }
        if (list.isEmpty()) return
        val comments = withContext(Dispatchers.IO) {
            list.associate { s ->
                s.id to runCatching {
                    context.contentResolver.openInputStream(s.uri)?.use { Comments.commentOf(BufferedInputStream(it, 16384)) }
                }.getOrNull().orEmpty()
            }
        }
        fun shared(value: (Song) -> String) = list.map(value).distinct().singleOrNull() ?: VARIOUS
        val f = Fields(
            title = shared { it.title }, artist = shared { it.artist }, albumArtist = shared { it.albumArtist },
            album = shared { it.album }, date = shared { if (it.year > 0) it.year.toString() else "" }, genre = shared { it.genre },
            track = shared { if (it.track > 0) it.track.toString() else "" }, disc = shared { if (it.disc > 1) it.disc.toString() else "" },
            comment = shared { comments[it.id].orEmpty() },
        )
        session = Session(list, f, f)
    }

    fun update(change: (Fields) -> Fields) { session = session?.let { it.copy(fields = change(it.fields)) } }
    fun setCover(cover: Cover) { session = session?.let { it.copy(cover = cover) } }
    fun toggleRemoveLyrics() { session = session?.let { it.copy(removeLyrics = !it.removeLyrics) } }

    /** Only files on this device can be edited (not songs streamed from a PC). */
    fun canEdit(song: Song) = !song.isRemote && song.id >= 0

    /** True when DualAmp may already write all of [songs]' files (otherwise ask with [writeRequest]). */
    fun canWrite(context: Context, songs: List<Song>): Boolean = songs.all {
        context.checkUriPermission(it.uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    /** Android's "allow DualAmp to modify these audio files?" prompt (one for all of them). */
    fun writeRequest(context: Context, songs: List<Song>) = MediaStore.createWriteRequest(context.contentResolver, songs.map { it.uri })

    /** Checks the fields before saving: null when fine, otherwise what is wrong. */
    fun problem(f: Fields): String? {
        fun bad(v: String, re: String) = v != VARIOUS && v.isNotBlank() && !v.trim().matches(Regex(re))
        return when {
            f.title.isBlank() -> "The title can't be empty"
            bad(f.track, """\d{1,3}(/\d{1,3})?""") -> "Track number: a number, like 3 or 3/12"
            bad(f.disc, """\d{1,2}(/\d{1,2})?""") -> "Disc number: a number, like 1 or 1/2"
            bad(f.date, """\d{4}(-\d{2}(-\d{2})?)?""") -> "Release date: a year (2021) or a date (2021-05-14)"
            else -> null
        }
    }

    /**
     * Writes the session's changes into the songs' files. Needs write permission ([canWrite]). [progress] hears how many
     * are done. Stops at the first song that can't be saved (the ones before it keep their changes).
     */
    suspend fun save(context: Context, s: Session, progress: (Int) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val dir = File(app.cacheDir, "tagedit").apply { mkdirs() }
            s.songs.forEachIndexed { i, song ->
                runCatching { saveOne(app, dir, song, s) }.getOrElse { throw IllegalStateException("${song.title}: ${it.message ?: it.javaClass.simpleName}", it) }
                withContext(Dispatchers.Main) { progress(i + 1) }
            }
            // Android reads the new tags; then the library reloads with them, and the old covers and lyrics are forgotten.
            val paths = s.songs.map { it.path }.toTypedArray()
            var left = paths.size
            MediaScannerConnection.scanFile(app, paths, null) { _, _ ->
                if (--left > 0) return@scanFile
                s.songs.map { it.albumId }.distinct().forEach(Artwork::forget)
                s.songs.forEach(LyricsRepository::forget)
                LibraryRepository.refresh()
            }
            Log.i(TAG, "Saved the tags of ${s.songs.size} songs")
            Unit
        }.onFailure { Log.w(TAG, "Couldn't save the tags", it) }
    }

    private fun saveOne(app: Context, dir: File, song: Song, s: Session) {
        val ext = song.path.substringAfterLast('.', "mp3").lowercase()
        dir.listFiles()?.forEach { it.delete() }
        val temp = File(dir, "song.$ext")
        app.contentResolver.openInputStream(song.uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ?: error("Can't open the song's file")

        TagOptionSingleton.getInstance().isAndroid = true
        val audio = AudioFileIO.read(temp)
        val tag = audio.tagOrCreateAndSetDefault
        // A field is written only when it was changed; "Various" left as it is keeps each song's own value.
        fun put(key: FieldKey, value: String, old: String) {
            if (value == old || value == VARIOUS) return
            if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value.trim())
        }
        fun putNumber(key: FieldKey, totalKey: FieldKey, value: String, old: String) {
            if (value == old || value == VARIOUS) return
            // "3/12": number and total.
            tag.deleteField(key); tag.deleteField(totalKey)
            value.trim().split('/').let { p ->
                if (p[0].isNotBlank()) tag.setField(key, p[0])
                if (p.size > 1 && p[1].isNotBlank()) tag.setField(totalKey, p[1])
            }
        }
        val f = s.fields; val o = s.original
        put(FieldKey.TITLE, f.title, o.title)
        put(FieldKey.ARTIST, f.artist, o.artist)
        put(FieldKey.ALBUM_ARTIST, f.albumArtist, o.albumArtist)
        put(FieldKey.ALBUM, f.album, o.album)
        put(FieldKey.YEAR, f.date, o.date)
        put(FieldKey.GENRE, f.genre, o.genre)
        put(FieldKey.COMMENT, f.comment, o.comment)
        putNumber(FieldKey.TRACK, FieldKey.TRACK_TOTAL, f.track, o.track)
        putNumber(FieldKey.DISC_NO, FieldKey.DISC_TOTAL, f.disc, o.disc)
        s.cover?.let { c ->
            tag.deleteArtworkField()
            val art = ArtworkFactory.getNew()
            art.binaryData = c.bytes
            art.mimeType = c.mime
            art.pictureType = 3 // front cover
            tag.setField(art)
        }
        if (s.removeLyrics) {
            // Plain (USLT, LYRICS, ©lyr) and, in MP3s, timed (SYLT) lyrics; FLAC / Ogg taggers also use these names.
            runCatching { tag.deleteField(FieldKey.LYRICS) }
            (tag as? AbstractID3v2Tag)?.removeFrame("SYLT")
            for (name in listOf("UNSYNCEDLYRICS", "SYNCEDLYRICS", "UNSYNCED LYRICS")) runCatching { tag.deleteField(name) }
        }
        audio.tag = tag
        audio.commit()

        // Back over the original ("wt": the old contents are replaced, not appended to).
        app.contentResolver.openOutputStream(song.uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
            ?: error("Can't write the song's file")
        temp.delete()
    }
}
