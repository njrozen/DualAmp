package com.dualamp.viz

/** JNI surface of libmilkbridge.so (projectM). Call only from the GL render thread. */
object MilkNative {
    const val EVENT_SWITCH = 1
    const val EVENT_HARD_CUT = 2
    const val EVENT_FAILED = 4

    init {
        System.loadLibrary("milkbridge")
    }

    external fun create(width: Int, height: Int, texturePaths: Array<String>, meshX: Int, meshY: Int): Long
    external fun destroy(handle: Long)
    external fun resize(handle: Long, width: Int, height: Int)
    external fun render(handle: Long)
    /** [data] holds interleaved stereo floats; [frames] is the number of L/R pairs. */
    external fun addPcm(handle: Long, data: FloatArray, frames: Int)
    external fun loadPresetData(handle: Long, data: String, smooth: Boolean)
    external fun loadPresetFile(handle: Long, path: String, smooth: Boolean)
    external fun configure(handle: Long, presetDuration: Double, softCut: Double, hardCuts: Boolean, beatSensitivity: Float)
    external fun setLocked(handle: Long, locked: Boolean)
    external fun pollEvents(handle: Long): Int
    external fun lastError(handle: Long): String

    // Album art presets (see AlbumArtViz). These don't use OpenGL and work on any thread.
    /** [argb]: Bitmap.getPixels() of the square cover. The file name projectM finds it by. */
    external fun artTextureName(argb: IntArray): String
    /** Main and accent colour of the cover: r, g, b, r, g, b (0..1). */
    external fun artPalette(argb: IntArray, width: Int, height: Int): FloatArray
    /**
     * The .milk text; [palettes] has 6 floats per texture, [params] one value per [ART_PARAMS] entry.
     * [fineBands]: 16 frequency bands (our patched projectM) instead of bass, mid and treble.
     */
    external fun buildArtPreset(textures: Array<String>, palettes: FloatArray, weights: FloatArray, style: Int, params: FloatArray, fineBands: Boolean): String
}
