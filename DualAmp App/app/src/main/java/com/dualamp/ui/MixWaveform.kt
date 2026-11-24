package com.dualamp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualamp.mix.Analysis
import com.dualamp.mix.Automation
import com.dualamp.mix.CurveKind
import com.dualamp.mix.CurvePoint
import com.dualamp.mix.Curves
import com.dualamp.mix.MixEditor
import com.dualamp.mix.MixEffect
import com.dualamp.mix.MixEngine
import com.dualamp.mix.SongAnalyzer
import com.dualamp.mix.Transition
import com.dualamp.playback.PlaybackService
import com.dualamp.playback.PlayerConnection
import kotlin.math.abs
import kotlin.math.max

private val LOW = Color(0xFF2448D8)
private val MID = Color(0xFFB8732E)
private val HIGH = Color(0xFFF3E6CF)
private val PLAYHEAD = Color(0xFF2BD15B)
private val BG = Color(0xFF111111)

/** Each curve's colour on the waveform. */
private fun colorOf(kind: CurveKind) = when (kind) {
    CurveKind.VOLUME -> Color(0xFF4FB3E8)
    CurveKind.LOWS -> Color(0xFFF2C230)
    CurveKind.MIDS -> Color(0xFFFF8A5B)
    CurveKind.HIGHS -> Color(0xFF9EE6FF)
    CurveKind.FILTER -> Color(0xFFE58BEA)
    CurveKind.EFFECT -> Color(0xFFD8D8D8)
}

/** Where things are on the waveform (shared by drawing and touch). */
private class Geo(t: Transition, bpmA: Float, val w: Float, val h: Float, val dotPad: Float) {
    val lenMs = t.lengthMs(bpmA).coerceAtLeast(1)
    private val pad = max(lenMs * 0.45, 6000.0)
    val winStart = t.startMs - pad
    val winEnd = t.startMs + lenMs + pad
    // Each song's lane; the gap between them holds the beat ruler ([beatRuler]).
    val laneH = h * 0.40f
    val topB = laneH + (h - laneH * 2)
    /** The gap between the lanes. */
    val gapTop = laneH
    val gapH = topB - laneH
    fun xOf(tA: Double) = ((tA - winStart) / (winEnd - winStart) * w).toFloat()
    val x0 = xOf(t.startMs.toDouble())
    val x1 = xOf((t.startMs + lenMs).toDouble())
    val msPerPx = (winEnd - winStart) / w
    fun top(lane: Int) = if (lane == 0) 0f else topB
    /** A curve height (0..1) in a lane, and back. */
    fun yOf(lane: Int, v: Double) = top(lane) + dotPad + (laneH - 2 * dotPad) * (1 - v.toFloat())
    fun vOf(lane: Int, y: Float) = (1 - (y - top(lane) - dotPad) / (laneH - 2 * dotPad)).coerceIn(0f, 1f)
    fun pxOf(x: Float) = x0 + (x1 - x0) * x
    fun xAt(px: Float) = ((px - x0) / (x1 - x0)).coerceIn(0f, 1f)
}

/** The lanes a curve is drawn in: song 1 (0) and song 2 (1); the effect is song 1's only. */
private fun lanesOf(kind: CurveKind) = if (kind == CurveKind.EFFECT) listOf(0) else listOf(0, 1)

/**
 * The mix editor's picture of a transition: song 1's waveform above song 2's, lined up as they will play (song 2 at
 * song 1's tempo when beatmatched), with the transition's window, beat lines (bars brighter), and the volume (blue),
 * bass (yellow), filter (pink) and effect (grey) curves. Drag a song sideways to slide it under the window (it moves
 * from beat to beat, or bar to bar, or freely: [MixEditor.snap]).
 *
 * Customizing ([MixEditor.editing]): that setting's curves are shown with dots. Tap the line to add a dot, drag a dot to
 * move it, tap a dot to remove it (the end dots only move up and down).
 */
@androidx.media3.common.util.UnstableApi
@Composable
fun MixWaveformView(modifier: Modifier = Modifier) {
    val session = MixEditor.session.collectAsState().value ?: return
    val editing by MixEditor.editing.collectAsState()
    val results by SongAnalyzer.results.collectAsState()
    val a = results[session.from.id]
    val b = results[session.to.id]
    val t = session.draft
    val bpmA = a?.bpm ?: 0f
    val bpmB = b?.bpm ?: 0f
    val ratio = if (t.beatmatch && bpmA > 0 && bpmB > 0) bpmA.toDouble() / com.dualamp.mix.matchedBpm(bpmA, bpmB) else 1.0
    val live by MixEngine.live.collectAsState()
    val playerState by PlayerConnection.state.collectAsState()

    // Where we are in song 1's time, when one of the two songs is playing.
    var playheadA by remember { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(session.from.id, session.to.id, t, live, playerState.songId) {
        while (true) {
            withFrameNanos { }
            val exo = PlaybackService.exo
            val pos = exo?.currentPosition ?: PlayerConnection.positionMs
            val id = playerState.songId
            playheadA = when {
                id == session.from.id -> pos
                id == session.to.id -> (t.startMs + (pos - t.entryMs) / ratio).toLong()
                else -> Long.MIN_VALUE
            }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(BG)) {
    // Not much height (single screen, a phone or the Thor's bottom screen held upright): smaller labels and one info
    // line, so the waveforms keep the room.
    val compact = maxHeight < 330.dp
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp)) {
        SongLabel(session.from.title, session.from.artist, a, "1", compact) {
            // Undo / redo while customizing.
            if (editing != null) Row(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(50)).background(Color(0xE6262626)),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SmallButton(Icons.AutoMirrored.Rounded.Undo, "Undo", MixEditor.canUndo) { MixEditor.undo() }
                SmallButton(Icons.AutoMirrored.Rounded.Redo, "Redo", MixEditor.canRedo) { MixEditor.redo() }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(session.from.id, session.to.id, ratio, editing) { touch(ratio, editing) }
            ) {
                val g = Geo(t, bpmA, size.width, size.height, 9.dp.toPx())
                val w = size.width
                val laneH = g.laneH

                // Song 1 and song 2's waveforms (song 2 on song 1's time axis).
                drawLane(a, 0f, laneH, w) { px -> g.winStart + (g.winEnd - g.winStart) * px / w }
                drawLane(b, g.topB, laneH, w) { px -> t.entryMs + (g.winStart + (g.winEnd - g.winStart) * px / w - t.startMs) * ratio }

                // Beat lines (bars brighter) for each song.
                beatLines(a, 0f, laneH, g::xOf) { beatMs -> beatMs }
                beatLines(b, g.topB, laneH, g::xOf) { beatB -> t.startMs + (beatB - t.entryMs) / ratio }
                // Between them: both songs' beats, meeting in the middle (green where they line up).
                beatRuler(g, a, b) { beatB -> t.startMs + (beatB - t.entryMs) / ratio }

                // Song 1 greyed out after the transition, song 2 before it.
                drawRect(BG.copy(alpha = 0.65f), Offset(g.x1, 0f), Size(w - g.x1, laneH + g.gapH / 2))
                drawRect(BG.copy(alpha = 0.65f), Offset(0f, g.gapTop + g.gapH / 2), Size(g.x0, laneH + g.gapH / 2))

                // The transition window.
                drawRect(colorOf(CurveKind.VOLUME).copy(alpha = 0.5f), Offset(g.x0, 0f), Size(g.x1 - g.x0, size.height), style = Stroke(1.5f.dp.toPx()))

                val kind = editing
                if (kind == null) {
                    // Every move that does something.
                    for (k in CurveKind.entries) {
                        val shown = when (k) {
                            CurveKind.VOLUME -> true
                            CurveKind.EFFECT -> t.effect != MixEffect.NONE
                            else -> k in t.custom || Automation.preset(t, k).let { c -> (c.a + c.b).any { abs(it.y - k.neutral) > 0.01f } }
                        }
                        if (shown) for (lane in lanesOf(k)) drawCurve(g, lane, Automation.curves(t, k).lane(lane == 0), colorOf(k),
                            if (k == CurveKind.VOLUME) 2.5f.dp.toPx() else 2.dp.toPx(), dots = false)
                    }
                } else {
                    if (kind == CurveKind.FILTER) for (lane in 0..1) {
                        val y = g.yOf(lane, 0.5)
                        drawLine(colorOf(kind).copy(alpha = 0.35f), Offset(g.x0, y), Offset(g.x1, y), 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    }
                    for (lane in lanesOf(kind)) drawCurve(g, lane, Automation.curves(t, kind).lane(lane == 0), colorOf(kind), 2.5f.dp.toPx(), dots = true)
                }

                // Where the music is.
                if (playheadA != Long.MIN_VALUE && playheadA in g.winStart.toLong()..g.winEnd.toLong()) {
                    val x = g.xOf(playheadA.toDouble())
                    drawLine(PLAYHEAD, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
                }
            }
            if (a == null || b == null) Text("Reading the songs' tempo and key…", color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp, modifier = Modifier.padding(8.dp))
        }
        // Play / stop the transition: on song 2's label row, so it never covers the waveforms (or the dots being drawn).
        val playingHere = playerState.isPlaying && (playerState.songId == session.from.id || playerState.songId == session.to.id)
        SongLabel(session.to.title, session.to.artist, b, "2", compact) {
            Box(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(50)).background(Color(0xE6333333))) {
                SmallButton(if (playingHere) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (playingHere) "Stop" else "Play the transition", true) {
                    if (playingHere) PlayerConnection.togglePlay() else MixEditor.preview()
                }
            }
        }
        val what = editing?.let { "Customizing ${it.label.lowercase()} · " } ?: ""
        val tempo = MixEditor.tempoLine(a, b)
        val described = what + MixEditor.describe(t, bpmA) + if (session.changed) " · not saved" else ""
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp)) {
            // Short on height: just what the transition is (the tempos and keys are on the song labels).
            if (compact) Text(described, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A phone held upright: one line each, so neither is cut off.
            else if (maxWidth < 560.dp) Column {
                Text(tempo, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(described, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Row(Modifier.fillMaxWidth()) {
                Text(tempo, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(described, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
    }
}

@Composable
private fun SmallButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.3f), modifier = Modifier.size(26.dp))
    }
}

/** What a touch landed on while customizing. */
private sealed class Hit {
    class Dot(val lane: Int, val index: Int) : Hit()
    class Line(val lane: Int) : Hit()
}

/** Touch on the waveform: dots and lines while customizing, otherwise (and off the curves) sliding a song. */
@androidx.media3.common.util.UnstableApi
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.touch(ratio: Double, editing: CurveKind?) {
    val near = 24.dp.toPx()
    val dotPad = 9.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        // (One screen: touching the waveforms makes them bigger; see [MixEditor.expanded].)
        MixEditor.expanded.value = true
        val s = MixEditor.session.value ?: return@awaitEachGesture
        val t = s.draft
        val bpmA = SongAnalyzer[s.from.id]?.bpm ?: 0f
        val g = Geo(t, bpmA, size.width.toFloat(), size.height.toFloat(), dotPad)
        val p0 = down.position
        // A dot, or the line, under the finger?
        var hit: Hit? = null
        if (editing != null && t.lengthMs(bpmA) > 0) {
            var best = near
            for (lane in lanesOf(editing)) {
                Automation.curves(t, editing).lane(lane == 0).forEachIndexed { i, pt ->
                    val d = Offset(g.pxOf(pt.x) - p0.x, g.yOf(lane, pt.y.toDouble()) - p0.y).getDistance()
                    if (d < best) { best = d; hit = Hit.Dot(lane, i) }
                }
            }
            if (hit == null && p0.x in g.x0..g.x1) for (lane in lanesOf(editing)) {
                val pts = Automation.curves(t, editing).lane(lane == 0)
                val y = g.yOf(lane, Curves.eval(pts, g.xAt(p0.x).toDouble()))
                if (abs(y - p0.y) < near) hit = Hit.Line(lane)
            }
        }
        val slideLane = if (p0.y < size.height / 2f) 0 else 1
        if (hit == null) MixEditor.beginDrag()
        var dragging = false
        var moved = Offset.Zero
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) {
                if (!dragging) when (val h = hit) {
                    is Hit.Dot -> removeDot(editing!!, h)
                    is Hit.Line -> addDot(editing!!, h.lane, g.xAt(ch.position.x))
                    null -> Unit
                }
                break
            }
            val delta = ch.positionChange()
            moved += delta
            if (!dragging && moved.getDistance() > viewConfiguration.touchSlop) {
                dragging = true
                if (hit is Hit.Dot) MixEditor.checkpoint()
                // A drag along the line slides the song instead.
                if (hit is Hit.Line) { hit = null; MixEditor.beginDrag() }
            }
            if (!dragging) continue
            ch.consume()
            when (val h = hit) {
                is Hit.Dot -> moveDot(editing!!, h, g, ch.position)
                else -> {
                    val ms = -delta.x * g.msPerPx
                    MixEditor.drag(slideLane, if (slideLane == 0) ms else ms * ratio)
                }
            }
        }
    }
}

@androidx.media3.common.util.UnstableApi
private fun moveDot(kind: CurveKind, h: Hit.Dot, g: Geo, pos: Offset) {
    val pts = MixEditor.points(kind, h.lane == 0).toMutableList()
    if (h.index !in pts.indices) return
    val last = pts.lastIndex
    val x = when (h.index) {
        0 -> 0f
        last -> 1f
        else -> MixEditor.snapX(g.xAt(pos.x)).coerceIn(pts[h.index - 1].x, pts[h.index + 1].x)
    }
    pts[h.index] = CurvePoint(x, g.vOf(h.lane, pos.y))
    MixEditor.setPoints(kind, h.lane == 0, pts, record = false)
}

@androidx.media3.common.util.UnstableApi
private fun removeDot(kind: CurveKind, h: Hit.Dot) {
    val pts = MixEditor.points(kind, h.lane == 0)
    if (h.index <= 0 || h.index >= pts.lastIndex) return // the ends stay
    MixEditor.setPoints(kind, h.lane == 0, pts.filterIndexed { i, _ -> i != h.index })
}

@androidx.media3.common.util.UnstableApi
private fun addDot(kind: CurveKind, lane: Int, x0: Float) {
    val pts = MixEditor.points(kind, lane == 0)
    val x = MixEditor.snapX(x0).coerceIn(0.001f, 0.999f)
    val y = Curves.eval(pts, x.toDouble()).toFloat()
    val i = pts.indexOfFirst { it.x > x }.let { if (it < 0) pts.size else it }
    MixEditor.setPoints(kind, lane == 0, pts.take(i) + CurvePoint(x, y) + pts.drop(i))
}

/** One curve in a lane: straight lines between its points, with the points as dots when customizing. */
private fun DrawScope.drawCurve(g: Geo, lane: Int, pts: List<CurvePoint>, color: Color, width: Float, dots: Boolean) {
    if (pts.isEmpty()) return
    val path = Path()
    pts.forEachIndexed { i, p ->
        val x = g.pxOf(p.x); val y = g.yOf(lane, p.y.toDouble())
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width))
    if (!dots) return
    val r = 9.dp.toPx()
    pts.forEachIndexed { i, p ->
        val c = Offset(g.pxOf(p.x), g.yOf(lane, p.y.toDouble()))
        val end = i == 0 || i == pts.lastIndex
        drawCircle(if (end) BG.copy(alpha = 0.6f) else Color.Black, r, c)
        drawCircle(color, r, c, style = Stroke(if (end) 2.dp.toPx() else 3.dp.toPx()))
    }
}

@Composable
private fun SongLabel(title: String, artist: String, a: Analysis?, n: String, compact: Boolean = false, middle: @Composable () -> Unit = {}) {
    val big = if (compact) 13.sp else 15.sp
    val small = if (compact) 12.sp else 14.sp
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f)) {
            Text("$n  $title", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = big, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("  $artist", color = Color.White.copy(alpha = 0.6f), fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
        }
        middle()
        if (a != null) Text((if (a.bpm > 0) "${MixEditor.bpm(a)} BPM  " else "") + a.camelot + " (${a.keyName})",
            color = Color.White.copy(alpha = 0.85f), fontSize = small, maxLines = 1)
        Spacer(Modifier.padding(0.dp))
    }
}

/** One song's three-band waveform, mirrored around the lane's middle: lows (blue) outside, then mids, highs inside. */
private fun DrawScope.drawLane(a: Analysis?, top: Float, h: Float, w: Float, songMsAt: (Float) -> Double) {
    drawRect(Color.White.copy(alpha = 0.04f), Offset(0f, top), Size(w, h))
    if (a == null) return
    val mid = top + h / 2
    val n = a.low.size
    val step = 2f
    var px = 0f
    while (px < w) {
        val t0 = songMsAt(px); val t1 = songMsAt(px + step)
        val i0 = (minOf(t0, t1) * Analysis.WAVE_RATE / 1000).toInt()
        val i1 = (max(t0, t1) * Analysis.WAVE_RATE / 1000).toInt().coerceAtLeast(i0 + 1)
        if (i1 > 0 && i0 < n) {
            var lo = 0; var mi = 0; var hi = 0
            for (i in i0.coerceAtLeast(0) until i1.coerceAtMost(n)) {
                lo = max(lo, a.low[i].toInt() and 255); mi = max(mi, a.mid[i].toInt() and 255); hi = max(hi, a.high[i].toInt() and 255)
            }
            val hl = h / 2 * lo / 255f; val hm = h / 2 * mi / 255f * 0.72f; val hh = h / 2 * hi / 255f * 0.38f
            drawLine(LOW, Offset(px, mid - hl), Offset(px, mid + hl), strokeWidth = step)
            drawLine(MID, Offset(px, mid - hm), Offset(px, mid + hm), strokeWidth = step)
            drawLine(HIGH, Offset(px, mid - hh), Offset(px, mid + hh), strokeWidth = step)
        }
        px += step
    }
}

private inline fun DrawScope.beatLines(a: Analysis?, top: Float, h: Float, xOf: (Double) -> Float, toA: (Double) -> Double) {
    if (a == null || a.bpm <= 0) return
    val beat = a.beatLengthMs.toDouble()
    val first = a.barMs - Math.floor(a.barMs / beat) * beat
    var k = 0
    var tB = first
    val end = a.durationMs.toDouble()
    while (tB < end) {
        val x = xOf(toA(tB))
        if (x in 0f..size.width) {
            val bar = a.isBarLine(tB)
            drawLine(Color.White.copy(alpha = if (bar) 0.36f else 0.14f), Offset(x, top), Offset(x, top + h), strokeWidth = if (bar) 2.dp.toPx() else 1.dp.toPx())
        }
        tB += beat
        if (++k > 20000) break
    }
}

/** A beat line of one song on song 1's time axis: where it is (px) and whether it starts a bar. */
private class Tick(val x: Float, val bar: Boolean)

private inline fun ticks(a: Analysis?, w: Float, xOf: (Double) -> Float, toA: (Double) -> Double): List<Tick> {
    if (a == null || a.bpm <= 0) return emptyList()
    val beat = a.beatLengthMs.toDouble()
    var tB = a.barMs - Math.floor(a.barMs / beat) * beat
    val end = a.durationMs.toDouble()
    val out = ArrayList<Tick>()
    var k = 0
    while (tB < end && k++ < 20000) {
        val x = xOf(toA(tB))
        if (x in -2f..w + 2f) out += Tick(x, a.isBarLine(tB))
        tB += beat
    }
    return out
}

private val TICK_A = Color(0xFF64B5F6)
private val TICK_B = Color(0xFFFFB74D)
private val TICK_SYNC = Color(0xFF69F0AE)

/**
 * The beat ruler between the two waveforms: song 1's beats hang from the top of the gap, song 2's rise from the bottom,
 * so you can see whether they meet. Bar lines are thick, beat lines thin. A line with one of the other song's lines at
 * the same spot turns green on both (a bar on a bar brighter); otherwise song 1's are blue and song 2's orange. Each
 * song's lines are dimmer where it isn't heard (song 1 after the transition, song 2 before it).
 */
private fun DrawScope.beatRuler(g: Geo, a: Analysis?, b: Analysis?, toAFromB: (Double) -> Double) {
    val w = size.width
    val ta = ticks(a, w, g::xOf) { it }
    val tb = ticks(b, w, g::xOf, toAFromB)
    if (ta.isEmpty() && tb.isEmpty()) return
    // Lined up: within a few pixels (or 4 % of a beat, if that is wider).
    val beatPx = if (ta.size > 1) abs(ta[1].x - ta[0].x) else 40f
    val tol = max(3.dp.toPx(), beatPx * 0.04f)
    fun match(x: Float, other: List<Tick>): Tick? {
        // The lists are in order: a binary search for the nearest.
        var lo = 0; var hi = other.lastIndex
        while (lo < hi) { val m = (lo + hi) / 2; if (other[m].x < x) lo = m + 1 else hi = m }
        var best: Tick? = null
        for (i in (lo - 1)..(lo)) { val o = other.getOrNull(i) ?: continue; if (abs(o.x - x) <= tol && (best == null || abs(o.x - x) < abs(best.x - x))) best = o }
        return best
    }
    val top = g.gapTop
    val h = g.gapH
    val mid = top + h / 2
    // A faint centre line, and the gap's background.
    drawRect(Color.White.copy(alpha = 0.04f), Offset(0f, top), Size(w, h))
    drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, mid), Offset(w, mid), 1f)
    fun draw(list: List<Tick>, other: List<Tick>, own: Color, fromTop: Boolean, heardFrom: Float, heardTo: Float) {
        for (t in list) {
            val m = match(t.x, other)
            val color = when {
                m != null && t.bar && m.bar -> TICK_SYNC
                m != null -> TICK_SYNC.copy(alpha = 0.75f)
                else -> own.copy(alpha = if (t.bar) 0.95f else 0.7f)
            }.let { c -> if (t.x in heardFrom..heardTo) c else c.copy(alpha = c.alpha * 0.35f) }
            // Bars reach right across to the middle line; beats go part way.
            val len = if (t.bar) h / 2 else h * 0.3f
            val width = if (t.bar) 3.dp.toPx() else 1.2f.dp.toPx()
            if (fromTop) drawLine(color, Offset(t.x, top), Offset(t.x, top + len), width)
            else drawLine(color, Offset(t.x, top + h), Offset(t.x, top + h - len), width)
        }
    }
    draw(ta, tb, TICK_A, fromTop = true, heardFrom = -10f, heardTo = g.x1)
    draw(tb, ta, TICK_B, fromTop = false, heardFrom = g.x0, heardTo = w + 10f)
}
