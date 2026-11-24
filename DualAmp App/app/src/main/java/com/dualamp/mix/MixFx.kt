package com.dualamp.mix

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One song's part in a transition, in that song's own time (µs): x = 0 at [startUs], x = 1 at [startUs] + [spanUs].
 * [loopStartUs]/[loopLenUs]: song 1 repeats that stretch until the transition ends ([MixLoop]).
 */
class DeckPlan(
    val transition: Transition,
    val outgoing: Boolean,
    val startUs: Long,
    val spanUs: Long,
    val beatUs: Long,
    val loopStartUs: Long = 0,
    val loopLenUs: Long = 0,
    /** Song 1's length: past it the plan is over (the next song is already coming through). */
    val endUs: Long = Long.MAX_VALUE,
) {
    fun x(itemUs: Long): Double = if (spanUs <= 0) (if (itemUs >= startUs) 1.0 else -1.0) else (itemUs - startUs).toDouble() / spanUs
}

/**
 * The mixer's effects on one player's sound (volume, lows/mids/highs, high- and low-pass filters, echo, loop), placed
 * exactly on the song's own time: the sink tells it the time of each buffer ([onInputPts]) and the mixer the offset
 * between the player's clock and the song ([offsetUs]).
 */
@UnstableApi
class MixFx : BaseAudioProcessor() {
    @Volatile var plan: DeckPlan? = null
    /** Renderer time minus the song's own time, µs (set by [MixEngine]). */
    @Volatile var offsetUs: Long = Long.MIN_VALUE
    // Written by the sink: the time of the buffer coming in, and where playback is.
    @Volatile private var pendingPts = Long.MIN_VALUE
    @Volatile var outPosUs = Long.MIN_VALUE; private set
    @Volatile var outPosNanos = 0L; private set
    /** Time of the next sample to process (renderer time). */
    private var clockUs = Long.MIN_VALUE

    private var rate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
        fun copyFrom(o: Biquad) { b0 = o.b0; b1 = o.b1; b2 = o.b2; a1 = o.a1; a2 = o.a2 }
        private fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
            b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
        }
        fun peaking(rate: Int, hz: Double, q: Double, db: Double) {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q)
            set(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
        }
        fun shelf(rate: Int, hz: Double, db: Double, low: Boolean) {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val cw = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
            if (low) set(a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa)
            else set(a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa)
        }
        fun pass(rate: Int, hz: Double, high: Boolean) {
            val w = 2 * PI * hz.coerceIn(10.0, rate * 0.45) / rate; val cw = cos(w); val alpha = sin(w) / (2 * 0.8)
            if (high) set((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
            else set((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
        }
    }

    // Per channel: low shelf, mid peak, high shelf, high-pass, low-pass.
    private var filters: Array<Array<Biquad>> = emptyArray()
    private val design = Array(5) { Biquad() }
    private val p = DeckParams()
    // The other deck's settings at the same moment (for [headroom]).
    private val other = DeckParams()
    private var lastGain = 1.0
    private var active = false
    private var echoBuf: Array<FloatArray> = emptyArray()
    private var echoPos = 0
    private var echoTail = 0
    // The effect in use (kept after the plan ends, for the tail): repeat length, feedback, darkening.
    private var fxDelay = 1
    private var fxFeedback = 0.55
    private var fxDark = true
    private var darkState: DoubleArray = DoubleArray(0)
    private var reverb: Reverb? = null
    private var loopBuf: Array<FloatArray> = emptyArray()
    private var loopFrames = 0
    private var loopRecorded = 0
    private var loopFor: DeckPlan? = null

    /** From the sink: a new buffer starting at [ptsUs] (renderer time) comes in. */
    fun onInputPts(ptsUs: Long) { pendingPts = ptsUs }

    fun onOutputPosition(us: Long) { outPosUs = us; outPosNanos = System.nanoTime() }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        filters = Array(channels) { Array(5) { Biquad() } }
        echoBuf = Array(channels) { FloatArray(rate * 3) }
        darkState = DoubleArray(channels)
        reverb = Reverb(rate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val pending = pendingPts
        if (pending != Long.MIN_VALUE) {
            pendingPts = Long.MIN_VALUE
            if (clockUs == Long.MIN_VALUE || abs(pending - clockUs) > 20_000) clockUs = pending
        }
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = size / (bytesPerSample * channels)
        val startUs = clockUs
        if (clockUs != Long.MIN_VALUE) clockUs += frames * 1_000_000L / rate
        val out = replaceOutputBuffer(size)
        val plan = plan
        val offset = offsetUs
        if ((plan == null || offset == Long.MIN_VALUE || startUs == Long.MIN_VALUE) && !active && echoTail <= 0) {
            out.put(inputBuffer); out.flip(); return
        }
        if (plan == null || offset == Long.MIN_VALUE || startUs == Long.MIN_VALUE) {
            // The plan just ended: let the filters settle back to neutral and the echo ring out.
            process(inputBuffer, out, frames, null, 0)
        } else {
            process(inputBuffer, out, frames, plan, startUs - offset)
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun process(input0: ByteBuffer, out: ByteBuffer, frames: Int, plan: DeckPlan?, itemStartUs: Long) {
        val input = input0.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())
        val float = encoding == C.ENCODING_PCM_FLOAT
        if (plan != null && plan.loopLenUs > 0 && loopFor !== plan) setUpLoop(plan)
        if (plan == null && loopFor != null) { loopFor = null; loopBuf = emptyArray() }
        if (plan != null) {
            val e = plan.transition.effect
            val beats = if (e.beats > 0) e.beats else 0.75
            fxDelay = (plan.beatUs * beats * rate / 1_000_000.0).toInt().coerceIn(1, rate * 3 - 1)
            fxDark = e.type != MixEffect.Type.DELAY
            fxFeedback = if (fxDark) 0.55 else 0.38
        }
        val delay = fxDelay
        val rv = reverb
        val darkK = 1 - kotlin.math.exp(-2 * PI * 2800.0 / rate)
        val block = 64
        var f = 0
        val frame = DoubleArray(channels)
        var anyEffect = false
        while (f < frames) {
            val n = minOf(block, frames - f)
            val tUs = itemStartUs + f * 1_000_000L / rate
            // Headroom: while both songs are heard, each is turned down a little so together they don't clip (Android
            // adds the two players up). Both at full volume: about -3.5 dB each.
            var headroom = 1.0
            if (plan != null && tUs < plan.endUs) {
                val x = plan.x(tUs)
                Automation.params(plan.transition, plan.outgoing, x, p)
                if (x >= 0 && x < 1) {
                    Automation.params(plan.transition, !plan.outgoing, x, other)
                    headroom = 1 / (1 + 0.5 * minOf(p.gain, other.gain).coerceAtLeast(0.0))
                }
            } else p.neutral()
            val neutral = p.gain == 1.0 && p.low == 0.0 && p.mid == 0.0 && p.high == 0.0 && p.hpHz <= 20.0 && p.lpHz >= 20_000.0 &&
                p.echo == 0.0 && p.reverb == 0.0
            if (!neutral) anyEffect = true
            // Filters for this block.
            design[0].shelf(rate, 200.0, p.low, low = true)
            design[1].peaking(rate, 1000.0, 0.7, p.mid)
            design[2].shelf(rate, 4000.0, p.high, low = false)
            design[3].pass(rate, p.hpHz, high = true)
            design[4].pass(rate, p.lpHz, high = false)
            for (c in 0 until channels) for (k in 0 until 5) filters[c][k].copyFrom(design[k])
            val g0 = lastGain; val g1 = p.gain * headroom
            for (i in 0 until n) {
                val g = g0 + (g1 - g0) * (i + 1) / n
                var send = 0.0
                for (c in 0 until channels) frame[c] = if (float) input.float.toDouble() else input.short / 32768.0
                // Loop: song 1 repeats the stretch it has recorded.
                if (plan != null && loopFor === plan && loopFrames > 0) loop(plan, tUs + i * 1_000_000L / rate, frame)
                for (c in 0 until channels) {
                    var v = frame[c]
                    if (!neutral || active) {
                        if (p.low != 0.0 || active) v = filters[c][0].process(v)
                        v = filters[c][1].process(v)
                        v = filters[c][2].process(v)
                        if (p.hpHz > 20.0) v = filters[c][3].process(v) else filters[c][3].reset()
                        if (p.lpHz < 20_000.0) v = filters[c][4].process(v) else filters[c][4].reset()
                    }
                    // Echo / delay: a beat-timed delay fed by the song, ringing on after the song itself is gone (an echo's
                    // repeats get darker).
                    val buf = echoBuf[c]
                    val read = (echoPos - delay + buf.size) % buf.size
                    val echoed = buf[read].toDouble()
                    var fb = echoed * fxFeedback
                    if (fxDark) { darkState[c] += darkK * (fb - darkState[c]); fb = darkState[c] }
                    buf[echoPos] = (v * p.echo + fb).toFloat()
                    frame[c] = v * g + echoed
                    send += v * p.reverb
                }
                // Reverb: the song (mono) into a room, heard on every channel.
                if (rv != null && (p.reverb > 0 || echoTail > 0)) {
                    val wet = rv.process(send / channels)
                    for (c in 0 until channels) frame[c] += wet
                }
                echoPos = (echoPos + 1) % echoBuf[0].size
                for (c in 0 until channels) {
                    val v = softClip(frame[c])
                    if (float) out.putFloat(v.toFloat()) else out.putShort((v * 32767).toInt().toShort())
                }
            }
            if (p.echo > 0 || p.reverb > 0) echoTail = rate * 4 else echoTail -= n
            lastGain = g1
            f += n
        }
        active = anyEffect
    }

    private fun setUpLoop(plan: DeckPlan) {
        loopFor = plan
        loopFrames = (plan.loopLenUs * rate / 1_000_000L).toInt().coerceIn(0, rate * 20)
        loopBuf = Array(channels) { FloatArray(loopFrames) }
        loopRecorded = 0
    }

    private fun loop(plan: DeckPlan, tUs: Long, frame: DoubleArray) {
        val rel = tUs - plan.loopStartUs
        if (rel < 0) return
        val idx = (rel * rate / 1_000_000L).toInt()
        if (idx < loopFrames) {
            // First pass: record (and play as is).
            for (c in 0 until channels) loopBuf[c][idx] = frame[c].toFloat()
            loopRecorded = maxOf(loopRecorded, idx + 1)
        } else if (plan.x(tUs) < 1.0 && loopRecorded >= loopFrames - rate / 50) {
            val j = idx % loopFrames
            for (c in 0 until channels) frame[c] = loopBuf[c][j].toDouble()
        }
    }

    override fun onFlush() {
        filters.forEach { ch -> ch.forEach { it.reset() } }
        echoBuf.forEach { java.util.Arrays.fill(it, 0f) }
        java.util.Arrays.fill(darkState, 0.0)
        reverb?.clear()
        echoTail = 0
        clockUs = Long.MIN_VALUE
        lastGain = plan?.let { if (it.outgoing) 1.0 else 0.0 } ?: 1.0
        loopFor = null
        active = false
    }

    override fun onReset() {
        filters = emptyArray(); echoBuf = emptyArray(); loopBuf = emptyArray(); loopFor = null
        clockUs = Long.MIN_VALUE
    }
}

/**
 * A small room reverb (Schroeder / Freeverb style): four damped comb filters side by side, then two all-pass filters.
 * About two and a half seconds of tail.
 */
class Reverb(private val rate: Int) {
    private fun len(n: Int) = (n * rate / 44100.0).toInt().coerceAtLeast(1)
    private val combs = intArrayOf(1116, 1188, 1277, 1356).map { FloatArray(len(it)) }
    private val combPos = IntArray(4)
    private val combLow = DoubleArray(4)
    private val alls = intArrayOf(556, 441).map { FloatArray(len(it)) }
    private val allPos = IntArray(2)

    fun process(x: Double): Double {
        val input = x * 0.09
        var out = 0.0
        for (k in 0 until 4) {
            val buf = combs[k]; val i = combPos[k]
            val y = buf[i].toDouble()
            combLow[k] = y * 0.75 + combLow[k] * 0.25 // damping: the room swallows the highs
            buf[i] = (input + combLow[k] * 0.84).toFloat()
            combPos[k] = (i + 1) % buf.size
            out += y
        }
        for (k in 0 until 2) {
            val buf = alls[k]; val i = allPos[k]
            val b = buf[i].toDouble()
            buf[i] = (out + b * 0.5).toFloat()
            out = b - out
            allPos[k] = (i + 1) % buf.size
        }
        return out
    }

    fun clear() {
        combs.forEach { java.util.Arrays.fill(it, 0f) }; alls.forEach { java.util.Arrays.fill(it, 0f) }
        java.util.Arrays.fill(combLow, 0.0)
    }
}

/** Tells a [MixFx] the time of each buffer and where playback is (see [MixFx.onInputPts]). */
@UnstableApi
class MixClockSink(sink: AudioSink, private val fx: MixFx) : ForwardingAudioSink(sink) {
    private var lastBuffer: ByteBuffer? = null
    private var lastPts = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        lastBuffer = null
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (buffer !== lastBuffer || presentationTimeUs != lastPts) {
            lastBuffer = buffer; lastPts = presentationTimeUs
            fx.onInputPts(presentationTimeUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val pos = super.getCurrentPositionUs(sourceEnded)
        if (pos != AudioSink.CURRENT_POSITION_NOT_SET) fx.onOutputPosition(pos)
        return pos
    }

    override fun flush() { super.flush(); lastBuffer = null }
}

/** Rounds off peaks above 0.9 instead of cutting them flat (no harsh clipping when an echo or two songs pile up). */
internal fun softClip(v: Double): Double {
    val a = abs(v)
    if (a <= 0.9) return v
    val s = 0.9 + 0.1 * kotlin.math.tanh((a - 0.9) / 0.1)
    return if (v < 0) -s else s
}
