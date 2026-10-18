package com.dualamp.audio

import com.dualamp.settings.Prefs

/** Settings for [DspProcessor]. All values live in [Prefs], so the settings page edits them directly. */
object DspSettings {
    /** Centre frequencies of the 10-band equalizer. */
    val bands = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    fun bandLabel(hz: Int) = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"

    /** Equalizer presets, in dB per band. */
    val presets: List<Pair<String, IntArray>> = listOf(
        "Flat" to intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "Bass boost" to intArrayOf(7, 6, 5, 3, 1, 0, 0, 0, 0, 0),
        "Bass reducer" to intArrayOf(-6, -5, -4, -2, -1, 0, 0, 0, 0, 0),
        "Treble boost" to intArrayOf(0, 0, 0, 0, 0, 1, 3, 5, 6, 7),
        "Rock" to intArrayOf(5, 4, 3, 1, -1, -1, 1, 3, 4, 5),
        "Pop" to intArrayOf(-1, 1, 3, 4, 4, 2, 0, -1, -1, -1),
        "Hip-hop" to intArrayOf(6, 5, 2, 3, -1, -1, 1, -1, 2, 3),
        "Electronic" to intArrayOf(5, 4, 1, 0, -2, 2, 1, 1, 4, 5),
        "Jazz" to intArrayOf(4, 3, 1, 2, -2, -2, 0, 1, 3, 4),
        "Classical" to intArrayOf(4, 3, 2, 1, -1, -1, 0, 2, 3, 4),
        "Vocal" to intArrayOf(-2, -3, -3, 1, 4, 4, 3, 1, 0, -2),
        "Loudness" to intArrayOf(6, 4, 0, 0, -2, 0, -1, -4, 5, 2),
    )

    val enabled get() = Prefs.dspEnabled.value
    val eq: List<Prefs.Setting<Int>> get() = Prefs.eqBands

    /** Name of the preset matching the current bands, or "Custom". */
    fun currentPresetName(): String =
        presets.firstOrNull { (_, gains) -> gains.indices.all { gains[it] == eq[it].value } }?.first ?: "Custom"

    fun applyPreset(name: String) {
        val gains = presets.firstOrNull { it.first == name }?.second ?: return
        gains.forEachIndexed { i, g -> eq[i].value = g }
    }

    fun nextPreset() {
        val names = presets.map { it.first }
        val i = names.indexOf(currentPresetName())
        applyPreset(names[(i + 1).mod(names.size)])
    }

    fun resetAll() {
        applyPreset("Flat")
        Prefs.dspPreamp.value = 0
        Prefs.dspBass.value = 0
        Prefs.dspTreble.value = 0
        Prefs.dspBalance.value = 0
        Prefs.dspWidth.value = 100
        Prefs.dspMono.value = false
        Prefs.dspLimiter.value = true
    }

    /** A cheap fingerprint of every setting, so the processor only recomputes filters when one changes. */
    fun signature(): Int {
        var h = if (enabled) 1 else 0
        eq.forEach { h = h * 31 + it.value }
        h = h * 31 + Prefs.dspPreamp.value
        h = h * 31 + Prefs.dspBass.value
        h = h * 31 + Prefs.dspTreble.value
        h = h * 31 + Prefs.dspBalance.value
        h = h * 31 + Prefs.dspWidth.value
        h = h * 31 + (if (Prefs.dspMono.value) 1 else 0)
        h = h * 31 + (if (Prefs.dspLimiter.value) 1 else 0)
        return h
    }
}
