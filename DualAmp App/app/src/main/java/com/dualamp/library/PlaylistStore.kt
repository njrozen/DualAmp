package com.dualamp.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The app's own playlists plus "Liked Songs", saved as JSON in app storage. */
object PlaylistStore {
    const val LIKED_ID = "liked"
    private lateinit var file: File
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    val liked: Playlist get() = _playlists.value.first { it.id == LIKED_ID }

    fun init(context: Context) {
        file = File(context.filesDir, "playlists.json")
        val loaded = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
        _playlists.value = if (loaded.any { it.id == LIKED_ID }) loaded
        else listOf(Playlist(LIKED_ID, "Liked Songs", emptyList(), 0)) + loaded
    }

    fun isLiked(songId: Long) = songId in liked.songIds

    fun toggleLike(songId: Long) = update(LIKED_ID) { ids ->
        if (songId in ids) ids - songId else listOf(songId) + ids
    }

    fun create(name: String, songIds: List<Long> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.ifBlank { "New playlist" }, songIds, System.currentTimeMillis())
        save(_playlists.value + p)
        return p
    }

    fun add(playlistId: String, songIds: List<Long>) = update(playlistId) { ids -> ids + songIds.filter { it !in ids } }

    fun remove(playlistId: String, songId: Long) = update(playlistId) { ids -> ids - songId }

    fun rename(playlistId: String, name: String) =
        save(_playlists.value.map { if (it.id == playlistId) it.copy(name = name) else it })

    fun delete(playlistId: String) {
        if (playlistId == LIKED_ID) return
        save(_playlists.value.filterNot { it.id == playlistId })
    }

    private fun update(id: String, change: (List<Long>) -> List<Long>) =
        save(_playlists.value.map { if (it.id == id) it.copy(songIds = change(it.songIds)) else it })

    private fun save(list: List<Playlist>) {
        _playlists.value = list
        val json = JSONArray()
        list.forEach { p ->
            json.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("created", p.created)
                put("songs", JSONArray(p.songIds))
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private fun parse(text: String): List<Playlist> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val songs = o.getJSONArray("songs")
            Playlist(
                id = o.getString("id"),
                name = o.getString("name"),
                songIds = (0 until songs.length()).map { songs.getLong(it) },
                created = o.optLong("created"),
            )
        }
    }
}
