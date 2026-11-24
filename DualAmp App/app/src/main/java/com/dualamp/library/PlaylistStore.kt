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

    /** Mix mode on or off: on, the playlist plays in order with its transitions. */
    fun setMixMode(playlistId: String, on: Boolean) =
        save(_playlists.value.map { if (it.id == playlistId) it.copy(mixMode = on) else it })

    fun playlist(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    /** Saves a transition (replacing the one between the same two songs). */
    fun saveTransition(playlistId: String, t: com.dualamp.mix.Transition) =
        save(_playlists.value.map { if (it.id == playlistId) it.copy(transitions = it.transitions + (t.pairKey to t)) else it })

    fun saveTransitions(playlistId: String, list: List<com.dualamp.mix.Transition>) =
        save(_playlists.value.map { if (it.id == playlistId) it.copy(transitions = it.transitions + list.associateBy { t -> t.pairKey }) else it })

    fun removeTransition(playlistId: String, pairKey: String) =
        save(_playlists.value.map { if (it.id == playlistId) it.copy(transitions = it.transitions - pairKey) else it })

    fun removeAllTransitions(playlistId: String) =
        save(_playlists.value.map { if (it.id == playlistId) it.copy(transitions = emptyMap()) else it })

    /** Moves a song one place up ([by] -1) or down (+1): in a mix, the order makes the transitions. */
    fun move(playlistId: String, index: Int, by: Int) = update(playlistId) { ids ->
        val to = index + by
        if (index !in ids.indices || to !in ids.indices) ids
        else ids.toMutableList().also { val s = it.removeAt(index); it.add(to, s) }
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
                if (p.mixMode) put("mix", true)
                if (p.transitions.isNotEmpty()) put("transitions", JSONArray().apply { p.transitions.values.forEach { put(it.toJson()) } })
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
                mixMode = o.optBoolean("mix", false),
                transitions = o.optJSONArray("transitions")?.let { t ->
                    (0 until t.length()).mapNotNull { runCatching { com.dualamp.mix.Transition.fromJson(t.getJSONObject(it)) }.getOrNull() }
                        .associateBy { it.pairKey }
                } ?: emptyMap(),
            )
        }
    }
}
