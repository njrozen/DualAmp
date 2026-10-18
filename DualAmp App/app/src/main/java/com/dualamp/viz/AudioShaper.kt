package com.dualamp.viz

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Shapes the audio the visualizer sees (not what you hear), set by Settings > Visualizer reactivity.
 *
 *  - Evens out the volume: quiet songs move the visuals as much as loud ones.
 *  - Above 100 %, exaggerates beats: the louder a moment is than the last second or so, the more it is
 *    boosted, so presets (which react to bass / mid / treble compared with their recent average) jump harder.
 *    Below 100 %, the same works the other way and the visuals calm down.
 *
 * projectM's own "beat sensitivity" setting is not used by projectM 4.1, so this is where reactivity is set.
 */
internal class AudioShaper {
    /** Follows the audio closely (beats). */
    private var fast = 0f
    /** The level over the last second or so. */
    private var slow = 0.05f

    fun process(buf: FloatArray, frames: Int, percent: Int, sampleRate: Int) {
        val rate = sampleRate.coerceAtLeast(8000).toFloat()
        val fastFall = exp(-1f / (0.06f * rate))
        val slowFall = exp(-1f / (1.2f * rate))
        // 0 at 100 %, up to 2 at 300 %, -0.5 at 50 %.
        val emphasis = (percent - 100) / 100f
        for (f in 0 until frames) {
            val l = buf[f * 2]
            val r = buf[f * 2 + 1]
            val x = maxOf(abs(l), abs(r))
            fast = if (x > fast) x else fast * fastFall + x * (1 - fastFall)
            slow = slow * slowFall + x * (1 - slowFall)
            val level = slow.coerceAtLeast(0.004f)
            var gain = TARGET / level
            if (emphasis != 0f) gain *= (fast / level).coerceIn(0.1f, 10f).pow(emphasis)
            gain = gain.coerceIn(0.1f, MAX_GAIN)
            buf[f * 2] = (l * gain).coerceIn(-1f, 1f)
            buf[f * 2 + 1] = (r * gain).coerceIn(-1f, 1f)
        }
    }

    private companion object {
        /** Average level the volume is brought to (peaks then land near full scale). */
        const val TARGET = 0.25f
        /** Most a quiet passage is boosted, so near-silence is not blown up into noise. */
        const val MAX_GAIN = 8f
    }
}
