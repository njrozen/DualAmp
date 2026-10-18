package com.dualamp.viz

import com.dualamp.settings.Prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/** Commands for the visualizer from either screen, and what it is currently showing. */
object VizControl {
    sealed interface Command {
        data object Next : Command
        data object Previous : Command
        data object Random : Command
        data object SongChanged : Command
        data class Load(val path: String) : Command
    }

    /** How the visualizer moves on to a new preset. Stored in [Prefs.presetMode]. */
    enum class Mode(val label: String) {
        INTERVAL("Every few seconds"), SONG("When the song changes"), LOCKED("Never (locked)");

        companion object {
            fun current(): Mode = runCatching { valueOf(Prefs.presetMode.value) }.getOrDefault(INTERVAL)
        }
    }

    internal val commands = ConcurrentLinkedQueue<Command>()

    private val _currentPreset = MutableStateFlow<PresetManager.Preset?>(null)
    val currentPreset: StateFlow<PresetManager.Preset?> = _currentPreset.asStateFlow()

    private val _overlay = MutableSharedFlow<OverlayRequest>(extraBufferCapacity = 8)
    val overlayRequests: SharedFlow<OverlayRequest> get() = _overlay

    enum class OverlayRequest { SHOW, TOGGLE }

    /** Mode to return to when the lock button is turned off again. */
    private var unlockedMode = Mode.INTERVAL

    fun nextPreset() { commands += Command.Next }
    fun previousPreset() { commands += Command.Previous }
    fun randomPreset() { commands += Command.Random }

    /** L2: the next visualizer, or a random one of the folder (Settings > L2 button). */
    fun leftTrigger() = if (Prefs.l2Random.value) randomPreset() else nextPreset()
    fun loadPreset(preset: PresetManager.Preset) { commands += Command.Load(preset.path) }
    fun songChanged() { commands += Command.SongChanged }

    fun setMode(mode: Mode) {
        if (mode != Mode.LOCKED) unlockedMode = mode
        Prefs.presetMode.value = mode.name
    }

    /** The lock button on the top screen: keep this preset, or go back to the previous mode. */
    fun setLocked(locked: Boolean) {
        val now = Mode.current()
        if (locked) {
            if (now != Mode.LOCKED) unlockedMode = now
            setMode(Mode.LOCKED)
        } else {
            setMode(unlockedMode)
        }
    }

    /** Brings the song info back on the top screen (any button press, track change...). */
    fun pokeOverlay() { _overlay.tryEmit(OverlayRequest.SHOW) }
    fun toggleOverlay() { _overlay.tryEmit(OverlayRequest.TOGGLE) }

    internal fun publishPreset(preset: PresetManager.Preset?) { _currentPreset.value = preset }
}
