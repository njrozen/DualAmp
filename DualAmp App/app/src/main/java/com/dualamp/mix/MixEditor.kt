package com.dualamp.mix

import com.dualamp.library.LibraryRepository
import com.dualamp.library.PlaylistStore
import com.dualamp.library.Song
import com.dualamp.playback.PlayerConnection
import com.dualamp.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The transition being edited (mix editor): the bottom screen's settings change [Session.draft], the top screen draws
 * it, and a preview plays it live ([MixEngine.draft]). Nothing is kept until [save].
 */
@androidx.media3.common.util.UnstableApi
object MixEditor {
    class Session(
        val playlistId: String,
        val from: Song,
        val to: Song,
        val saved: Transition?,
        val draft: Transition,
        /** Still the automatic suggestion (redone when the songs' analysis comes in). */
        val auto: Boolean,
    ) {
        val changed: Boolean get() = saved == null || draft != saved
        val a: Analysis? get() = SongAnalyzer[from.id]
        val b: Analysis? get() = SongAnalyzer[to.id]
    }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var jobs: List<Job> = emptyList()

    fun open(playlistId: String, from: Song, to: Song) {
        close()
        val saved = PlaylistStore.playlist(playlistId)?.transitions?.get(Transition.key(from.id, to.id))
        SongAnalyzer.request(listOf(from, to), first = true)
        val draft = saved ?: Transition.auto(from.id, to.id, SongAnalyzer[from.id], SongAnalyzer[to.id], from.durationMs)
        set(Session(playlistId, from, to, saved, draft, auto = saved == null))
        jobs = listOf(
            // The tempo and key arrive: redo the suggestion (if untouched) and the sliders' steps.
            scope.launch {
                SongAnalyzer.results.collect { r ->
                    val s = _session.value ?: return@collect
                    if (s.auto && r[s.from.id] != null && r[s.to.id] != null) {
                        set(Session(s.playlistId, s.from, s.to, s.saved,
                            Transition.auto(s.from.id, s.to.id, r[s.from.id], r[s.to.id], s.from.durationMs, s.draft.preset), auto = true))
                    } else syncSliders(s)
                }
            },
            // The sliders on the settings page.
            scope.launch {
                merge(Prefs.mixEdStart.state, Prefs.mixEdEntry.state).collect { fromSliders() }
            },
            scope.launch {
                combine(Prefs.mixEdEq.map { it.state }) { it.toList() }.collect { fromSliders() }
            },
        )
    }

    fun close() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _session.value = null
        _editing.value = null
        expanded.value = false
        undoStack.clear(); redoStack.clear()
        MixEngine.draft = null
    }

    private fun set(s: Session) {
        _session.value = s
        MixEngine.draft = s.draft
        syncSliders(s)
    }

    /** The page and the waveform redraw (something they show besides the transition changed). */
    private fun refresh() {
        _session.value?.let { s -> set(Session(s.playlistId, s.from, s.to, s.saved, s.draft, s.auto)) }
    }

    // Undo / redo: the transition as it was before each change (changes close together count as one).
    private val undoStack = ArrayDeque<Transition>()
    private val redoStack = ArrayDeque<Transition>()
    private var lastRecordNs = 0L
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun record(before: Transition, force: Boolean) {
        val now = System.nanoTime()
        if (force || now - lastRecordNs > 600_000_000L) {
            undoStack.addLast(before)
            if (undoStack.size > 60) undoStack.removeFirst()
            redoStack.clear()
        }
        lastRecordNs = now
    }

    /** Starts a change that should undo as one step (a dot being dragged). */
    fun checkpoint() {
        val s = _session.value ?: return
        record(s.draft, force = true)
    }

    fun undo() {
        val s = _session.value ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(s.draft)
        lastRecordNs = 0L
        set(Session(s.playlistId, s.from, s.to, s.saved, prev, auto = false))
    }

    fun redo() {
        val s = _session.value ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(s.draft)
        lastRecordNs = 0L
        set(Session(s.playlistId, s.from, s.to, s.saved, next, auto = false))
    }

    /** Changes the transition by hand (the preset becomes "Custom"). [record]: can be undone (false while dragging). */
    fun update(record: Boolean = true, change: (Transition) -> Transition) {
        val s = _session.value ?: return
        val t = change(s.draft).let { if (it == s.draft) return else it.copy(preset = "Custom") }
        if (record) record(s.draft, force = false)
        set(Session(s.playlistId, s.from, s.to, s.saved, t, auto = false))
    }

    fun applyPreset(name: String) {
        val s = _session.value ?: return
        val made = Transition.auto(s.from.id, s.to.id, s.a, s.b, s.from.durationMs, name)
        // Keep where the songs meet if it was chosen by hand.
        val t = if (s.auto) made else made.copy(startMs = s.draft.startMs, entryMs = s.draft.entryMs)
        record(s.draft, force = true)
        set(Session(s.playlistId, s.from, s.to, s.saved, t, auto = s.auto))
    }

    // -----------------------------------------------------------------------------------------
    // Drawing curves on the waveform (Customize): which one is shown with its dots, and changing its points.

    private val _editing = MutableStateFlow<CurveKind?>(null)
    /** The curve shown with dots on the waveform (null: just the transition). */
    val editing: StateFlow<CurveKind?> = _editing.asStateFlow()

    /**
     * One screen: the waveforms were touched, so they get most of the screen and only the settings that go with them show
     * below (customizing a curve does the same). "Show all settings" or B puts it back.
     */
    val expanded = MutableStateFlow(false)

    /** Back to the full settings: stops customizing and shrinks the waveforms. */
    fun collapse() { expanded.value = false; if (_editing.value != null) showCurve(null) }

    fun showCurve(kind: CurveKind?) {
        _editing.value = kind
        refresh()
    }

    /** "Custom" picked for a setting: its curves become hand-drawn (starting from the preset's) and shown with dots. */
    fun customize(kind: CurveKind) {
        val kinds = if (kind.isEq) listOf(CurveKind.LOWS, CurveKind.MIDS, CurveKind.HIGHS) else listOf(kind)
        update { t -> t.copy(custom = t.custom + kinds.filter { it !in t.custom }.associateWith { Automation.preset(t, it) }) }
        showCurve(kind)
    }

    /** A setting's ready-made choice picked: its hand-drawn curves (if any) are dropped. */
    fun choose(kind: CurveKind, change: (Transition) -> Transition) {
        val kinds = if (kind.isEq) setOf(CurveKind.LOWS, CurveKind.MIDS, CurveKind.HIGHS) else setOf(kind)
        update { t -> change(t).let { it.copy(custom = it.custom - kinds) } }
    }

    fun isCustom(t: Transition, kind: CurveKind) =
        if (kind.isEq) t.custom.keys.any { it.isEq } else kind in t.custom

    fun points(kind: CurveKind, outgoing: Boolean): List<CurvePoint> =
        _session.value?.let { Automation.curves(it.draft, kind).lane(outgoing) } ?: emptyList()

    /** Sets one song's points for [kind] (making that setting hand-drawn). */
    fun setPoints(kind: CurveKind, outgoing: Boolean, points: List<CurvePoint>, record: Boolean = true) {
        update(record) { t ->
            val kinds = if (kind.isEq) listOf(CurveKind.LOWS, CurveKind.MIDS, CurveKind.HIGHS) else listOf(kind)
            val filled = t.custom + kinds.filter { it !in t.custom }.associateWith { Automation.preset(t, it) }
            t.copy(custom = filled + (kind to filled.getValue(kind).with(outgoing, points)))
        }
    }

    /** [x] (0..1 across the transition) moved onto song 1's beat or bar lines, going by [snap]. */
    fun snapX(x: Float): Float {
        val s = _session.value ?: return x
        val len = s.draft.lengthMs(s.a?.bpm ?: 0f).toDouble()
        if (len <= 0) return x
        val ms = s.draft.startMs + x * len
        return ((snapped(s.a, ms) - s.draft.startMs) / len).toFloat().coerceIn(0f, 1f)
    }

    // -----------------------------------------------------------------------------------------
    // Where the songs meet. The sliders count in steps of the snap grid: bars or beats of each song (or tenths of a
    // second without a tempo), or 10 ms with snapping off.

    enum class Snap(val label: String) { OFF("Off"), BEATS("Beats"), BARS("Bars") }

    val snap: Snap get() = runCatching { Snap.valueOf(Prefs.mixSnap.value) }.getOrDefault(Snap.BEATS)

    fun setSnap(v: Snap) {
        Prefs.mixSnap.value = v.name
        // The sliders' steps change: redraw the page and set them again.
        _session.value?.let { s -> set(Session(s.playlistId, s.from, s.to, s.saved, s.draft, s.auto)) }
    }

    private fun stepMs(a: Analysis?): Double = when {
        snap == Snap.OFF -> 10.0
        a == null || a.bpm <= 0 -> 100.0
        snap == Snap.BARS -> a.barLengthMs.toDouble()
        else -> a.beatLengthMs.toDouble()
    }
    private fun anchor(a: Analysis?): Double = if (snap == Snap.OFF || a == null || a.bpm <= 0) 0.0 else a.barMs.toDouble()

    /** What one step of the "Song 2 comes in at" / "starts from" sliders is, in words. */
    fun stepName(a: Analysis?): String = when {
        snap == Snap.OFF -> "10 ms"
        a == null || a.bpm <= 0 -> "a tenth of a second"
        snap == Snap.BARS -> "a bar"
        else -> "a beat"
    }

    fun startUnits(s: Session, ms: Long) = ((ms - anchor(s.a)) / stepMs(s.a)).roundToInt()
    fun entryUnits(s: Session, ms: Long) = ((ms - anchor(s.b)) / stepMs(s.b)).roundToInt()
    fun startMsOf(s: Session, units: Int) = (anchor(s.a) + units * stepMs(s.a)).toLong().coerceIn(0, s.from.durationMs)
    fun entryMsOf(s: Session, units: Int) = (anchor(s.b) + units * stepMs(s.b)).toLong().coerceIn(0, s.to.durationMs)

    /** [ms] in a song put on the snap grid (the nearest beat or bar line). */
    fun snapped(a: Analysis?, ms: Double): Double = when {
        a == null || a.bpm <= 0 || snap == Snap.OFF -> ms
        snap == Snap.BARS -> a.snapToBar(ms)
        else -> a.snapToBeat(ms)
    }

    private var syncing = false

    private fun syncSliders(s: Session) {
        syncing = true
        Prefs.mixEdStart.value = startUnits(s, s.draft.startMs)
        Prefs.mixEdEntry.value = entryUnits(s, s.draft.entryMs)
        (s.draft.eqA + s.draft.eqB).forEachIndexed { i, v -> if (Prefs.mixEdEq[i].value != v) Prefs.mixEdEq[i].value = v }
        syncing = false
    }

    private fun fromSliders() {
        if (syncing) return
        val s = _session.value ?: return
        val start = if (startUnits(s, s.draft.startMs) != Prefs.mixEdStart.value) startMsOf(s, Prefs.mixEdStart.value) else s.draft.startMs
        val entry = if (entryUnits(s, s.draft.entryMs) != Prefs.mixEdEntry.value) entryMsOf(s, Prefs.mixEdEntry.value) else s.draft.entryMs
        val eq = Prefs.mixEdEq.map { it.value }
        update { it.copy(startMs = start, entryMs = entry, eqA = eq.take(3), eqB = eq.drop(3)) }
    }

    // Dragging a waveform: where the song would be without snapping, and the snapped point it shows.
    private var dragStart = 0.0
    private var dragEntry = 0.0

    fun beginDrag() {
        val s = _session.value ?: return
        dragStart = s.draft.startMs.toDouble(); dragEntry = s.draft.entryMs.toDouble()
    }

    /**
     * Slides song 1 ([lane] 0: where song 2 comes in) or song 2 (where it starts from) by [ms] of that song, jumping
     * from one grid line to the next as it goes.
     */
    fun drag(lane: Int, ms: Double) {
        val s = _session.value ?: return
        if (lane == 0) {
            dragStart = (dragStart + ms).coerceIn(0.0, (s.from.durationMs - 1000).toDouble())
            val v = snapped(s.a, dragStart).toLong().coerceIn(0, s.from.durationMs - 1000)
            update { it.copy(startMs = v) }
        } else {
            dragEntry = (dragEntry + ms).coerceIn(0.0, (s.to.durationMs - 1000).toDouble())
            val v = snapped(s.b, dragEntry).toLong().coerceIn(0, s.to.durationMs - 1000)
            update { it.copy(entryMs = v) }
        }
    }

    // -----------------------------------------------------------------------------------------

    /** Plays the playlist in mix mode from a little before song 2 comes in, so the transition is heard as it is now. */
    fun preview(secondsBefore: Int = 8) {
        val s = _session.value ?: return
        val lib = LibraryRepository.library.value ?: return
        val p = PlaylistStore.playlist(s.playlistId) ?: return
        val songs = p.songIds.mapNotNull { lib.songById[it] }
        val i = songs.indices.firstOrNull { songs[it].id == s.from.id && songs.getOrNull(it + 1)?.id == s.to.id } ?: return
        PlayerConnection.playSongs(songs, i, playlist = true, mixPlaylistId = p.id,
            startMs = (s.draft.startMs - secondsBefore * 1000L).coerceAtLeast(0))
    }

    fun save() {
        val s = _session.value ?: return
        PlaylistStore.saveTransition(s.playlistId, s.draft)
        _session.value = Session(s.playlistId, s.from, s.to, s.draft, s.draft, auto = false)
    }

    fun remove() {
        val s = _session.value ?: return
        PlaylistStore.removeTransition(s.playlistId, s.draft.pairKey)
        close()
    }

    /** Makes an automatic transition for every pair of songs in the playlist that has none (songs analysed first). */
    fun autoMixAll(playlistId: String, songs: List<Song>, preset: String = "Auto"): Int {
        val p = PlaylistStore.playlist(playlistId) ?: return 0
        val made = songs.zipWithNext().filter { (a, b) -> Transition.key(a.id, b.id) !in p.transitions }.map { (a, b) ->
            Transition.auto(a.id, b.id, SongAnalyzer[a.id], SongAnalyzer[b.id], a.durationMs, preset)
        }
        if (made.isNotEmpty()) PlaylistStore.saveTransitions(playlistId, made)
        return made.size
    }

    /** [autoMixAll] once every song has been read (tempo, key), then tells [done] how many were made. Main thread. */
    fun autoMixAllWhenReady(playlistId: String, songs: List<Song>, done: (Int) -> Unit) {
        SongAnalyzer.request(songs)
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(15 * 60_000L) {
                SongAnalyzer.results.first { r -> songs.all { it.id in r || SongAnalyzer.hasFailed(it.id) } }
            }
            done(autoMixAll(playlistId, songs))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Words for the settings page.

    fun tempoLine(a: Analysis?, b: Analysis?): String {
        if (a == null || b == null) return "Reading the songs' tempo and key…"
        val tempo = if (a.bpm > 0 && b.bpm > 0) {
            // Against song 2's tempo halved or doubled when that is closer (it then mixes in half or double time).
            val m = matchedBpm(a.bpm, b.bpm)
            val diff = (m - a.bpm) / a.bpm * 100
            val time = if (m < b.bpm) ", half time" else if (m > b.bpm) ", double time" else ""
            "${bpm(a)} → ${bpm(b)} BPM (" + (if (kotlin.math.abs(diff) < 0.5) "same tempo" else "%+.0f%%".format(diff)) + time +
                (if (kotlin.math.abs(diff) >= 8) ", far apart" else "") + ")"
        } else "Tempo unclear"
        val key = when (Analysis.keyMatch(a.key, b.key)) {
            2 -> "same key, mix perfectly"
            1 -> "keys mix well"
            0 -> "keys may clash"
            else -> "key unclear"
        }
        return "$tempo · ${a.camelot} → ${b.camelot}: $key"
    }

    fun bpm(a: Analysis) = if (a.bpm <= 0) "?" else if (a.bpm % 1f == 0f) a.bpm.toInt().toString() else "%.1f".format(a.bpm)

    /** "140 BPM · 10B" for a song (or null before it's analysed). */
    fun songTag(id: Long): String? = SongAnalyzer[id]?.let { a -> if (a.bpm > 0) "${bpm(a)} BPM · ${a.camelot}" else a.camelot }

    fun describe(t: Transition, bpmA: Float): String {
        val len = if (t.volume == MixVolume.CUT) "cut" else if (t.bars > 0 && bpmA > 0) "${t.bars} bars" else "${t.seconds.roundToInt()} s"
        val name = if (t.preset != "Custom") t.preset else if (CurveKind.VOLUME in t.custom) "Custom" else t.volume.label
        return "$name · $len"
    }
}
