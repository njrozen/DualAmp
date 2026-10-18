package com.dualamp.viz

import android.content.Context
import com.dualamp.settings.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The user's own folders of visualizer presets (like playlists, but for presets).
 * One folder can be made "active", so the visualizer only cycles through its presets.
 */
object VizFolders {
    data class VizFolder(val id: String, val name: String, val presetPaths: List<String>)

    private lateinit var file: File
    private val _folders = MutableStateFlow<List<VizFolder>>(emptyList())
    val folders: StateFlow<List<VizFolder>> = _folders.asStateFlow()

    /** The folder the visualizer cycles through, or null for all presets. */
    val active: VizFolder? get() = _folders.value.firstOrNull { it.id == Prefs.activeVizFolder.value }

    fun init(context: Context) {
        file = File(context.filesDir, "viz_folders.json")
        _folders.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun create(name: String, presetPaths: List<String> = emptyList()): VizFolder {
        val f = VizFolder(UUID.randomUUID().toString(), name.ifBlank { "My visualizers" }, presetPaths)
        save(_folders.value + f)
        return f
    }

    fun add(folderId: String, path: String) = update(folderId) { if (path in it) it else it + path }

    fun rename(folderId: String, name: String) {
        if (name.isBlank()) return
        save(_folders.value.map { if (it.id == folderId) it.copy(name = name.trim()) else it })
    }
    fun remove(folderId: String, path: String) = update(folderId) { it - path }

    fun delete(folderId: String) {
        if (Prefs.activeVizFolder.value == folderId) Prefs.activeVizFolder.value = ""
        save(_folders.value.filterNot { it.id == folderId })
    }

    /** Prefix for an active built-in preset type (a category folder of the bundled pack). */
    const val TYPE_PREFIX = "type:"

    /** Name of the active built-in visualizer type, if one is chosen instead of a folder. */
    val activeType: String? get() = Prefs.activeVizFolder.value.takeIf { it.startsWith(TYPE_PREFIX) }?.removePrefix(TYPE_PREFIX)

    fun setActiveType(type: String) {
        Prefs.activeVizFolder.value = TYPE_PREFIX + type
    }

    /** Whether [preset] is in the active folder or type (true for everything when neither is set). */
    fun allows(preset: PresetManager.Preset): Boolean {
        activeType?.let { return preset.category == it }
        val folder = active ?: return true
        return preset.path in folder.presetPaths
    }

    fun setActive(folderId: String?) {
        Prefs.activeVizFolder.value = folderId.orEmpty()
    }

    private fun update(id: String, change: (List<String>) -> List<String>) =
        save(_folders.value.map { if (it.id == id) it.copy(presetPaths = change(it.presetPaths)) else it })

    private fun save(list: List<VizFolder>) {
        _folders.value = list
        val json = JSONArray()
        list.forEach { f ->
            json.put(JSONObject().put("id", f.id).put("name", f.name).put("presets", JSONArray(f.presetPaths)))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private fun parse(text: String): List<VizFolder> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("presets")
            VizFolder(o.getString("id"), o.getString("name"), (0 until p.length()).map { p.getString(it) })
        }
    }
}
