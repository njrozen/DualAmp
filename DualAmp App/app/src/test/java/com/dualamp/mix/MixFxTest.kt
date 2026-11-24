package com.dualamp.mix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@androidx.media3.common.util.UnstableApi
class MixFxTest {
    private val rate = 44100

    /** Runs [seconds] of a 440 Hz tone (16-bit stereo, song time starting at 0) through [fx]; returns the left channel. */
    private fun run(fx: MixFx, seconds: Double, tone: (Int) -> Double = { i -> 0.5 * sin(2 * PI * 440 * i / rate) }): DoubleArray {
        fx.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        fx.flush()
        fx.offsetUs = 0
        val frames = (seconds * rate).toInt()
        val out = DoubleArray(frames)
        var done = 0
        val chunk = 4096
        while (done < frames) {
            val n = minOf(chunk, frames - done)
            val buf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until n) {
                val v = (tone(done + i) * 32767).toInt().toShort()
                buf.putShort(v); buf.putShort(v)
            }
            buf.flip()
            fx.onInputPts(done * 1_000_000L / rate)
            fx.queueInput(buf)
            val o = fx.output.order(ByteOrder.nativeOrder())
            var i = 0
            while (o.remaining() >= 4) { out[done + i] = o.short / 32768.0; o.short; i++ }
            done += n
        }
        return out
    }

    private fun level(x: DoubleArray, fromS: Double, toS: Double): Double {
        var m = 0.0
        for (i in (fromS * rate).toInt() until (toS * rate).toInt()) m = maxOf(m, abs(x[i]))
        return m
    }

    @Test fun incomingSongIsSilentBeforeAndFullAfter() {
        val t = Transition(1, 2, 0, 1000, volume = MixVolume.CROSSFADE, eq = MixEq.NONE)
        val fx = MixFx()
        // Song 2's part: x = 0 at 1 s, x = 1 at 3 s of its own time.
        fx.plan = DeckPlan(t, outgoing = false, startUs = 1_000_000, spanUs = 2_000_000, beatUs = 500_000)
        val out = run(fx, 4.0)
        assertTrue("silent before it comes in", level(out, 0.1, 0.95) < 0.01)
        assertTrue("half way up in the middle", level(out, 1.9, 2.1) in 0.25..0.45)
        assertTrue("full after", level(out, 3.2, 3.9) > 0.45)
    }

    @Test fun outgoingSongFadesAway() {
        val t = Transition(1, 2, 1000, 0, volume = MixVolume.CROSSFADE, eq = MixEq.BASS_SWAP, filter = MixFilter.HIGH_PASS_OUT)
        val fx = MixFx()
        fx.plan = DeckPlan(t, outgoing = true, startUs = 1_000_000, spanUs = 2_000_000, beatUs = 500_000)
        val out = run(fx, 4.0)
        assertTrue("untouched before", level(out, 0.2, 0.9) > 0.45)
        assertTrue("gone after", level(out, 3.2, 3.9) < 0.01)
    }

    @Test fun loopRepeatsTheFirstBars() {
        // A rising ramp: once looping, song 1 goes back to the start of the loop instead of rising on.
        // (Overlap: song 2's volume, and so the headroom, stays the same while it loops.)
        val t = Transition(1, 2, 1000, 0, volume = MixVolume.OVERLAP, eq = MixEq.NONE, loop = MixLoop.ONE)
        val fx = MixFx()
        fx.plan = DeckPlan(t, outgoing = true, startUs = 1_000_000, spanUs = 3_000_000, beatUs = 250_000,
            loopStartUs = 1_000_000, loopLenUs = 1_000_000)
        val out = run(fx, 3.5) { i -> 0.9 * i / (rate * 4.0) }
        val first = out[(1.5 * rate).toInt()]
        val again = out[(2.5 * rate).toInt()]
        assertEquals("one loop later it plays the same", first, again, 0.01)
    }

    @Test fun echoRingsOn() {
        val t = Transition(1, 2, 0, 0, volume = MixVolume.CROSSFADE, eq = MixEq.NONE, effect = MixEffect.ECHO_OUT)
        val fx = MixFx()
        fx.plan = DeckPlan(t, outgoing = true, startUs = 0, spanUs = 2_000_000, beatUs = 400_000)
        val out = run(fx, 3.0)
        // Song 1 itself is gone at the end (2 s), but its echoes are still heard after.
        assertTrue("echo heard after song 1 left", level(out, 2.1, 2.6) > 0.02)
    }

    @Test fun reverbRingsOnAndDiesAway() {
        val t = Transition(1, 2, 0, 0, volume = MixVolume.OVERLAP, eq = MixEq.NONE, effect = MixEffect.REVERB_CUT_END)
        val fx = MixFx()
        fx.plan = DeckPlan(t, outgoing = true, startUs = 0, spanUs = 2_000_000, beatUs = 400_000)
        val out = run(fx, 7.0)
        val end = level(out, 1.7, 1.95)
        // (Turned down a little while song 2 plays too: headroom.)
        assertTrue("song 1 plays up to the end ($end)", end > 0.15)
        val tail = level(out, 2.05, 2.4)
        assertTrue("the reverb rings on after the cut ($tail)", tail > 0.01)
        assertTrue("and dies away", level(out, 6.0, 7.0) < tail * 0.1)
        assertTrue("never blows up", out.all { kotlin.math.abs(it) <= 1.0 })
    }

    @Test fun bothSongsAtFullVolumeLeaveHeadroom() {
        // Overlap: both full, so each is turned down (about -3.5 dB) and together they don't clip; before and after, untouched.
        val t = Transition(1, 2, 1000, 0, volume = MixVolume.OVERLAP, eq = MixEq.NONE)
        val fx = MixFx()
        fx.plan = DeckPlan(t, outgoing = true, startUs = 1_000_000, spanUs = 2_000_000, beatUs = 500_000)
        val out = run(fx, 2.5)
        assertEquals("untouched before", 0.5, level(out, 0.2, 0.9), 0.01)
        assertEquals("turned down while both play", 0.5 / 1.5, level(out, 1.5, 2.4), 0.01)
    }

    @Test fun softClipRoundsPeaks() {
        assertEquals(0.5, softClip(0.5), 0.0)
        assertTrue(softClip(1.5) < 1.0 && softClip(1.5) > 0.95)
        assertTrue(softClip(-3.0) >= -1.0)
    }
}
