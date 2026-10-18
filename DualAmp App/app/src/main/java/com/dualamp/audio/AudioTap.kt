package com.dualamp.audio

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hands decoded PCM from the player to the visualizer, in sync with what is actually audible.
 *
 * The audio sink receives decoded audio well before it is played (the AudioTrack buffer is a few
 * hundred milliseconds deep). Each chunk is stored with its presentation time, and the sink also
 * reports the current playback position. The visualizer then reads only the samples that are
 * playing right now, so the visuals land on the beat instead of running ahead of it.
 *
 * This is the same idea as Poweramp's "Milk DSP" plugin: tap the decoded stream inside the player,
 * so no RECORD_AUDIO permission is needed and the visualizer sees clean, full-quality audio.
 */
object AudioTap {
    private const val CAPACITY = 1 shl 17 // stereo frames kept, ~2.7 s at 48 kHz
    private const val MASK = CAPACITY - 1
    private const val ANCHORS = 512

    private val ring = FloatArray(CAPACITY * 2)
    private var written = 0L // total frames written since the last flush
    private var readPos = 0L

    // Maps frame index -> presentation time, one anchor per chunk.
    private val anchorFrame = LongArray(ANCHORS)
    private val anchorPts = LongArray(ANCHORS)
    private var anchorCount = 0
    private var anchorHead = 0

    @Volatile private var positionUs = Long.MIN_VALUE
    @Volatile private var positionAtNanos = 0L
    @Volatile var playing = false

    // Current input format, written by the playback thread.
    @Volatile var sampleRate = 44100; private set
    @Volatile var channels = 2; private set
    @Volatile var encoding = C.ENCODING_PCM_16BIT; private set

    /** Bits per sample of the current stream, for the "FLAC · 44.1 kHz · 16-bit" label. */
    val bitDepth: Int
        get() = when (encoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }

    private var scratch = FloatArray(8192)

    fun configure(sampleRate: Int, channels: Int, encoding: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        this.encoding = encoding
        flush()
    }

    @Synchronized
    fun flush() {
        written = 0
        readPos = 0
        anchorCount = 0
        anchorHead = 0
        positionUs = Long.MIN_VALUE
    }

    fun onPosition(us: Long) {
        positionUs = us
        positionAtNanos = System.nanoTime()
    }

    /** Called on the playback thread with each decoded buffer. Does not modify [buffer]. */
    fun capture(buffer: ByteBuffer, presentationTimeUs: Long) {
        val ch = channels.coerceAtLeast(1)
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> return // compressed passthrough: nothing to visualize
        }
        val bigEndian = encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN ||
            encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        val src = buffer.duplicate().order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val frameBytes = bytesPerSample * ch
        val frames = src.remaining() / frameBytes
        if (frames <= 0) return
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)

        var pos = src.position()
        for (f in 0 until frames) {
            val l = readSample(src, pos, bigEndian)
            val r = if (ch > 1) readSample(src, pos + bytesPerSample, bigEndian) else l
            scratch[f * 2] = l
            scratch[f * 2 + 1] = r
            pos += frameBytes
        }
        write(scratch, frames, presentationTimeUs)
    }

    private fun readSample(b: ByteBuffer, at: Int, bigEndian: Boolean): Float = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((b.get(at).toInt() and 0xFF) - 128) / 128f
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> b.getShort(at) / 32768f
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
            val b0 = b.get(at).toInt() and 0xFF
            val b1 = b.get(at + 1).toInt() and 0xFF
            val b2 = b.get(at + 2).toInt()
            val v = if (bigEndian) (b0.toByte().toInt() shl 16) or (b1 shl 8) or (b2 and 0xFF)
            else (b2 shl 16) or (b1 shl 8) or b0
            v / 8388608f
        }
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> b.getInt(at) / 2147483648f
        C.ENCODING_PCM_FLOAT -> b.getFloat(at)
        else -> 0f
    }

    @Synchronized
    private fun write(data: FloatArray, frames: Int, ptsUs: Long) {
        val idx = (anchorHead + anchorCount) % ANCHORS
        anchorFrame[idx] = written
        anchorPts[idx] = ptsUs
        if (anchorCount < ANCHORS) anchorCount++ else anchorHead = (anchorHead + 1) % ANCHORS

        for (f in 0 until frames) {
            val slot = ((written + f) and MASK.toLong()).toInt() * 2
            ring[slot] = data[f * 2]
            ring[slot + 1] = data[f * 2 + 1]
        }
        written += frames
    }

    /**
     * Copies the stereo frames that have become audible since the last call into [out]
     * (interleaved L/R) and returns the frame count. Called by the visualizer once per frame.
     */
    @Synchronized
    fun read(out: FloatArray, maxFrames: Int): Int {
        val target = audibleFrame()
        if (target < readPos) readPos = target // jumped backwards without a flush
        val oldest = (written - CAPACITY + 1).coerceAtLeast(0)
        val start = maxOf(readPos, target - maxFrames, oldest)
        val count = (target - start).toInt().coerceIn(0, maxFrames)
        for (i in 0 until count) {
            val slot = ((start + i) and MASK.toLong()).toInt() * 2
            out[i * 2] = ring[slot]
            out[i * 2 + 1] = ring[slot + 1]
        }
        readPos = target
        return count
    }

    /** Index of the frame currently coming out of the speaker. */
    private fun audibleFrame(): Long {
        if (anchorCount == 0) return written
        val pos = positionUs
        if (pos == Long.MIN_VALUE) return written
        val elapsedUs = if (playing) ((System.nanoTime() - positionAtNanos) / 1000).coerceIn(0, 250_000) else 0
        val nowUs = pos + elapsedUs

        // Latest anchor at or before nowUs.
        var best = -1
        for (i in anchorCount - 1 downTo 0) {
            val idx = (anchorHead + i) % ANCHORS
            if (anchorPts[idx] <= nowUs) { best = idx; break }
        }
        if (best < 0) return anchorFrame[anchorHead] // nothing audible yet
        val frame = anchorFrame[best] + (nowUs - anchorPts[best]) * sampleRate / 1_000_000
        return frame.coerceIn(0, written)
    }
}
