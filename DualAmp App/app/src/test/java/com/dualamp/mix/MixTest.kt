package com.dualamp.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class MixTest {
    private val rate = 11025

    /** A kick drum (decaying 60 Hz thump) on every beat, plus a little hi-hat between. */
    private fun clickTrack(bpm: Double, seconds: Int, offsetMs: Double = 0.0): FloatArray {
        val x = FloatArray(rate * seconds)
        val beat = 60.0 / bpm
        var t = offsetMs / 1000
        while (t < seconds) {
            val start = (t * rate).toInt()
            for (i in 0 until rate / 5) {
                val j = start + i; if (j >= x.size) break
                x[j] += (sin(2 * PI * 60 * i / rate) * exp(-i / (rate * 0.05)) * 0.8).toFloat()
            }
            val hat = ((t + beat / 2) * rate).toInt()
            for (i in 0 until rate / 50) { val j = hat + i; if (j < x.size) x[j] += ((Math.random() - 0.5) * 0.2 * exp(-i / (rate * 0.005))).toFloat() }
            t += beat
        }
        return x
    }

    @Test fun findsTempo() {
        for (bpm in listOf(90.0, 124.0, 128.0, 140.0, 150.0)) {
            val a = SongAnalyzer.analysePcm(1, clickTrack(bpm, 60, 230.0), rate)
            assertEquals("tempo of a $bpm BPM click track", bpm, a.bpm.toDouble(), 0.1)
        }
    }

    @Test fun findsBeatPosition() {
        val a = SongAnalyzer.analysePcm(1, clickTrack(120.0, 60, 230.0), rate)
        // A beat every 500 ms starting at 230 ms: the grid's anchor is on one of them (within 30 ms).
        val phase = ((a.beatMs - 230.0) % 500 + 500) % 500
        assertTrue("beat at ${a.beatMs}", phase < 30 || phase > 470)
    }

    /**
     * Techno: a kick on every beat, a loud bass note and an open hat between them, a clap on beats 2 and 4, and a new
     * part (a rumble) coming in every 8 bars on the first beat of a bar. Bars start at [barOffsetMs].
     */
    private fun technoTrack(bpm: Double, seconds: Int, barOffsetMs: Double): FloatArray {
        val x = FloatArray(rate * seconds)
        val beat = 60.0 / bpm
        val rnd = java.util.Random(7)
        fun add(t: Double, len: Double, f: (Int) -> Double) {
            val s = (t * rate).toInt(); if (s < 0) return
            for (i in 0 until (len * rate).toInt()) { val j = s + i; if (j >= x.size) break; x[j] += f(i).toFloat() }
        }
        var k = 0
        var t = barOffsetMs / 1000 - beat * 4
        while (t < seconds) {
            if (t >= 0) {
                add(t, 0.15) { i -> sin(2 * PI * 55 * i / rate) * exp(-i / (rate * 0.04)) * 0.7 }
                // Off-beat bass, louder and longer than the kick, and an open hat.
                add(t + beat / 2, beat / 2 * 0.9) { i -> sin(2 * PI * 70 * i / rate) * 0.55 * minOf(1.0, i / (rate * 0.004)) }
                add(t + beat / 2, 0.12) { i -> (rnd.nextDouble() - 0.5) * 0.5 * exp(-i / (rate * 0.05)) }
                if (k % 2 == 1) add(t, 0.08) { i -> (rnd.nextDouble() - 0.5) * 0.4 * exp(-i / (rate * 0.02)) }
                // Every other 8 bars a rumble plays (starting on a bar's first beat).
                if ((k / 32) % 2 == 1) add(t, beat) { i -> sin(2 * PI * 420 * i / rate) * 0.25 }
            }
            k++; t += beat
        }
        return x
    }

    @Test fun gridIsOnTheKicksInTechno() {
        val a = SongAnalyzer.analysePcm(1, technoTrack(130.0, 90, 350.0), rate)
        assertEquals(130.0, a.bpm.toDouble(), 0.1)
        val beat = 60_000.0 / 130
        val off = ((a.beatMs - 350.0) % beat + beat) % beat
        assertTrue("beat grid at ${a.beatMs} (off the kicks by $off ms)", off < 25 || beat - off < 25)
    }

    @Test fun findsTheFirstBeatOfTheBars() {
        val beat = 60_000.0 / 126
        for (barOffset in listOf(350.0, 350.0 + beat, 350.0 + 2 * beat, 350.0 + 3 * beat)) {
            val a = SongAnalyzer.analysePcm(1, technoTrack(126.0, 100, barOffset), rate)
            val bar = 4 * beat
            val off = ((a.barMs - barOffset) % bar + bar) % bar
            assertTrue("bars from $barOffset, found ${a.barMs}", off < 30 || bar - off < 30)
            assertTrue(a.isBarLine(barOffset + bar * 5))
            assertEquals(barOffset + bar * 3, a.snapToBar(barOffset + bar * 3 + 700), 30.0)
        }
    }

    @Test fun findsKey() {
        // A minor chord progression (Am - F - C - G pattern weighted to Am): A, C, E strongest.
        val x = FloatArray(rate * 30)
        val chords = listOf(listOf(220.0, 261.63, 329.63), listOf(174.61, 220.0, 261.63), listOf(220.0, 261.63, 329.63), listOf(196.0, 246.94, 293.66))
        for (i in x.indices) {
            val c = chords[(i / (rate * 2)) % chords.size]
            var v = 0.0
            for (f in c) v += sin(2 * PI * f * i / rate) + 0.3 * sin(2 * PI * f * 2 * i / rate)
            x[i] = (v * 0.15).toFloat()
        }
        val key = SongAnalyzer.key(x, rate)
        // A minor (21) or its relative, C major (0), both 8 on the Camelot wheel.
        assertTrue("key ${Analysis.keyName(key)}", key == 21 || key == 0)
    }

    @Test fun camelot() {
        assertEquals("8B", Analysis.camelot(0))   // C
        assertEquals("8A", Analysis.camelot(21))  // Am
        assertEquals("9B", Analysis.camelot(7))   // G
        assertEquals("1B", Analysis.camelot(11))  // B
        assertEquals("5A", Analysis.camelot(12))  // Cm
        assertEquals(2, Analysis.keyMatch(0, 0))
        assertEquals(1, Analysis.keyMatch(0, 7))   // C -> G
        assertEquals(1, Analysis.keyMatch(0, 21))  // C -> Am
        assertEquals(0, Analysis.keyMatch(0, 6))   // C -> F#
    }

    @Test fun crossfadeCurves() {
        val t = Transition(1, 2, 60_000, 0, volume = MixVolume.CROSSFADE, eq = MixEq.BASS_SWAP)
        val p = DeckParams()
        assertEquals(0.0, Automation.params(t, false, -0.1, p).gain, 1e-9) // song 2 silent before
        assertEquals(1.0, Automation.params(t, true, -0.1, p).gain, 1e-9)
        assertEquals(0.0, Automation.params(t, true, 1.0, p).gain, 1e-9) // song 1 gone after
        assertEquals(1.0, Automation.params(t, false, 1.0, p).gain, 1e-9)
        // Equal power in the middle.
        val ga = Automation.params(t, true, 0.5, DeckParams()).gain
        val gb = Automation.params(t, false, 0.5, DeckParams()).gain
        assertEquals(1.0, ga * ga + gb * gb, 1e-3)
        // Bass swap: song 2's bass out before halfway, song 1's after.
        assertTrue(Automation.params(t, false, 0.4, p).low < -20)
        assertTrue(Automation.params(t, true, 0.6, p).low < -20)
        assertEquals(0.0, Automation.params(t, true, 0.4, p).low, 1e-9)
    }

    @Test fun tempoEndsAtSong2sOwn() {
        val t = Transition(1, 2, 0, 0, tempo = MixTempo.A_TO_B)
        // Song 2 at song 1's tempo at the start, its own at the end; the two always share a beat.
        assertEquals(140.0 / 128, Automation.speed(t, false, 0.0, 140f, 128f), 1e-6)
        assertEquals(1.0, Automation.speed(t, false, 1.0, 140f, 128f), 1e-6)
        for (x in listOf(0.0, 0.3, 0.7, 1.0)) {
            val a = Automation.speed(t, true, x, 140f, 128f) * 140
            val b = Automation.speed(t, false, x, 140f, 128f) * 128
            assertEquals(a, b, 1e-6)
        }
    }

    @Test fun halfAndDoubleTime() {
        assertEquals(140f, matchedBpm(140f, 70f), 1e-3f)    // 70 BPM counted double
        assertEquals(87.5f, matchedBpm(87f, 175f), 1e-3f)   // 175 BPM counted half
        assertEquals(104.7f, matchedBpm(140f, 104.7f), 1e-3f)
        assertTrue(tempoGap(140f, 104.7f) > 0.2f)
        assertTrue(tempoGap(128f, 126f) < 0.02f)
        // Song 2 at half its tempo: it plays at its own speed and lands on every other beat.
        val t = Transition(1, 2, 0, 0, tempo = MixTempo.B_TO_A)
        assertEquals(1.0, Automation.speed(t, false, 0.5, 140f, 70f), 1e-6)
    }

    @Test fun lengthInBars() {
        val t = Transition(1, 2, 0, 0, bars = 8)
        assertEquals(8 * 4 * 500L, t.lengthMs(120f))
        assertEquals(10_000L, t.copy(seconds = 10f).lengthMs(0f))
    }

    @Test fun savesAndLoads() {
        val t = Transition(5, 9, 123_456, 7_890, bars = 16, volume = MixVolume.FADE_IN, eq = MixEq.BLEND, filter = MixFilter.LOW_PASS_OUT,
            effect = MixEffect.ECHO_OUT, loop = MixLoop.TWO, beatmatch = false, tempo = MixTempo.B_TO_A, eqA = listOf(-3, 0, 2), eqB = listOf(0, -6, 0), preset = "Custom")
        assertEquals(t, Transition.fromJson(org.json.JSONObject(t.toJson().toString())))
    }

    @Test fun autoTransitionIsOnABeat() {
        val a = SongAnalyzer.analysePcm(1, clickTrack(128.0, 90, 100.0), rate)
        val b = SongAnalyzer.analysePcm(2, clickTrack(126.0, 90, 40.0), rate)
        val t = Transition.auto(1, 2, a, b, 90_000)
        val beat = 60_000.0 / a.bpm
        val off = ((t.startMs - a.beatMs) % beat + beat) % beat
        assertTrue("start ${t.startMs} on a beat", off < 15 || beat - off < 15)
        assertTrue(t.startMs + t.lengthMs(a.bpm) <= 90_000)
        assertTrue(abs(t.entryMs - b.snapToBeat(t.entryMs.toDouble())) < 2)
    }

    @Test fun overlapStopsRightAtTheEnd() {
        val t = Transition(1, 2, 0, 0, volume = MixVolume.OVERLAP, eq = MixEq.NONE)
        assertEquals(1.0, Automation.params(t, true, 0.99, DeckParams()).gain, 1e-9)
        assertEquals(0.0, Automation.params(t, true, 1.0, DeckParams()).gain, 1e-9)
        assertEquals(1.0, Automation.params(t, false, 0.0, DeckParams()).gain, 1e-9)
    }

    @Test fun handDrawnCurvesReplaceThePreset() {
        val drawn = Curves.of("0:1;0.3:0.2;1:0", "0:0;0.6:0.9;1:1")
        val t = Transition(1, 2, 0, 0, volume = MixVolume.OVERLAP, custom = mapOf(CurveKind.VOLUME to drawn))
        assertEquals(0.6, Automation.params(t, true, 0.15, DeckParams()).gain, 1e-6)
        assertEquals(0.45, Automation.params(t, false, 0.3, DeckParams()).gain, 1e-6)
        // A step (two points at the same x): the bass swap.
        val step = Curves.parse("0:1;0.5:1;0.5:0;1:0")
        assertEquals(1.0, Curves.eval(step, 0.49), 1e-9)
        assertEquals(0.0, Curves.eval(step, 0.5), 1e-9)
        // Filter: up is a high pass, down a low pass.
        val f = Transition(1, 2, 0, 0, custom = mapOf(CurveKind.FILTER to Curves.of("0:1;1:1", "0:0;1:0")))
        assertTrue(Automation.params(f, true, 0.5, DeckParams()).hpHz > 2000)
        assertTrue(Automation.params(f, false, 0.5, DeckParams()).lpHz < 300)
        // Kept in the playlist.
        assertEquals(t, Transition.fromJson(org.json.JSONObject(t.toJson().toString())))
    }

    @Test fun effectsCutOrFadeSong1() {
        assertEquals(1.0, MixEffect.ECHO_1_CUT_END.dry(0.99), 1e-9)
        assertEquals(0.0, MixEffect.ECHO_1_CUT_END.dry(1.0), 1e-9)
        assertEquals(0.5, MixEffect.ECHO_1_OUT_END.dry(0.75), 1e-9)
        assertEquals(0.0, MixEffect.REVERB_OUT_CENTER.dry(0.5), 1e-9)
        val t = Transition(1, 2, 0, 0, volume = MixVolume.OVERLAP, effect = MixEffect.REVERB_OUT_CENTER)
        assertTrue(Automation.params(t, true, 0.45, DeckParams()).reverb > 0.8)
        assertEquals("nothing goes in after the point", 0.0, Automation.params(t, true, 0.6, DeckParams()).reverb, 1e-9)
    }
}
