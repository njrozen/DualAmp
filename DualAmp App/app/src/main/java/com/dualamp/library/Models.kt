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
    /** When the file last changed (seconds), so its comment is read again after an edit. */
    val modified: Long = 0,
    /** Its comment tag says it is a leak or unreleased (Settings > Leaks & unreleased; see [Leaks]). */
    val leak: Boolean = false,
) {
    val uri: Uri get() = remoteUrl?.let(Uri::parse) ?: contentUri(id)
    val isRemote: Boolean get() = remoteUrl != null
    val folder: String get() = path.substringBeforeLast('/', "")
    /** Artist used for grouping, so compilations don't scatter across many artists. */
    val primaryArtist: String get() = albumArtist.ifBlank { artist }
    /** Each genre on the tag: "Rock; Punk" or "Rock, Punk" is Rock and Punk, not a genre of its own. */
    val genres: List<String> get() = splitGenres(genre)

    /** e.g. "FLAC", "MP3", "OPUS" from the file name or MIME type. */
    val codec: String
        get() {
            val ext = path.substringAfterLast('.', "").uppercase()
            if (ext.isNotEmpty() && ext.length <= 5) return if (ext == "M4A") "AAC" else ext
            return mime.substringAfter('/').uppercase()
        }

    /** [from]: how it got into the queue (PlaybackService.FROM_QUEUE / FROM_AUTOPLAY), null for what you chose to play. */
    fun toMediaItem(from: String? = null): MediaItem = MediaItem.Builder()
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
                .apply { if (from != null) setExtras(android.os.Bundle().apply { putString("dualamp.from", from) }) }
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
    /** Unreleased / leaked: more than half of its songs are (an official album with one leaked bonus track stays official). */
    val isLeak: Boolean get() = songs.count { it.leak } * 2 > songs.size
}

/** Title compared without case, brackets ("(feat. X)", "[Remastered]"), " - Single" / " - EP" or punctuation. */
private fun releaseName(s: String): String = s.lowercase()
    .replace(Regex("""\([^)]*\)|\[[^]]*]"""), "")
    .replace(Regex("""\s+-\s+(single|ep)\s*$"""), "")
    .filter { it.isLetterOrDigit() }

/**
 * [albums]: releases with this artist as an album artist. [songs]: everything they are on, features included.
 * [appearsOn]: other artists' releases with songs they are credited on.
 */
data class Artist(val name: String, val albums: List<Album>, val songs: List<Song>, val appearsOn: List<Album> = emptyList())

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
    /** Mix mode: plays in order with its [transitions] (DualAmp 1.6). */
    val mixMode: Boolean = false,
    /** Transitions between songs next to each other, by [com.dualamp.mix.Transition.key]. */
    val transitions: Map<String, com.dualamp.mix.Transition> = emptyMap(),
) {
    /** The transitions still between two songs next to each other. */
    val liveTransitions: List<com.dualamp.mix.Transition>
        get() = songIds.zipWithNext().mapNotNull { (a, b) -> transitions[com.dualamp.mix.Transition.key(a, b)] }
    /** Has at least one transition (shown with the mix symbol). */
    val isMixed: Boolean get() = liveTransitions.isNotEmpty()
}

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
    /** Also for a combined tag ("A & B"): its first artist. */
    fun artist(name: String): Artist? = artistByName[artistKey(name)]
        ?: ArtistNames.split(name).firstNotNullOfOrNull { artistByName[artistKey(it)] }
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

/**
 * The genres on one tag. Several genres are written "Rock; Punk", "Rock, Punk", "Rock | Punk" or (ID3v2.4) with a
 * null between them. A slash is left alone: "Hip-Hop/Rap" and "R&B/Soul" are one genre each.
 */
fun splitGenres(tag: String): List<String> =
    tag.split(';', ',', '|', '\u0000').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
