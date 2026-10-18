package com.dualamp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.dualamp.settings.Prefs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DualAmp's audio effects, applied inside the player before sound reaches Android's mixer:
 * preamp, 10-band equalizer, bass and treble shelves, stereo width / mono, balance and a limiter
 * that stops boosted bands from clipping.
 *
 * Works in floating point on 16-bit or float PCM. Settings changes take effect on the next buffer.
 * When the DSP is switched off, audio passes through untouched.
 */
@UnstableApi
class DspProcessor : BaseAudioProcessor() {

    /** Standard "Audio EQ Cookbook" biquad, one instance per channel per filter. */
    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

        private fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
            b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
        }

        fun peaking(rate: Int, hz: Double, q: Double, gainDb: Double) {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * q)
            set(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
        }

        fun lowShelf(rate: Int, hz: Double, gainDb: Double) = shelf(rate, hz, gainDb, low = true)
        fun highShelf(rate: Int, hz: Double, gainDb: Double) = shelf(rate, hz, gainDb, low = false)

        private fun shelf(rate: Int, hz: Double, gainDb: Double, low: Boolean) {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * hz / rate
            val cosW = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0) // shelf slope 1
            val sa = 2 * sqrt(a) * alpha
            if (low) set(
                a * ((a + 1) - (a - 1) * cosW + sa), 2 * a * ((a - 1) - (a + 1) * cosW), a * ((a + 1) - (a - 1) * cosW - sa),
                (a + 1) + (a - 1) * cosW + sa, -2 * ((a - 1) + (a + 1) * cosW), (a + 1) + (a - 1) * cosW - sa,
            ) else set(
                a * ((a + 1) + (a - 1) * cosW + sa), -2 * a * ((a - 1) + (a + 1) * cosW), a * ((a + 1) + (a - 1) * cosW - sa),
                (a + 1) - (a - 1) * cosW + sa, 2 * ((a - 1) - (a + 1) * cosW), (a + 1) - (a - 1) * cosW - sa,
            )
        }
    }

    private var rate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var signature = Int.MIN_VALUE

    // filters[channel] = active filters for that channel, in order.
    private var filters: Array<List<Biquad>> = emptyArray()
    private var preampGain = 1.0
    private var leftGain = 1.0
    private var rightGain = 1.0
    private var width = 1.0
    private var limiter = true
    private var limiterGain = 1.0
    private var limiterRelease = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        signature = Int.MIN_VALUE
        return inputAudioFormat // same format out; the DSP never changes the stream shape
    }

    private fun rebuild() {
        val eqGains = Prefs.eqBands.map { it.value.toDouble() }
        val bass = Prefs.dspBass.value.toDouble()
        val treble = Prefs.dspTreble.value.toDouble()
        filters = Array(channels) {
            buildList {
                DspSettings.bands.forEachIndexed { i, hz ->
                    // Bands above the Nyquist limit (e.g. 16 kHz at low sample rates) are skipped.
                    if (eqGains[i] != 0.0 && hz < rate * 0.45) add(Biquad().apply { peaking(rate, hz.toDouble(), 1.41, eqGains[i]) })
                }
                if (bass != 0.0) add(Biquad().apply { lowShelf(rate, 100.0, bass) })
                if (treble != 0.0) add(Biquad().apply { highShelf(rate, 10000.0, treble) })
            }
        }
        preampGain = 10.0.pow(Prefs.dspPreamp.value / 20.0)
        val balance = Prefs.dspBalance.value / 100.0
        leftGain = if (balance > 0) 1 - balance else 1.0
        rightGain = if (balance < 0) 1 + balance else 1.0
        width = if (Prefs.dspMono.value) 0.0 else Prefs.dspWidth.value / 100.0
        limiter = Prefs.dspLimiter.value
        limiterRelease = exp(-1.0 / (0.15 * rate)) // ~150 ms recovery
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!DspSettings.enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val sig = DspSettings.signature()
        if (sig != signature) {
            signature = sig
            rebuild()
        }

        val input = inputBuffer.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = size / (bytesPerSample * channels)
        val frame = DoubleArray(channels)

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                frame[c] = if (encoding == C.ENCODING_PCM_FLOAT) input.float.toDouble() else input.short / 32768.0
            }
            for (c in 0 until channels) {
                var v = frame[c] * preampGain
                for (filter in filters[c]) v = filter.process(v)
                frame[c] = v
            }
            if (channels == 2) {
                // Stereo width works on the mid/side signal; 0 = mono, 1 = unchanged, 2 = extra wide.
                if (width != 1.0) {
                    val mid = (frame[0] + frame[1]) / 2
                    val side = (frame[0] - frame[1]) / 2 * width
                    frame[0] = mid + side
                    frame[1] = mid - side
                }
                frame[0] *= leftGain
                frame[1] *= rightGain
            }
            if (limiter) {
                // Instant attack, smooth release: keeps the loudest sample just under full scale.
                var peak = 0.0
                for (c in 0 until channels) peak = maxOf(peak, abs(frame[c]))
                val needed = if (peak * limiterGain > LIMIT) LIMIT / peak else 1.0
                limiterGain = if (needed < limiterGain) needed else 1.0 - (1.0 - limiterGain) * limiterRelease
                for (c in 0 until channels) frame[c] *= limiterGain
            }
            for (c in 0 until channels) {
                val v = frame[c].coerceIn(-1.0, 1.0)
                if (encoding == C.ENCODING_PCM_FLOAT) out.putFloat(v.toFloat())
                else out.putShort((v * 32767).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        filters.forEach { list -> list.forEach { it.reset() } }
        limiterGain = 1.0
    }

    override fun onReset() {
        signature = Int.MIN_VALUE
        filters = emptyArray()
    }

    companion object {
        private const val LIMIT = 0.98
    }
}
