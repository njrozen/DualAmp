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

    // Settings last pushed to projectM.
    private var appliedDuration = -1
    private var appliedHardCuts: Boolean? = null
    private var appliedLock: Boolean? = null

    private val failed = HashSet<String>()
    private var current: PresetManager.Preset? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context means every GL object of the old instance is gone.
        if (handle != 0L) MilkNative.destroy(handle)
        handle = 0L
        appliedDuration = -1
        appliedHardCuts = null
        appliedLock = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        if (handle == 0L) {
            handle = MilkNative.create(width, height, PresetManager.textureDirs.toTypedArray(), 48, 32)
            if (handle == 0L) Log.e(tag, "projectM failed to start")
            current?.let { load(it, smooth = false) } ?: next(smooth = false)
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
        if (current == null && PresetManager.presets.value.isNotEmpty()) next(smooth = false)
        handleCommands()

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

        val events = MilkNative.pollEvents(handle)
        if (events and MilkNative.EVENT_FAILED != 0) {
            current?.let { failed += it.path }
            Log.w(tag, MilkNative.lastError(handle))
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
                VizControl.Command.Next -> step(1, smooth = true)
                VizControl.Command.Previous -> step(-1, smooth = true)
                VizControl.Command.Random -> random(smooth = true)
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
        if (Prefs.shufflePresets.value) random(smooth) else step(1, smooth)
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
