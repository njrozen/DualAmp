package com.dualamp.viz

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the list of MilkDrop presets.
 *
 * Two sources:
 *  - bundled: app/src/main/assets/presets, listed in presets/index.txt (see tools/fetch-presets.ps1)
 *  - imported: any folder of .milk files the user picks, copied into app storage
 *
 * Preset text is handed to projectM directly, so bundled presets are never unpacked. Textures are
 * the exception: projectM loads them by path, so bundled textures are copied out once.
 */
object PresetManager {
    private const val TAG = "PresetManager"
    private const val ASSET_ROOT = "presets"
    private val TEXTURE_EXT = setOf("jpg", "jpeg", "png", "tga", "bmp", "dds")

    data class Preset(val name: String, val category: String, val path: String, val bundled: Boolean)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    val userDir: File get() = File(app.filesDir, "presets")
    private val bundledTextureDir: File get() = File(app.filesDir, "bundled_textures")

    /** Folders projectM searches for textures referenced by presets. */
    val textureDirs: List<String> get() = listOf(bundledTextureDir.path, userDir.path)

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch {
            copyBundledTextures()
            rescan()
        }
    }

    fun rescan() {
        val bundled = runCatching {
            app.assets.open("$ASSET_ROOT/index.txt").bufferedReader().readLines()
        }.getOrDefault(emptyList())
            .filter { it.endsWith(".milk", ignoreCase = true) }
            .map { rel -> toPreset(rel, "$ASSET_ROOT/$rel", bundled = true) }

        val user = userDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("milk", ignoreCase = true) }
            .map { toPreset(it.relativeTo(userDir).path.replace('\\', '/'), it.path, bundled = false) }
            .toList()

        _presets.value = (user + bundled).sortedWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
        Log.i(TAG, "${bundled.size} bundled + ${user.size} imported presets")
    }

    private fun toPreset(relative: String, path: String, bundled: Boolean): Preset {
        val parts = relative.split('/')
        val category = if (parts.size > 1) parts.first() else if (bundled) "Bundled" else "Imported"
        return Preset(parts.last().removeSuffix(".milk").removeSuffix(".MILK"), category, path, bundled)
    }

    fun readText(preset: Preset): String? = try {
        if (preset.bundled) app.assets.open(preset.path).bufferedReader(Charsets.ISO_8859_1).readText()
        else File(preset.path).readText(Charsets.ISO_8859_1)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read ${preset.path}", e)
        null
    }

    private fun copyBundledTextures() {
        val marker = File(bundledTextureDir, ".done")
        if (marker.exists()) return
        val names = runCatching { app.assets.list("$ASSET_ROOT/textures") }.getOrNull() ?: return
        bundledTextureDir.mkdirs()
        for (name in names) {
            if (name.substringAfterLast('.').lowercase() !in TEXTURE_EXT) continue
            runCatching {
                app.assets.open("$ASSET_ROOT/textures/$name").use { input ->
                    File(bundledTextureDir, name).outputStream().use { input.copyTo(it) }
                }
            }
        }
        marker.createNewFile()
    }

    /** Copies every .milk file and texture under [treeUri] (a folder picked by the user). */
    fun importFolder(treeUri: Uri) {
        scope.launch {
            val root = DocumentFile.fromTreeUri(app, treeUri) ?: return@launch
            val dest = File(userDir, root.name ?: "Imported")
            var count = 0
            _importStatus.value = "Importing presets…"
            fun walk(dir: DocumentFile, out: File) {
                for (f in dir.listFiles()) {
                    val name = f.name ?: continue
                    if (f.isDirectory) {
                        walk(f, File(out, name))
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext != "milk" && ext !in TEXTURE_EXT) continue
                    out.mkdirs()
                    runCatching {
                        app.contentResolver.openInputStream(f.uri)?.use { input ->
                            File(out, name).outputStream().use { input.copyTo(it) }
                        }
                        if (ext == "milk") {
                            count++
                            if (count % 50 == 0) _importStatus.value = "Importing presets… $count"
                        }
                    }
                }
            }
            walk(root, dest)
            rescan()
            _importStatus.value = "Imported $count presets"
        }
    }

    suspend fun deleteImported() = withContext(Dispatchers.IO) {
        userDir.deleteRecursively()
        rescan()
        _importStatus.value = "Removed imported presets"
    }
}
