package com.dualamp.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.dualamp.library.Library
import com.dualamp.library.LibraryRepository
import com.dualamp.library.Playlist
import com.dualamp.library.PlaylistStore
import com.dualamp.mix.MixEditor
import com.dualamp.mix.CurveKind
import com.dualamp.mix.MixEffect
import com.dualamp.mix.MixEq
import com.dualamp.mix.MixFilter
import com.dualamp.mix.MixLoop
import com.dualamp.mix.MixTempo
import com.dualamp.mix.MixVolume
import com.dualamp.mix.SongAnalyzer
import com.dualamp.mix.Transition
import com.dualamp.playback.PlayerConnection
import com.dualamp.settings.Prefs
import com.dualamp.ui.formatDurationLong
import com.dualamp.ui.formatTime
import com.dualamp.ui.plural

// Mix mode (1.6): a playlist that plays in order with transitions between its songs.

/** Turns a playlist's mix mode on or off. Off, it plays like any playlist; its transitions are kept for later. */
internal fun mixModeEntry(p: Playlist) = Entry(
    "set:mixmode", "Mix mode",
    if (p.mixMode) "On: plays in order, with the transitions between songs" else if (p.isMixed) "Off: plays normally (your transitions are kept)"
    else "Set up transitions between the songs, like a DJ mix",
    Art.Symbol(Icons.Rounded.Tune, "mixmode"), Kind.SETTING, trailing = if (p.mixMode) "On" else "Off",
    action = {
        PlaylistStore.setMixMode(p.id, !p.mixMode)
        // Turned off while it plays as a mix: the rest plays normally.
        if (p.mixMode && Prefs.mixPlaylistId.value == p.id) Prefs.mixPlaylistId.value = ""
        if (!p.mixMode) LibraryRepository.library.value?.let { lib -> SongAnalyzer.request(p.songIds.mapNotNull { lib.songById[it] }) }
    },
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun buildMixPlaylist(p: Playlist, lib: Library, actions: UiActions): PageData {
    val songs = p.songIds.mapNotNull { lib.songById[it] }
    val first = songs.firstOrNull()
    val mixed = p.liveTransitions.size
    // Each song's tempo and key show next to it (read in the background, once).
    SongAnalyzer.request(songs)
    val pairs = (songs.size - 1).coerceAtLeast(0)
    val waiting = songs.count { SongAnalyzer[it.id] == null && !SongAnalyzer.hasFailed(it.id) }
    val entries = ArrayList<Entry>()
    entries += actionEntry("act:playmix", "Play mix", Icons.Rounded.PlayArrow, "In order, with the transitions") {
        PlayerConnection.playSongs(songs, playlist = true, mixPlaylistId = p.id)
    }
    entries += mixModeEntry(p)
    playlistSearchEntry(p, songs.size)?.let { entries += it }
    if (mixed < pairs) entries += actionEntry("act:automix", if (mixed == 0) "Auto-mix the playlist" else "Auto-mix the rest",
        Icons.Rounded.AutoAwesome,
        (if (waiting > 0) "Reads each song's tempo and key first ($waiting to go). " else "") +
            "Makes a transition for the ${plural(pairs - mixed, "pair")} of songs without one") {
        actions.toast(if (waiting > 0) "Reading the songs first, this can take a moment" else "Mixing…")
        MixEditor.autoMixAllWhenReady(p.id, songs) { n -> actions.toast(if (n > 0) "Added ${plural(n, "transition")}" else "Nothing to add") }
    }
    if (mixed > 0) entries += actionEntry("act:unmix", "Remove all transitions", Icons.Rounded.Delete, plural(mixed, "transition")) {
        actions.confirm("Remove all ${plural(mixed, "transition")} from ${p.name}?", "Yes, remove them") { PlaylistStore.removeAllTransitions(p.id) }
    }
    songs.forEachIndexed { i, s ->
        val tag = MixEditor.songTag(s.id)
        entries += songEntry(s).copy(subtitle = listOfNotNull(s.artist, tag).joinToString(" · "))
        val next = songs.getOrNull(i + 1) ?: return@forEachIndexed
        val t = p.transitions[Transition.key(s.id, next.id)]
        entries += Entry(
            key = "mix:${s.id}>${next.id}:$i",
            title = t?.let { "Transition: " + MixEditor.describe(it, SongAnalyzer[s.id]?.bpm ?: 0f) } ?: "Add a transition",
            subtitle = t?.let { "${next.title} comes in at ${formatTime(it.startMs)}" } ?: "Mix ${s.title} into ${next.title}",
            art = Art.Symbol(if (t != null) Icons.Rounded.Tune else Icons.Rounded.Add, "mix$i"),
            kind = Kind.ACTION,
            target = Page.MixEdit(p.id, s.id, next.id),
        )
    }
    return PageData(
        title = p.name,
        hero = Hero(p.name, "Mixed by you · mix mode on",
            "${plural(songs.size, "song")} · ${plural(mixed, "transition")} · ${formatDurationLong(songs.sumOf { it.durationMs })}",
            playlistArt(p, first)),
        entries = entries,
        playable = songs,
    )
}

/** "2:31.4" */
private fun preciseTime(ms: Long): String = formatTime(ms) + ".${(ms % 1000) / 100}"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun buildMixEdit(page: Page.MixEdit, actions: UiActions): PageData {
    val ed = MixEditor
    val s = ed.session.value?.takeIf { it.playlistId == page.playlistId && it.from.id == page.fromId && it.to.id == page.toId }
        ?: return PageData("Transition", emptyList())
    val t = s.draft
    val a = s.a
    val b = s.b
    val bpmA = a?.bpm ?: 0f
    val bpmB = b?.bpm ?: 0f
    fun choice(key: String, title: String, help: String, names: List<String>, current: String, icon: ImageVector = Icons.Rounded.Tune, set: (String) -> Unit) =
        Entry("mixed:$key", title, help, Art.Symbol(icon, key), Kind.SETTING, trailing = current, choices = names, onChoose = set)
    fun eqSlider(i: Int, title: String) = Entry("mixed:eq$i", title, if (i < 3) "Song 1, during the transition" else "Song 2, during the transition",
        Art.Symbol(Icons.Rounded.Equalizer, "eq$i"), Kind.SETTING,
        slider = SliderSpec(Prefs.mixEdEq[i], -24, 6, 1, "dB", minLabel = "Off", label = { v -> if (v > 0) "+$v dB" else "$v dB" }))

    // The curves that can be drawn (the effect's only when there is one).
    val drawable: List<CurveKind?> = listOf(null) + CurveKind.entries.filter { it != CurveKind.EFFECT || t.effect != MixEffect.NONE }
    val lengths = if (bpmA > 0) listOf(1, 2, 4, 8, 16, 32).map { if (it == 1) "1 bar" else "$it bars" } else listOf(4, 8, 12, 16, 24, 32).map { "$it s" }
    val lengthNow = if (t.volume == MixVolume.CUT) "Cut" else if (bpmA > 0) (if (t.bars == 1) "1 bar" else "${t.bars} bars") else "${t.seconds.toInt()} s"
    val entries = listOfNotNull(
        actionEntry("act:mixpreview", "Play the transition", Icons.Rounded.PlayArrow,
            "From 8 s before song 2 comes in, as it is now (saved or not). The playlist plays on as a mix") { ed.preview() },
        choice("preset", "Style", "A ready-made transition. Change anything below to make it your own",
            Transition.PRESETS, t.preset, Icons.Rounded.AutoAwesome) { ed.applyPreset(it) },
        choice("length", "Length", if (bpmA > 0) "How long they play together, in bars of song 1 (4 beats each)" else "How long they play together",
            lengths, lengthNow) { v ->
            val n = v.substringBefore(' ').toIntOrNull() ?: return@choice
            ed.update { if (bpmA > 0) it.copy(bars = n) else it.copy(bars = 0, seconds = n.toFloat()) }
        },
        sectionHeader("mixwhere", "Where they meet"),
        choice("snap", "Snap to the grid", "Moving a song (left / right, or dragging its waveform) lands on its beats, or only on the first beat of its bars, or anywhere",
            MixEditor.Snap.entries.map { it.label }, ed.snap.label, Icons.Rounded.GridOn) { v ->
            MixEditor.Snap.entries.firstOrNull { it.label == v }?.let { ed.setSnap(it) }
        },
        Entry("mixed:start", "Song 2 comes in at", "In song 1. Left / right: ${ed.stepName(a)}. Or drag song 1's waveform",
            Art.Symbol(Icons.Rounded.MusicNote, "start"), Kind.SETTING,
            slider = SliderSpec(Prefs.mixEdStart, ed.startUnits(s, 0), ed.startUnits(s, s.from.durationMs - 1000), 1, "",
                label = { v -> preciseTime(if (v == ed.startUnits(s, t.startMs)) t.startMs else ed.startMsOf(s, v)) })),
        Entry("mixed:entry", "Song 2 starts from", "In song 2. Left / right: ${ed.stepName(b)}. Or drag song 2's waveform",
            Art.Symbol(Icons.Rounded.MusicNote, "entry"), Kind.SETTING,
            slider = SliderSpec(Prefs.mixEdEntry, ed.entryUnits(s, 0), ed.entryUnits(s, s.to.durationMs - 1000), 1, "",
                label = { v -> preciseTime(if (v == ed.entryUnits(s, t.entryMs)) t.entryMs else ed.entryMsOf(s, v)) })),
        sectionHeader("mixsound", "How they blend"),
        choice("volume", "Volume", if (ed.isCustom(t, CurveKind.VOLUME)) "Drawn by you on the waveform" else t.volume.help,
            listOf("Custom") + MixVolume.entries.map { it.label }, if (ed.isCustom(t, CurveKind.VOLUME)) "Custom" else t.volume.label) { v ->
            if (v == "Custom") ed.customize(CurveKind.VOLUME)
            else MixVolume.entries.firstOrNull { it.label == v }?.let { x -> ed.choose(CurveKind.VOLUME) { it.copy(volume = x) } }
        },
        choice("eq", "EQ", if (ed.isCustom(t, CurveKind.LOWS)) "Drawn by you on the waveform (lows, mids and highs)" else t.eq.help,
            listOf("Custom") + MixEq.entries.map { it.label }, if (ed.isCustom(t, CurveKind.LOWS)) "Custom" else t.eq.label, Icons.Rounded.Equalizer) { v ->
            if (v == "Custom") ed.customize(CurveKind.LOWS)
            else MixEq.entries.firstOrNull { it.label == v }?.let { x -> ed.choose(CurveKind.LOWS) { it.copy(eq = x) } }
        },
        choice("filter", "Filter", if (ed.isCustom(t, CurveKind.FILTER)) "Drawn by you on the waveform: up a high pass, down a low pass" else t.filter.help,
            listOf("Custom") + MixFilter.entries.map { it.label }, if (ed.isCustom(t, CurveKind.FILTER)) "Custom" else t.filter.label) { v ->
            if (v == "Custom") ed.customize(CurveKind.FILTER)
            else MixFilter.entries.firstOrNull { it.label == v }?.let { x -> ed.choose(CurveKind.FILTER) { it.copy(filter = x) } }
        },
        choice("effect", "Effects", t.effect.help + if (ed.isCustom(t, CurveKind.EFFECT)) " (how much: drawn by you)" else "",
            MixEffect.entries.map { it.label }, t.effect.label, Icons.Rounded.AutoAwesome) { v ->
            MixEffect.entries.firstOrNull { it.label == v }?.let { x ->
                ed.choose(CurveKind.EFFECT) { it.copy(effect = x) }
                if (x == MixEffect.NONE && ed.editing.value == CurveKind.EFFECT) ed.showCurve(null)
            }
        },
        choice("draw", "Customize on the waveform",
            "Shows a setting's curve with dots on the waveform. Tap the line to add a dot, drag a dot to move it, tap it again to remove it",
            drawable.map { it?.label ?: "Off" }, ed.editing.value?.label ?: "Off", Icons.Rounded.Gesture) { v ->
            val kind = CurveKind.entries.firstOrNull { it.label == v }
            ed.showCurve(kind)
        },
        actionEntry("act:mixundo", "Undo", Icons.AutoMirrored.Rounded.Undo, if (ed.canUndo) "Takes back the last change" else "Nothing to undo") { ed.undo() },
        actionEntry("act:mixredo", "Redo", Icons.AutoMirrored.Rounded.Redo, if (ed.canRedo) "Puts back what was undone" else "Nothing to redo") { ed.redo() },
        choice("loop", "Looping"
, "Song 1 repeats the first bars of the transition instead of playing on",
            MixLoop.entries.map { it.label }, t.loop.label, Icons.Rounded.Refresh) { v ->
            MixLoop.entries.firstOrNull { it.label == v }?.let { x -> ed.update { it.copy(loop = x) } }
        },
        Entry("mixed:beatmatch", "Beatmatching",
            if (bpmA <= 0 || bpmB <= 0) "Needs both songs' tempo (not found yet)"
            else if (com.dualamp.mix.tempoGap(bpmA, bpmB) >= 0.08f) "Song 2 plays on song 1's beat. Their tempos are far apart: one gets much faster or slower"
            else "Song 2 plays on song 1's beat",
            Art.Symbol(Icons.Rounded.Speed, "beatmatch"), Kind.SETTING, trailing = if (t.beatmatch) "On" else "Off",
            action = { ed.update { it.copy(beatmatch = !it.beatmatch) } }),
        if (!t.beatmatch) null else choice("tempo", "Tempo", t.tempo.help, MixTempo.entries.map { it.label }, t.tempo.label, Icons.Rounded.Speed) { v ->
            MixTempo.entries.firstOrNull { it.label == v }?.let { x -> ed.update { it.copy(tempo = x) } }
        },
        sectionHeader("mixeqa", "Song 1 EQ"),
        eqSlider(0, "Lows"), eqSlider(1, "Mids"), eqSlider(2, "Highs"),
        sectionHeader("mixeqb", "Song 2 EQ"),
        eqSlider(3, "Lows"), eqSlider(4, "Mids"), eqSlider(5, "Highs"),
        sectionHeader("mixsave", "Keep it"),
        actionEntry("act:mixsave", if (s.changed) "Save transition" else "Saved", Icons.Rounded.Check,
            if (s.changed) "Kept in the playlist: plays every time it plays as a mix" else "Plays every time the playlist plays as a mix") {
            ed.save(); actions.toast("Transition saved")
        },
        if (s.saved == null) null else actionEntry("act:mixremove", "Remove transition", Icons.Rounded.Delete, "Back to one song simply following the other") {
            actions.confirm("Remove this transition?", "Yes, remove it") { ed.remove(); actions.toast("Transition removed") }
        },
    )
    return PageData(
        title = "Transition",
        hero = Hero("${s.from.title} → ${s.to.title}", "${s.from.artist} → ${s.to.artist}", ed.tempoLine(a, b),
            Art.Cover(s.from.albumId, s.from.id)),
        entries = entries,
    )
}
