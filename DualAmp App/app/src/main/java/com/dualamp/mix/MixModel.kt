package com.dualamp.mix

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Song 2's tempo as it gets matched to song 1's: halved or doubled when that is closer (a 70 BPM song beatmatches a 140
 * BPM one, every other beat).
 */
fun matchedBpm(bpmA: Float, bpmB: Float): Float {
    if (bpmA <= 0 || bpmB <= 0) return bpmB
    return listOf(bpmB / 2, bpmB, bpmB * 2).minBy { kotlin.math.abs(kotlin.math.ln(bpmA / it)) }
}

/** How far apart the tempos are (0.02 = 2 %), after [matchedBpm]. */
fun tempoGap(bpmA: Float, bpmB: Float): Float =
    if (bpmA <= 0 || bpmB <= 0) 1f else kotlin.math.abs(matchedBpm(bpmA, bpmB) / bpmA - 1)

/** A point of a curve: [x] across the transition (0 = start, 1 = end) and [y] from 0 to 1 (see [CurveKind]). */
data class CurvePoint(val x: Float, val y: Float)

/**
 * What can be drawn as a curve over a transition, and what its height (y, 0..1) means: Volume (0 = silent, 1 = full),
 * Lows / Mids / Highs (1 = as is, 0 = cut out), Filter (0.5 = none; up a high-pass, down a low-pass, fully at the
 * edges), Effect (how much of song 1 goes into the echo or reverb).
 */
enum class CurveKind(val label: String, val neutral: Float) {
    VOLUME("Volume", 1f), LOWS("Lows", 1f), MIDS("Mids", 1f), HIGHS("Highs", 1f), FILTER("Filter", 0.5f), EFFECT("Effect", 0f);

    val isEq: Boolean get() = this == LOWS || this == MIDS || this == HIGHS
}

/** Song 1's ([a]) and song 2's ([b]) curve for one setting: points in order of x (two at the same x make a step). */
data class Curves(val a: List<CurvePoint>, val b: List<CurvePoint>) {
    fun lane(outgoing: Boolean) = if (outgoing) a else b
    fun with(outgoing: Boolean, points: List<CurvePoint>) = if (outgoing) copy(a = points) else copy(b = points)

    companion object {
        fun of(a: String, b: String) = Curves(parse(a), parse(b))
        fun flat(y: Float) = Curves(listOf(CurvePoint(0f, y), CurvePoint(1f, y)), listOf(CurvePoint(0f, y), CurvePoint(1f, y)))

        fun parse(s: String): List<CurvePoint> = s.split(';').mapNotNull { p ->
            val xy = p.split(':'); val x = xy.getOrNull(0)?.trim()?.toFloatOrNull(); val y = xy.getOrNull(1)?.trim()?.toFloatOrNull()
            if (x == null || y == null) null else CurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }.sortedBy { it.x }

        fun format(p: List<CurvePoint>) = p.joinToString(";") { "${it.x}:${it.y}" }

        /** The curve's height at [x] (straight lines between the points). */
        fun eval(p: List<CurvePoint>, x: Double): Double {
            if (p.isEmpty()) return 0.0
            if (x <= p[0].x) return p[0].y.toDouble()
            for (i in 1 until p.size) {
                val q = p[i]
                if (x < q.x) {
                    val o = p[i - 1]; val span = q.x - o.x
                    return if (span <= 0f) q.y.toDouble() else o.y + (q.y - o.y) * (x - o.x) / span
                }
            }
            return p.last().y.toDouble()
        }
    }
}

private const val COS = "0:1;0.25:0.924;0.5:0.707;0.75:0.383;1:0"
private const val SIN = "0:0;0.25:0.383;0.5:0.707;0.75:0.924;1:1"
private const val FULL = "0:1;1:1"

/** How the two songs' volumes move during a transition. */
enum class MixVolume(val label: String, val help: String, val curves: Curves) {
    CROSSFADE("Smooth crossfade", "One fades out as the other fades in", Curves.of(COS, SIN)),
    OVERLAP("Overlap", "Both play at full volume; song 1 stops right at the end", Curves.of(FULL, FULL)),
    FADE_IN_FADE_OUT("Fade in fade out", "Song 2 fades in over the first half, then song 1 fades out over the second",
        Curves.of("0:1;0.5:1;1:0", "0:0;0.5:1;1:1")),
    FADE_OUT("Cut in fade out", "Song 2 comes in at full volume; song 1 fades out", Curves.of(COS, FULL)),
    FADE_IN("Fade in cut out", "Song 2 fades in under song 1, which stops right at the end", Curves.of(FULL, SIN)),
    CUT("Cut", "Straight from song 1 to song 2", Curves.of("0:0;1:0", FULL)),
}

private fun swap(at: Float) = Curves.of("0:1;$at:1;$at:0;1:0", "0:0;$at:0;$at:1;1:1")

/** Low/mid/high moves during a transition. */
enum class MixEq(val label: String, val help: String, private val lows: Curves?, private val mids: Curves? = null, private val highs: Curves? = mids) {
    NONE("None", "Both songs keep their sound", null),
    BASS_SWAP("Bass swap", "Song 2's bass stays out until halfway, then song 1's bass goes", swap(0.5f)),
    START_BASS_SWAP("Start bass swap", "The bass changes over near the start", swap(0.125f)),
    END_BASS_SWAP("End bass swap", "Song 1 keeps the bass until near the end", swap(0.875f)),
    BASS_AND_MID_SWAP("Bass and mid swap", "Halfway, song 1's lows and mids go and song 2's come in", swap(0.5f), swap(0.5f), null),
    BLEND("EQ blend", "Song 1's lows, mids and highs slowly hand over to song 2's",
        Curves.of("0:1;0.25:0.844;0.5:0.5;0.75:0.156;1:0", "0:0;0.25:0.156;0.5:0.5;0.75:0.844;1:1"),
        Curves.of("0:1;0.25:0.953;0.5:0.85;0.75:0.747;1:0.7", "0:0.7;0.25:0.747;0.5:0.85;0.75:0.953;1:1"));

    private val FLAT = Curves.flat(1f)
    fun curves(kind: CurveKind): Curves = when (kind) {
        CurveKind.LOWS -> lows
        CurveKind.MIDS -> mids
        CurveKind.HIGHS -> highs
        else -> null
    } ?: FLAT
}

/** Filters on the way in (song 2) and out (song 1). */
enum class MixFilter(val label: String, val help: String, inn: Int, out: Int) {
    NONE("None", "No filter", 0, 0),
    HIGH_PASS_OUT("High pass filter out", "Song 1 thins out (bass first) as it leaves", 0, 1),
    LOW_PASS_OUT("Low pass filter out", "Song 1 gets muffled as it leaves", 0, -1),
    HIGH_PASS_IN("High pass filter in", "Song 2 starts thin and fills out", 1, 0),
    LOW_PASS_IN("Low pass filter in", "Song 2 starts muffled and opens up", -1, 0),
    HP_IN_HP_OUT("High pass filter in high pass filter out", "Song 2 starts thin and fills out, then song 1 thins out", 1, 1),
    LP_IN_LP_OUT("Low pass filter in low pass filter out", "Song 2 starts muffled and opens up, then song 1 gets muffled", -1, -1),
    LP_IN_HP_OUT("Low pass filter in high pass filter out", "Song 2 starts muffled and opens up, then song 1 thins out", -1, 1),
    HP_IN_LP_OUT("High pass filter in low pass filter out", "Song 2 starts thin and fills out, then song 1 gets muffled", 1, -1);

    /** The filter sweeps over song 1's second half and song 2's first half. */
    val curves = Curves.of(
        when (out) { 1 -> "0:0.5;0.5:0.5;1:1"; -1 -> "0:0.5;0.5:0.5;1:0"; else -> "0:0.5;1:0.5" },
        when (inn) { 1 -> "0:1;0.5:0.5;1:0.5"; -1 -> "0:0;0.5:0.5;1:0.5"; else -> "0:0.5;1:0.5" },
    )
}

/**
 * Effects on song 1 as it leaves: a reverb, an echo (repeats that darken) or a delay (plain repeats) every [beats]
 * beats. Song 1 goes into the effect until the end (or halfway, [atEnd] false), fading out into it or stopping there
 * ([cut]); after that only the effect rings on.
 */
enum class MixEffect(val label: String, val type: Type, val beats: Double = 0.0, val atEnd: Boolean = true, val cut: Boolean = false) {
    NONE("None", Type.NONE),
    REVERB_OUT_CENTER("Reverb out center", Type.REVERB, atEnd = false),
    REVERB_CUT_END("Reverb cut end", Type.REVERB, cut = true),
    REVERB_OUT_END("Reverb out end", Type.REVERB),
    ECHO_HALF_CUT_END("Echo ½ cut end", Type.ECHO, 0.5, cut = true),
    ECHO_HALF_OUT_END("Echo ½ out end", Type.ECHO, 0.5),
    ECHO_34_CUT_END("Echo ¾ cut end", Type.ECHO, 0.75, cut = true),
    ECHO_OUT("Echo ¾ out end", Type.ECHO, 0.75),
    ECHO_1_CUT_END("Echo 1 cut end", Type.ECHO, 1.0, cut = true),
    ECHO_1_OUT_END("Echo 1 out end", Type.ECHO, 1.0),
    DELAY_HALF_CUT_END("Delay ½ cut end", Type.DELAY, 0.5, cut = true),
    DELAY_34_CUT_END("Delay ¾ cut end", Type.DELAY, 0.75, cut = true),
    DELAY_1_CUT_END("Delay 1 cut end", Type.DELAY, 1.0, cut = true);

    enum class Type { NONE, REVERB, ECHO, DELAY }

    /** Where song 1 is gone: the end (1), or halfway. */
    val point: Double get() = if (atEnd) 1.0 else 0.5

    val help: String get() {
        if (type == Type.NONE) return "No effect"
        val every = when (beats) { 0.5 -> "½"; 0.75 -> "¾"; else -> "1" }
        val what = when (type) { Type.REVERB -> "a reverb"; Type.ECHO -> "an echo every $every beat"; else -> "a delay every $every beat" }
        return "Song 1 goes into $what and " + (if (cut) "stops" else "fades out") + (if (atEnd) " at the end" else " halfway") +
            "; the effect rings on"
    }

    /** How much of song 1 goes into the effect: rising over the half before its point. */
    // Made once (it is read for every block of audio).
    val curves: Curves by lazy { if (type == Type.NONE) Curves.flat(0f) else Curves(
        Curves.parse(if (atEnd) "0:0;0.5:0;1:1" else "0:0;0.5:1;1:1"), Curves.parse("0:0;1:0")) }

    /** Song 1's own sound at [x]: on until the point ([cut]), or fading out over the half before it. */
    fun dry(x: Double): Double {
        if (type == Type.NONE) return 1.0
        return if (cut) (if (x < point) 1.0 else 0.0) else ((point - x) / 0.5).coerceIn(0.0, 1.0)
    }

    /** How long the effect rings on after song 1 has gone (ms), for a beat of [beatMs]. */
    fun tailMs(beatMs: Double): Long = when (type) {
        Type.NONE -> 0L
        Type.REVERB -> 2600L
        Type.ECHO -> (beats * beatMs * 7).toLong().coerceIn(1200L, 3500L)
        Type.DELAY -> (beats * beatMs * 5).toLong().coerceIn(1000L, 3000L)
    }
}

enum class MixLoop(val label: String, val bars: Int) {
    NONE("None", 0), ONE("Loop 1 bar", 1), TWO("Loop 2 bars", 2), FOUR("Loop 4 bars", 4),
}

enum class MixTempo(val label: String, val help: String) {
    B_TO_A("Song 2 matches song 1", "Song 2 plays at song 1's tempo, then eases back to its own after the transition"),
    A_TO_B("Ease into song 2's tempo", "Song 2 starts at song 1's tempo; both ease to song 2's own tempo by the end"),
}

/**
 * A transition from song [fromId] into song [toId] (next to each other in a playlist): song 2 comes in at [startMs]
 * of song 1, from [entryMs] of song 2, and the two play together for [bars] bars (of song 1's tempo), or
 * [seconds] when the tempo isn't known.
 */
data class Transition(
    val fromId: Long,
    val toId: Long,
    val startMs: Long,
    val entryMs: Long,
    val bars: Int = 8,
    val seconds: Float = 10f,
    val volume: MixVolume = MixVolume.CROSSFADE,
    val eq: MixEq = MixEq.BASS_SWAP,
    val filter: MixFilter = MixFilter.NONE,
    val effect: MixEffect = MixEffect.NONE,
    val loop: MixLoop = MixLoop.NONE,
    val beatmatch: Boolean = true,
    // Song 2 plays at song 1's tempo (the default; beatmatching is on whenever both tempos are known).
    val tempo: MixTempo = MixTempo.B_TO_A,
    /** Extra EQ (dB) for song 1 and song 2 during the transition: lows, mids, highs. */
    val eqA: List<Int> = listOf(0, 0, 0),
    val eqB: List<Int> = listOf(0, 0, 0),
    /** The preset these settings came from ("Custom" once changed by hand). */
    val preset: String = "Custom",
    /** Curves drawn by hand (Customize), in place of the chosen Volume / EQ / Filter / Effect's own. */
    val custom: Map<CurveKind, Curves> = emptyMap(),
) {
    val pairKey: String get() = key(fromId, toId)

    /** Length in song 1's time. */
    fun lengthMs(bpmA: Float): Long =
        if (volume == MixVolume.CUT) 0L
        else if (bars > 0 && bpmA > 0) (bars * 4 * 60_000.0 / bpmA).toLong()
        else (seconds * 1000).toLong()

    fun toJson(): JSONObject = JSONObject().apply {
        put("from", fromId); put("to", toId); put("start", startMs); put("entry", entryMs)
        put("bars", bars); put("seconds", seconds.toDouble())
        put("volume", volume.name); put("eq", eq.name); put("filter", filter.name); put("effect", effect.name)
        put("loop", loop.name); put("beatmatch", beatmatch); put("tempo", tempo.name)
        put("eqA", eqA.joinToString(",")); put("eqB", eqB.joinToString(",")); put("preset", preset)
        if (custom.isNotEmpty()) put("curves", JSONObject().apply {
            custom.forEach { (k, c) -> put(k.name, JSONObject().put("a", Curves.format(c.a)).put("b", Curves.format(c.b))) }
        })
    }

    companion object {
        fun key(from: Long, to: Long) = "$from>$to"

        private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
            runCatching { enumValueOf<E>(name!!) }.getOrDefault(default)

        private fun ints(s: String?) = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 } ?: listOf(0, 0, 0)

        fun fromJson(o: JSONObject) = Transition(
            fromId = o.getLong("from"), toId = o.getLong("to"), startMs = o.getLong("start"), entryMs = o.getLong("entry"),
            bars = o.optInt("bars", 8), seconds = o.optDouble("seconds", 10.0).toFloat(),
            volume = enumOf(o.optString("volume"), MixVolume.CROSSFADE), eq = enumOf(o.optString("eq"), MixEq.NONE),
            filter = enumOf(o.optString("filter"), MixFilter.NONE), effect = enumOf(o.optString("effect"), MixEffect.NONE),
            loop = enumOf(o.optString("loop"), MixLoop.NONE), beatmatch = o.optBoolean("beatmatch", true),
            tempo = enumOf(o.optString("tempo"), MixTempo.B_TO_A),
            eqA = ints(o.optString("eqA")), eqB = ints(o.optString("eqB")), preset = o.optString("preset", "Custom"),
            custom = o.optJSONObject("curves")?.let { c ->
                c.keys().asSequence().mapNotNull { k ->
                    val kind = runCatching { CurveKind.valueOf(k) }.getOrNull() ?: return@mapNotNull null
                    val v = c.optJSONObject(k) ?: return@mapNotNull null
                    val a = Curves.parse(v.optString("a")); val b = Curves.parse(v.optString("b"))
                    if (a.size < 2 || b.size < 2) null else kind to Curves(a, b)
                }.toMap()
            } ?: emptyMap(),
        )

        val PRESETS = listOf("Auto", "Smooth", "Bass swap", "Rise", "Fade", "Echo out", "Quick cut")

        /**
         * A ready-made transition: song 2 comes in [bars] bars before the end of song 1's music (on a beat), from
         * song 2's first beat.
         */
        fun auto(fromId: Long, toId: Long, a: Analysis?, b: Analysis?, durationA: Long, preset: String = "Auto"): Transition {
            val bars = when (preset) { "Quick cut" -> 1; "Rise", "Echo out" -> 8; "Smooth", "Fade" -> 16; else -> 8 }
            val base = Transition(fromId, toId, 0, 0, bars = bars, preset = preset)
            val styled = when (preset) {
                "Smooth" -> base.copy(volume = MixVolume.CROSSFADE, eq = MixEq.BLEND)
                "Bass swap" -> base.copy(volume = MixVolume.OVERLAP, eq = MixEq.BASS_SWAP)
                "Rise" -> base.copy(volume = MixVolume.FADE_IN, eq = MixEq.END_BASS_SWAP, filter = MixFilter.HIGH_PASS_IN)
                "Fade" -> base.copy(volume = MixVolume.FADE_OUT, eq = MixEq.NONE, filter = MixFilter.LOW_PASS_OUT)
                "Echo out" -> base.copy(volume = MixVolume.CROSSFADE, eq = MixEq.BASS_SWAP, effect = MixEffect.ECHO_OUT)
                "Quick cut" -> base.copy(volume = MixVolume.CROSSFADE, eq = MixEq.NONE)
                else -> base.copy(volume = MixVolume.CROSSFADE, eq = MixEq.BASS_SWAP, filter = MixFilter.HIGH_PASS_OUT)
            }
            val len = styled.lengthMs(a?.bpm ?: 0f)
            // Leave the last few seconds (often a fade or silence) out.
            val musicEnd = (a?.let { lastLoud(it) } ?: durationA) - 2_000
            var start = (musicEnd - len).coerceAtLeast(durationA / 3)
            if (a != null && a.bpm > 0) {
                // On the first beat of a bar.
                val bar = a.barLengthMs.toDouble()
                start = (a.barMs + Math.floor((start - a.barMs) / bar) * bar).toLong().coerceAtLeast(0)
            }
            val entry = b?.let { firstLoud(it) }?.let { e -> if (b.bpm > 0) b.snapToBar(e.toDouble()).let { if (it < 0) it + b.barLengthMs else it }.toLong() else e } ?: 0L
            return styled.copy(startMs = start, entryMs = entry, beatmatch = a != null && b != null && a.bpm > 0 && b.bpm > 0)
        }

        private fun lastLoud(a: Analysis): Long {
            val w = a.low.indices.reversed().firstOrNull { i -> (a.low[i].toInt() and 255) + (a.mid[i].toInt() and 255) > 120 } ?: a.low.size
            return w * 1000L / Analysis.WAVE_RATE
        }

        private fun firstLoud(a: Analysis): Long {
            val w = a.low.indices.firstOrNull { i -> (a.low[i].toInt() and 255) + (a.mid[i].toInt() and 255) > 120 } ?: 0
            return w * 1000L / Analysis.WAVE_RATE
        }
    }
}

/** What one song's sound is at a moment of a transition (see [Automation]). */
class DeckParams {
    var gain = 1.0
    /** dB for lows, mids, highs. */
    var low = 0.0; var mid = 0.0; var high = 0.0
    /** High-pass and low-pass cutoffs in Hz (20 / 20000 = off). */
    var hpHz = 20.0; var lpHz = 20_000.0
    /** How much of the song goes into the echo / delay, and into the reverb (0 = none). */
    var echo = 0.0
    var reverb = 0.0

    fun neutral(): DeckParams { gain = 1.0; low = 0.0; mid = 0.0; high = 0.0; hpHz = 20.0; lpHz = 20_000.0; echo = 0.0; reverb = 0.0; return this }
}

/**
 * The shape of a transition: for a point [x] (0 = start, 1 = end) what song 1 ([outgoing]) or song 2 sounds like.
 * Before the start song 2 is silent; after the end song 1 is (its effect still rings on).
 */
object Automation {
    const val KILL = -30.0
    private fun smooth(x: Double) = x * x * (3 - 2 * x)
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    /** Exponential sweep between two frequencies. */
    private fun sweep(from: Double, to: Double, t: Double) = from * (to / from).pow(t)

    /** The ready-made curves of the transition's chosen Volume / EQ / Filter / Effect. */
    fun preset(t: Transition, kind: CurveKind): Curves = when (kind) {
        CurveKind.VOLUME -> t.volume.curves
        CurveKind.LOWS, CurveKind.MIDS, CurveKind.HIGHS -> t.eq.curves(kind)
        CurveKind.FILTER -> t.filter.curves
        CurveKind.EFFECT -> t.effect.curves
    }

    /** The curves in use: drawn by hand, or the preset's. */
    fun curves(t: Transition, kind: CurveKind): Curves = t.custom[kind] ?: preset(t, kind)

    fun value(t: Transition, kind: CurveKind, outgoing: Boolean, x: Double): Double = Curves.eval(curves(t, kind).lane(outgoing), x)

    /** EQ curve height to dB (1 = 0 dB, 0 = cut out). */
    fun eqDb(y: Double): Double = (1 - y) * KILL

    /** Filter curve height to cutoffs: (high-pass, low-pass) Hz. */
    fun filterHz(y: Double): Pair<Double, Double> = when {
        y > 0.502 -> sweep(20.0, 3000.0, ((y - 0.5) * 2).coerceIn(0.0, 1.0)) to 20_000.0
        y < 0.498 -> 20.0 to sweep(20_000.0, 200.0, ((0.5 - y) * 2).coerceIn(0.0, 1.0))
        else -> 20.0 to 20_000.0
    }

    private fun eqStep(db: Int): Double = if (db <= -24) KILL else db.toDouble()

    fun params(t: Transition, outgoing: Boolean, x: Double, out: DeckParams): DeckParams {
        out.neutral()
        if (x < 0) { if (!outgoing) out.gain = 0.0; return out }
        if (x >= 1) { if (outgoing) out.gain = 0.0; return out }
        out.gain = value(t, CurveKind.VOLUME, outgoing, x)
        out.low = eqDb(value(t, CurveKind.LOWS, outgoing, x))
        out.mid = eqDb(value(t, CurveKind.MIDS, outgoing, x))
        out.high = eqDb(value(t, CurveKind.HIGHS, outgoing, x))
        // The song's own EQ; the sliders' lowest step (-24, "Off") cuts the band. (Runs on the audio thread for every
        // block: nothing here allocates.)
        val eq = if (outgoing) t.eqA else t.eqB
        out.low = (out.low + eqStep(eq[0])).coerceAtLeast(KILL); out.mid = (out.mid + eqStep(eq[1])).coerceAtLeast(KILL)
        out.high = (out.high + eqStep(eq[2])).coerceAtLeast(KILL)
        val fy = value(t, CurveKind.FILTER, outgoing, x)
        out.hpHz = when { fy > 0.502 -> sweep(20.0, 3000.0, ((fy - 0.5) * 2).coerceIn(0.0, 1.0)); else -> 20.0 }
        out.lpHz = when { fy < 0.498 -> sweep(20_000.0, 200.0, ((0.5 - fy) * 2).coerceIn(0.0, 1.0)); else -> 20_000.0 }
        // The effect: song 1 goes into it up to the effect's point, then only the effect rings on.
        val e = t.effect
        if (outgoing && e.type != MixEffect.Type.NONE) {
            val send = value(t, CurveKind.EFFECT, true, x)
            if (x < e.point) { if (e.type == MixEffect.Type.REVERB) out.reverb = send else out.echo = send }
            out.gain *= e.dry(x)
        }
        return out
    }

    /** Song 2's tempo factor (1 = its own) at [x], and song 1's ([outgoing]). */
    fun speed(t: Transition, outgoing: Boolean, x: Double, bpmA: Float, bpmB: Float, afterRampX: Double = 1.0): Double {
        if (!t.beatmatch || bpmA <= 0 || bpmB <= 0) return 1.0
        val ratio = bpmA.toDouble() / matchedBpm(bpmA, bpmB) // song 2 at song 1's tempo
        return when (t.tempo) {
            MixTempo.A_TO_B -> {
                // Song 1 eases from its tempo to song 2's; song 2 eases from song 1's tempo to its own.
                val s = smooth(x.coerceIn(0.0, 1.0))
                val sA = lerp(1.0, 1 / ratio, s)
                if (outgoing) sA else sA * ratio
            }
            MixTempo.B_TO_A -> if (outgoing) 1.0 else {
                if (x <= 1) ratio else lerp(ratio, 1.0, smooth(((x - 1) / afterRampX).coerceIn(0.0, 1.0)))
            }
        }
    }
}
