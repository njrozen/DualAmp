package com.dualamp.viz

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.dualamp.audio.AudioTap
import com.dualamp.settings.Prefs
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.random.Random

/** Full-screen MilkDrop visualizer: a GLES 3 surface driving projectM. */
class MilkSurfaceView(context: Context) : GLSurfaceView(context) {
    private val renderer = MilkRenderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyQuality(w, h)
    }

    /** Renders below native resolution when "visualizer quality" is lowered, then scales up. */
    fun applyQuality(w: Int = width, h: Int = height) {
        if (w <= 0 || h <= 0) return
        val scale = Prefs.vizQuality.value.coerceIn(25, 100) / 100f
        if (scale >= 0.99f) holder.setSizeFromLayout()
        else holder.setFixedSize((w * scale).toInt(), (h * scale).toInt())
    }
}

private class MilkRenderer : GLSurfaceView.Renderer {
    private val tag = "MilkRenderer"
    private var handle = 0L
    private val pcm = FloatArray(MAX_FRAMES * 2)
    private val shaper = AudioShaper()
    private val silence = FloatArray(SILENT_FRAMES * 2)
    private var lastFrameAt = 0L
    private val capture = GlCapture()
    private var surfaceW = 0
    private var surfaceH = 0

    // Settings last pushed to projectM.
    private var appliedDuration = -1
    private var appliedHardCuts: Boolean? = null
    private var appliedLock: Boolean? = null

    private val failed = HashSet<String>()
    /** The last MilkDrop preset (still set while album art shows, to carry on from there). */
    private var current: PresetManager.Preset? = null

    // Album art (AlbumArtViz).
    private var showingArt = false
    /** Cover, style and sliders of the album art preset showing. */
    private var artKey = ""
    /** Cover whose preset projectM could not load: presets for that song. */
    private var artFailed: String? = null
    /** "Both": the next change shows album art. */
    private var artTurn = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context means every GL object of the old instance is gone.
        if (handle != 0L) MilkNative.destroy(handle)
        handle = 0L
        appliedDuration = -1
        appliedHardCuts = null
        appliedLock = null
        capture.reset()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceW = width
        surfaceH = height
        if (handle == 0L) {
            handle = MilkNative.create(width, height, PresetManager.textureDirs.toTypedArray(), 48, 32)
            if (handle == 0L) Log.e(tag, "projectM failed to start")
            if (!(showingArt && showArt(smooth = false))) current?.let { load(it, smooth = false) } ?: next(smooth = false)
        } else {
            MilkNative.resize(handle, width, height)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (handle == 0L) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        capFrameRate()
        applySettings()
        // The preset list loads in the background; start as soon as it arrives.
        if (current == null && !showingArt && PresetManager.presets.value.isNotEmpty()) next(smooth = false)
        handleCommands()
        followArt()

        val frames = AudioTap.read(pcm, MAX_FRAMES)
        when {
            frames > 0 -> {
                shaper.process(pcm, frames, Prefs.vizReactivity.value, AudioTap.sampleRate)
                MilkNative.addPcm(handle, pcm, frames)
            }
            // Paused: feed silence so the waveform settles instead of freezing mid-beat.
            !AudioTap.playing -> MilkNative.addPcm(handle, silence, SILENT_FRAMES)
        }

        MilkNative.render(handle)
        // Settings > Stream visualizer: a copy for browsers on the Wi-Fi.
        capture.afterFrame(surfaceW, surfaceH)

        val events = MilkNative.pollEvents(handle)
        if (events and MilkNative.EVENT_FAILED != 0) {
            Log.w(tag, MilkNative.lastError(handle))
            if (showingArt) {
                artFailed = AlbumArtViz.cover.value?.texture
                showingArt = false
            } else {
                current?.let { failed += it.path }
            }
            next(smooth = false)
        } else if (events and MilkNative.EVENT_SWITCH != 0 && VizControl.Mode.current() == VizControl.Mode.INTERVAL) {
            next(smooth = events and MilkNative.EVENT_HARD_CUT == 0)
        }
    }

    private fun capFrameRate() {
        val cap = Prefs.vizFpsCap.value
        if (cap > 0) {
            val frameMs = 1000L / cap
            val wait = lastFrameAt + frameMs - SystemClock.uptimeMillis()
            if (wait in 1..frameMs) SystemClock.sleep(wait)
        }
        lastFrameAt = SystemClock.uptimeMillis()
    }

    private fun applySettings() {
        // Outside interval mode projectM never switches by itself; song changes are handled below.
        val duration = if (VizControl.Mode.current() == VizControl.Mode.INTERVAL) Prefs.presetDurationSec.value else 0
        val hard = Prefs.hardCuts.value
        if (duration != appliedDuration || hard != appliedHardCuts) {
            MilkNative.configure(handle, duration.toDouble(), 3.0, hard, 1.0f)
            appliedDuration = duration
            appliedHardCuts = hard
        }
        val lock = VizControl.Mode.current() != VizControl.Mode.INTERVAL
        if (lock != appliedLock) {
            MilkNative.setLocked(handle, lock)
            appliedLock = lock
        }
    }

    private fun handleCommands() {
        while (true) {
            when (val cmd = VizControl.commands.poll() ?: return) {
                VizControl.Command.Next -> if (!artChange(1, smooth = true)) step(1, smooth = true)
                VizControl.Command.Previous -> if (!artChange(-1, smooth = true)) step(-1, smooth = true)
                VizControl.Command.Random -> if (!artChange(0, smooth = true)) random(smooth = true)
                VizControl.Command.SongChanged -> if (VizControl.Mode.current() == VizControl.Mode.SONG) next(smooth = true)
                is VizControl.Command.Load -> {
                    val preset = PresetManager.presets.value.firstOrNull { it.path == cmd.path } ?: continue
                    load(preset, smooth = true)
                }
            }
        }
    }

    /** Automatic switch (timer, beat, song change): random or in order, following Settings > Shuffle presets. */
    private fun next(smooth: Boolean) {
        val shuffle = Prefs.shufflePresets.value
        if (artChange(if (shuffle) 0 else 1, smooth)) return
        if (shuffle) random(smooth) else step(1, smooth)
    }

    /**
     * A visualizer change when album art is to show: the next (+1), previous (-1) or a random (0) style,
     * if styles change at all. False when a preset should show instead (presets chosen, or no cover).
     */
    private fun artChange(by: Int, smooth: Boolean): Boolean {
        val source = AlbumArtViz.Source.current()
        val art = source == AlbumArtViz.Source.ALBUM_ART || (source == AlbumArtViz.Source.BOTH && artTurn)
        if (source == AlbumArtViz.Source.BOTH) artTurn = !artTurn
        if (!art) return false
        if (showingArt && !Prefs.artCycleStyles.value) return true
        if (showingArt) {
            val style = Prefs.artStyle.value
            Prefs.artStyle.value = when (by) {
                0 -> (style + 1 + Random.nextInt(ART_STYLES.size - 1)).mod(ART_STYLES.size)
                else -> (style + by).mod(ART_STYLES.size)
            }
        }
        return showArt(smooth)
    }

    /**
     * Keeps the album art preset in step with the song's cover and the album art settings, and starts or
     * stops it when Settings > Visualizer shows changes.
     */
    private fun followArt() {
        val source = AlbumArtViz.Source.current()
        val cover = AlbumArtViz.cover.value
        if (showingArt) {
            when {
                source == AlbumArtViz.Source.PRESETS || cover == null -> {
                    showingArt = false
                    current?.let { load(it, smooth = true) } ?: next(smooth = true)
                }
                artKeyOf(cover) != artKey -> showArt(smooth = true)
            }
        } else if (source == AlbumArtViz.Source.ALBUM_ART && cover != null && cover.texture != artFailed) {
            showArt(smooth = true)
        }
    }

    private fun artKeyOf(cover: AlbumArtViz.Cover) =
        "${cover.texture}|${Prefs.artStyle.value}|${AlbumArtViz.tweaks.value}|${Prefs.artFineBands.value}"

    /** Loads the album art preset for the song playing. False when there is no cover (or it failed). */
    private fun showArt(smooth: Boolean): Boolean {
        val cover = AlbumArtViz.cover.value ?: return false
        if (cover.texture == artFailed) return false
        val style = Prefs.artStyle.value.coerceIn(ART_STYLES.indices)
        val text = MilkNative.buildArtPreset(arrayOf(cover.texture), cover.palette, floatArrayOf(1f), style,
            AlbumArtViz.paramValues(), Prefs.artFineBands.value)
        MilkNative.loadPresetData(handle, text, smooth)
        showingArt = true
        artKey = artKeyOf(cover)
        val name = "Album art: ${ART_STYLES[style]}"
        Log.d("MilkSurfaceView", "$name (${cover.title})")
        VizControl.publishPreset(PresetManager.Preset(name, "Album art", PresetManager.ALBUM_ART_PREFIX + ART_STYLES[style], bundled = false))
        return true
    }

    /**
     * The presets the visualizer cycles through: the active visualizer folder in its own order, the
     * active type, or every preset. Falls back to every preset if the folder is empty.
     */
    private fun cycleList(): List<PresetManager.Preset> {
        val all = PresetManager.presets.value.filter { it.path !in failed }
        val folder = VizFolders.active
        val list = if (folder != null && VizFolders.activeType == null) {
            val byPath = all.associateBy { it.path }
            folder.presetPaths.mapNotNull { byPath[it] }
        } else all.filter { VizFolders.allows(it) }
        return list.ifEmpty { all }
    }

    /** Arrows / swipes: the next (+1) or previous (-1) preset of the folder, wrapping round at either end. */
    private fun step(by: Int, smooth: Boolean) {
        val list = cycleList()
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.path == current?.path }
        // Not in the list yet (just switched folder): start at its first or last preset.
        val target = if (i < 0) (if (by > 0) 0 else list.lastIndex) else (i + by).mod(list.size)
        load(list[target], smooth)
    }

    /** A random preset of the folder (never the one showing, when there is a choice). */
    private fun random(smooth: Boolean) {
        val list = cycleList()
        if (list.isEmpty()) return
        val choices = list.filter { it.path != current?.path }.ifEmpty { list }
        load(choices[Random.nextInt(choices.size)], smooth)
    }

    private fun load(preset: PresetManager.Preset, smooth: Boolean) {
        current = preset
        showingArt = false
        Log.d("MilkSurfaceView", "Preset: ${preset.name}")
        VizControl.publishPreset(preset)
        if (preset.bundled) {
            val text = PresetManager.readText(preset)
            if (text == null) {
                failed += preset.path
                return
            }
            MilkNative.loadPresetData(handle, text, smooth)
        } else {
            // Loading by path lets projectM find textures that sit next to the preset.
            MilkNative.loadPresetFile(handle, preset.path, smooth)
        }
    }

    companion object {
        private const val MAX_FRAMES = 4096
        private const val SILENT_FRAMES = 512
    }
}
