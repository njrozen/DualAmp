package com.dualamp.library

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val track: Int,
    val disc: Int,
    val durationMs: Long,
    val path: String,
    val mime: String,
    val dateAdded: Long,
    val bitrate: Int,
    /** Set for songs streamed from DualAmp Server on a PC. */
    val remoteUrl: String? = null,
    val remoteArtUrl: String? = null,
) {
    val uri: Uri get() = remoteUrl?.let(Uri::parse) ?: contentUri(id)
    val isRemote: Boolean get() = remoteUrl != null
    val folder: String get() = path.substringBeforeLast('/', "")
    /** Artist used for grouping, so compilations don't scatter across many artists. */
    val primaryArtist: String get() = albumArtist.ifBlank { artist }

    /** e.g. "FLAC", "MP3", "OPUS" from the file name or MIME type. */
    val codec: String
        get() {
            val ext = path.substringAfterLast('.', "").uppercase()
            if (ext.isNotEmpty() && ext.length <= 5) return if (ext == "M4A") "AAC" else ext
            return mime.substringAfter('/').uppercase()
        }

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist.ifBlank { null })
                .setGenre(genre.ifBlank { null })
                .setReleaseYear(year.takeIf { it > 0 })
                .setTrackNumber(track.takeIf { it > 0 })
                .setDurationMs(durationMs)
                .setArtworkUri(remoteArtUrl?.let(Uri::parse) ?: ContentUris.withAppendedId(ALBUM_ART_URI, albumId))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    companion object {
        private val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")
        fun contentUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    }
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val genre: String,
    val songs: List<Song>,
) {
    val durationMs: Long get() = songs.sumOf { it.durationMs }
    val coverSongId: Long get() = songs.firstOrNull()?.id ?: -1
    /**
     * A single: its only song has the album's name ("Cartier (feat. X)" on "Cartier - Single" counts).
     * One song from a bigger album that happens to be alone in the library is still an album.
     */
    val isSingle: Boolean get() = songs.size == 1 && releaseName(songs.first().title) == releaseName(title)
}

/** Title compared without case, brackets ("(feat. X)", "[Remastered]"), " - Single" / " - EP" or punctuation. */
private fun releaseName(s: String): String = s.lowercase()
    .replace(Regex("""\([^)]*\)|\[[^]]*]"""), "")
    .replace(Regex("""\s+-\s+(single|ep)\s*$"""), "")
    .filter { it.isLetterOrDigit() }

data class Artist(val name: String, val albums: List<Album>, val songs: List<Song>)

data class Genre(val name: String, val albums: List<Album>, val songs: List<Song>)

data class YearGroup(val year: Int, val albums: List<Album>, val songs: List<Song>)

data class Folder(
    val path: String,
    val name: String,
    val folders: List<Folder>,
    val songs: List<Song>,
) {
    /** Songs in this folder and every folder below it. */
    val allSongs: List<Song> get() = songs + folders.flatMap { it.allSongs }
}

data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<Long>,
    val created: Long,
    /** Playlists from the Android media store are shown but not edited. */
    val readOnly: Boolean = false,
)

class Library(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val genres: List<Genre>,
    val years: List<YearGroup>,
    val rootFolder: Folder,
    val devicePlaylists: List<Playlist>,
    /** Songs from the other library (Thor / PC), findable by id but not listed here. */
    lookup: List<Song> = emptyList(),
) {
    val songById: Map<Long, Song> = (lookup + songs).associateBy { it.id }
    /** Everything but singles; the Albums tab shows only these. */
    val fullAlbums: List<Album> = albums.filterNot { it.isSingle }
    /** See [Album.isSingle]. */
    val singles: List<Song> = albums.filter { it.isSingle }.map { it.songs.first() }
    val albumById: Map<Long, Album> = albums.associateBy { it.id }
    /** Keyed by [artistKey], so "Kanye West" and "kanye west" are the same artist. */
    val artistByName: Map<String, Artist> = artists.associateBy { artistKey(it.name) }
    fun artist(name: String): Artist? = artistByName[artistKey(name)]
    val genreByName: Map<String, Genre> = genres.associateBy { it.name }
    val yearByValue: Map<Int, YearGroup> = years.associateBy { it.year }

    fun findFolder(path: String): Folder? {
        fun walk(f: Folder): Folder? {
            if (f.path == path) return f
            for (child in f.folders) if (path == child.path || path.startsWith(child.path + "/")) return walk(child)
            return null
        }
        return walk(rootFolder)
    }

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Folder("", "Storage", emptyList(), emptyList()), emptyList())
    }
}

/** Artist identity ignoring capitalization and surrounding spaces. */
fun artistKey(name: String): String = name.trim().lowercase()
