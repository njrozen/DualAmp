package com.dualamp.viz

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import com.dualamp.library.Artwork
import com.dualamp.library.LibraryRepository
import com.dualamp.library.Song
import com.dualamp.playback.PlayerConnection
import com.dualamp.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** One adjustable element of the album art visualizer, in the order of albumart::Param (cpp/albumartpreset.h). */
class ArtParam(val id: String, val label: String, val subtitle: String, val min: Float, val max: Float, val default: Float) {
    /** Settings store 0-100 % of the range. */
    val defaultPercent: Int get() = ((default - min) / (max - min) * 100).roundToInt()
    fun valueAt(percent: Int): Float = min + (max - min) * percent.coerceIn(0, 100) / 100f
}

val ART_PARAMS = listOf(
    ArtParam("Size", "Cover size / zoom", "How big the cover is (Cover style) or how far in it zooms", 0.2f, 1f, 0.55f),
    ArtParam("BassPulse", "Bass pulse", "How much the bass pumps the cover", 0f, 2f, 1f),
    ArtParam("Warp", "Warp / melt", "How much the mids bend and melt the picture", 0f, 2f, 1f),
    ArtParam("Spin", "Spin", "How fast it turns", 0f, 2f, 0.5f),
    ArtParam("Trails", "Trails", "How long old frames linger", 0f, 1f, 0.6f),
    ArtParam("Glow", "Colour glow", "A glow in the cover's own colour, on the beat", 0f, 2f, 1f),
    ArtParam("Sparkle", "Treble sparkle", "The cover's edges flash with hi-hats and cymbals", 0f, 2f, 1f),
    ArtParam("Waveform", "Waveform", "A waveform in the cover's second colour", 0f, 1f, 0.6f),
    ArtParam("Background", "Background art", "The cover, blurred, behind everything", 0f, 1f, 0.5f),
    ArtParam("ColorDrift", "Colour drift", "Colours slowly turning round the colour wheel", 0f, 1f, 0f),
    ArtParam("Slices", "Kaleidoscope slices", "Mirrored slices in the Kaleidoscope style", 2f, 16f, 6f),
    // Living photo only.
    ArtParam("Depth", "Depth", "Living photo: bright parts bulge forward on the bass, dark parts sink", 0f, 2f, 1f),
    ArtParam("Outlines", "Outline ripples", "Living photo: ripples run along the picture's own edges with the mids", 0f, 2f, 1f),
    ArtParam("Bands", "Bass / mid / treble bands", "Living photo: the bottom sways with the bass, the middle with the mids, the top with the treble", 0f, 2f, 1f),
    ArtParam("ColorSplit", "Colour split", "Living photo: red, green and blue pull apart on hi-hats", 0f, 2f, 1f),
    ArtParam("Glitch", "Beat glitches", "Living photo: strips of the picture jump sideways on big beats", 0f, 2f, 0.6f),
    ArtParam("Breathe", "Breathing", "Living photo: a slow zoom and drift between beats", 0f, 2f, 1f),
)

/** Album art styles, in the order of albumart::Style. */
val ART_STYLES = listOf("Cover", "Melt", "Kaleidoscope", "Tunnel", "Living photo")

/**
 * The album art visualizer: MilkDrop presets built around the cover of the song playing
 * (cpp/albumartpreset.cpp, shared with the Mixxx visualizer). The cover is saved in [dir], one of
 * projectM's texture folders; [MilkSurfaceView] builds and loads the preset.
 */
object AlbumArtViz {
    private const val TAG = "AlbumArtViz"
    private const val SIZE = 512

    /** What the visualizer shows. Stored in [Prefs.vizSource]. */
    enum class Source(val label: String) {
        PRESETS("MilkDrop presets"), ALBUM_ART("Album art"), BOTH("Both, taking turns");

        companion object {
            fun current(): Source = runCatching { valueOf(Prefs.vizSource.value) }.getOrDefault(PRESETS)
        }
    }

    /** The cover of the song playing: texture name, palette (see [MilkNative.artPalette]) and who it is by. */
    class Cover(val texture: String, val palette: FloatArray, val title: String)

    private val _cover = MutableStateFlow<Cover?>(null)
    val cover: StateFlow<Cover?> = _cover.asStateFlow()

    /** Goes up whenever one of the album art sliders moves, so the visualizer rebuilds its preset. */
    private val _tweaks = MutableStateFlow(0)
    val tweaks: StateFlow<Int> = _tweaks.asStateFlow()

    /** Folder of saved covers, searched by projectM for textures. */
    lateinit var dir: File
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) {
        val app = context.applicationContext
        dir = File(app.cacheDir, "viz_art").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        scope.launch {
            // The song playing, once the library that knows it has loaded.
            combine(
                PlayerConnection.state.map { it.songId }.distinctUntilChanged(),
                LibraryRepository.library,
                LibraryRepository.pcLibrary,
            ) { id, local, pc -> id?.let { local?.songById?.get(it) ?: pc?.songById?.get(it) } }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { song -> _cover.value = song?.let { load(app, it) } }
        }
        scope.launch {
            merge(*Prefs.artParams.map { it.state }.toTypedArray()).collect { _tweaks.value++ }
        }
    }

    /** The slider settings as preset values, in [ART_PARAMS] order. */
    fun paramValues(): FloatArray = FloatArray(ART_PARAMS.size) { ART_PARAMS[it].valueAt(Prefs.artParams[it].value) }

    fun resetTweaks() = Prefs.artParams.forEachIndexed { i, setting -> setting.value = ART_PARAMS[i].defaultPercent }

    private suspend fun load(context: Context, song: Song): Cover? = withContext(Dispatchers.IO) {
        try {
            val image = Artwork.load(context, song.albumId, song.id, SIZE) ?: return@withContext null
            var bitmap = image.asAndroidBitmap()
            if (bitmap.config == Bitmap.Config.HARDWARE) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            // Square and centre-cropped, as the presets show it.
            val side = min(bitmap.width, bitmap.height)
            val square = Bitmap.createScaledBitmap(
                Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side),
                SIZE, SIZE, true,
            )
            val pixels = IntArray(SIZE * SIZE)
            square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val name = MilkNative.artTextureName(pixels)
            val file = File(dir, "$name.jpg")
            if (!file.exists()) file.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            // Older covers are not needed: projectM keeps the ones it loaded in memory.
            dir.listFiles()?.forEach { if (it.name != file.name) it.delete() }
            val title = listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" - ")
            Cover(name, MilkNative.artPalette(pixels, SIZE, SIZE), title)
        } catch (e: Exception) {
            Log.w(TAG, "No album art visualizer for ${song.title}", e)
            null
        }
    }
}
