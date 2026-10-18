package com.dualamp.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.dualamp.settings.Prefs

/**
 * The app's colors (Settings > Theme). "Default" is DualAmp's original look. Each part can be changed:
 * the bottom screen's background, text, sub text, accent (selected tabs and buttons, like / shuffle on the
 * top screen) and icons, plus the Cover Flow glow. The top screen's visualizer buttons keep their own color.
 * Values are read through Compose state, so a change shows straight away.
 */
object AppTheme {
    class Background(val s1: Color, val s2: Color, val s3: Color, val light: Boolean = false)

    val backgrounds = linkedMapOf(
        "Dark" to Background(Color(0xFF121212), Color(0xFF1E1E1E), Color(0xFF2A2A2A)),
        "Black" to Background(Color(0xFF000000), Color(0xFF141414), Color(0xFF222222)),
        "Gray" to Background(Color(0xFF26262C), Color(0xFF32323A), Color(0xFF404049)),
        "Navy" to Background(Color(0xFF0E1324), Color(0xFF181F36), Color(0xFF242C48)),
        "White" to Background(Color(0xFFFFFFFF), Color(0xFFF1F1F4), Color(0xFFE2E2E8), light = true),
    )
    val texts = linkedMapOf("White" to Color.White, "Black" to Color(0xFF111111), "Light gray" to Color(0xFFDDDDDD), "Dark gray" to Color(0xFF333333))
    val subTexts = linkedMapOf("Gray" to Color(0xFFB3B3B3), "Dark gray" to Color(0xFF5E5E66), "Light gray" to Color(0xFFD2D2D2), "Accent" to Color.Unspecified)
    val accents = linkedMapOf(
        "Green" to Color(0xFF1DB954), "Purple" to Color(0xFF9B6BFF), "Blue" to Color(0xFF3D8BFF), "Red" to Color(0xFFE53950),
        "Orange" to Color(0xFFFF8A1F), "Pink" to Color(0xFFFF5CA8), "Teal" to Color(0xFF1FC7B6), "Yellow" to Color(0xFFF5C518),
    )
    val icons = linkedMapOf("White" to Color.White, "Black" to Color(0xFF111111), "Gray" to Color(0xFFB3B3B3), "Accent" to Color.Unspecified)
    /** "Auto": white on dark themes, black on the Light theme. */
    val glows = linkedMapOf("Auto" to Color.Unspecified, "White" to Color.White, "Black" to Color.Black, "Dark gray" to Color(0xFF3A3A3A),
        "Accent" to Color.Unspecified, "Purple" to Color(0xFFB388FF), "Blue" to Color(0xFF6FA8FF), "Navy" to Color(0xFF1A2A6C),
        "Red" to Color(0xFFFF5252), "Pink" to Color(0xFFFF7EC0), "Orange" to Color(0xFFFF9E40), "Gold" to Color(0xFFFFD54F),
        "Green" to Color(0xFF4CDB7A), "Teal" to Color(0xFF3DDBC8))

    /** Themes: background, text, sub text and icons (the accent is chosen on its own). */
    val presets = linkedMapOf(
        "Default" to listOf("Dark", "White", "Gray", "White"),
        "Light" to listOf("White", "Black", "Dark gray", "Black"),
        "OLED black" to listOf("Black", "White", "Gray", "White"),
        "Midnight" to listOf("Navy", "White", "Light gray", "White"),
    )

    private val background = mutableStateOf("Dark")
    private val text = mutableStateOf("White")
    private val subText = mutableStateOf("Gray")
    private val accent = mutableStateOf("Green")
    private val icon = mutableStateOf("White")
    private val glow = mutableStateOf("Auto")

    fun load() {
        background.value = Prefs.themeBackground.value
        text.value = Prefs.themeText.value
        subText.value = Prefs.themeSubText.value
        accent.value = Prefs.themeAccent.value
        icon.value = Prefs.themeIcon.value
        glow.value = Prefs.coverGlowColor.value
    }

    fun setBackground(v: String) { Prefs.themeBackground.value = v; background.value = v }
    fun setText(v: String) { Prefs.themeText.value = v; text.value = v }
    fun setSubText(v: String) { Prefs.themeSubText.value = v; subText.value = v }
    fun setAccent(v: String) { Prefs.themeAccent.value = v; accent.value = v }
    fun setIcon(v: String) { Prefs.themeIcon.value = v; icon.value = v }
    fun setGlow(v: String) { Prefs.coverGlowColor.value = v; glow.value = v }
    fun applyPreset(name: String) {
        val p = presets[name] ?: return
        setBackground(p[0]); setText(p[1]); setSubText(p[2]); setIcon(p[3])
    }

    val backgroundName get() = background.value
    val textName get() = text.value
    val subTextName get() = subText.value
    val accentName get() = accent.value
    val iconName get() = icon.value
    val glowName get() = glow.value
    /** The theme in use. */
    val presetName get() = presets.entries.firstOrNull { it.value == listOf(background.value, text.value, subText.value, icon.value) }?.key ?: "Default"

    fun bg(): Background = backgrounds[background.value] ?: backgrounds.getValue("Dark")
    fun accent(): Color = accents[accent.value] ?: accents.getValue("Green")
    fun text(): Color = texts[text.value] ?: Color.White
    fun subText(): Color = (subTexts[subText.value] ?: Color(0xFFB3B3B3)).takeOrAccent()
    fun icon(): Color = (icons[icon.value] ?: Color.White).takeOrAccent()
    fun glow(): Color = when (glow.value) {
        "Auto" -> if (bg().light) Color.Black else Color.White
        else -> (glows[glow.value] ?: Color.White).takeOrAccent()
    }
    private fun Color.takeOrAccent() = if (this == Color.Unspecified) accent() else this
}
