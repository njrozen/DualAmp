package com.dualamp.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.util.LruCache
import com.dualamp.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Loudness outline of a song for the top screen's waveform seek bar.
 *
 * The file is decoded once in the background (MediaCodec decodes far faster than real time) and the
 * average loudness of each slice is kept. Results are cached in memory and on disk, so each song is only
 * decoded the first time it plays.
 */
object Waveform {
    const val BARS = 150
    private const val TAG = "Waveform"
    private val memory = LruCache<Long, FloatArray>(64)
    private const val CACHE_VERSION = 2
    private val decodeLock = Mutex()

    fun cached(songId: Long): FloatArray? = memory.get(songId)

    // Decodes run here, not in the caller: the seek bar leaves the screen whenever the song info fades, and that
    // must not cancel a decode half way (a song taking longer than the fade would never get its waveform).
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private val running = HashMap<Long, kotlinx.coroutines.Deferred<FloatArray?>>()

    suspend fun load(context: Context, song: Song): FloatArray? {
        memory.get(song.id)?.let { return it }
        val app = context.applicationContext
        val job = synchronized(running) {
            // Only the song now asked for matters: stop reading songs that were skipped.
            running.entries.removeAll { (id, other) -> if (id != song.id) { other.cancel(); true } else false }
            running.getOrPut(song.id) {
                scope.async { readOrDecode(app, song) }.also { job ->
                    job.invokeOnCompletion { synchronized(running) { if (running[song.id] === job) running.remove(song.id) } }
                }
            }
        }
        // Cancelled because another song was asked for: no waveform (unless this caller is itself being cancelled).
        return try { job.await() } catch (e: kotlinx.coroutines.CancellationException) {
            if (coroutineContext[kotlinx.coroutines.Job]?.isActive != false) null else throw e
        }
    }

    private suspend fun readOrDecode(context: Context, song: Song): FloatArray? = withContext(Dispatchers.Default) {
        memory.get(song.id)?.let { return@withContext it }
        val file = File(context.cacheDir, "waveforms/v$CACHE_VERSION/${song.id}.bin")
        readCache(file)?.let { memory.put(song.id, it); return@withContext it }
        // One decode at a time.
        val bars = decodeLock.withLock {
            try {
                // A file the decoder chokes on must not hold up every later song.
                kotlinx.coroutines.withTimeoutOrNull(60_000) { decode(context, song) }
                    .also { if (it == null) Log.w(TAG, "No waveform for ${song.path}") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Could not read ${song.path}", e)
                null
            }
        } ?: return@withContext null
        memory.put(song.id, bars)
        writeCache(file, bars)
        bars
    }

    private suspend fun decode(context: Context, song: Song): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, song.uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
            else song.durationMs * 1000
            if (durationUs <= 0) return null

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val sums = DoubleArray(BARS)
            val counts = IntArray(BARS)
            val info = MediaCodec.BufferInfo()
            var floatPcm = false
            var inputDone = false
            var outputDone = false
            // Some decoders never flag the end: stop once all input is in and nothing more comes out.
            var lastOutput = System.nanoTime()
            while (!outputDone) {
                coroutineContext.ensureActive()
                // Hand over every free input buffer without waiting (waiting per frame made this slower than real time).
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                // Then take every decoded buffer that is ready, waiting a moment only for the first.
                var first = true
                while (!outputDone) {
                    val outIndex = codec.dequeueOutputBuffer(info, if (first) 1_000 else 0)
                    first = false
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIndex >= 0 -> {
                            lastOutput = System.nanoTime()
                            if (info.size > 0) {
                                val bar = (info.presentationTimeUs * BARS / durationUs).toInt().coerceIn(0, BARS - 1)
                                val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                var sum = 0.0
                                var n = 0
                                // Every 8th sample is plenty for an outline and keeps this fast.
                                if (floatPcm) {
                                    val fb = buf.asFloatBuffer()
                                    var i = 0
                                    while (i < fb.limit()) { val v = fb.get(i); sum += v * v; n++; i += 8 }
                                } else {
                                    val sb = buf.asShortBuffer()
                                    var i = 0
                                    while (i < sb.limit()) { val v = sb.get(i) / 32768.0; sum += v * v; n++; i += 8 }
                                }
                                sums[bar] += sum
                                counts[bar] += n
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                if (inputDone && !outputDone && System.nanoTime() - lastOutput > 3_000_000_000L) break
            }
            // Normalise to the loudest bar, with a gentle curve so quiet parts still show.
            // Average loudness (RMS) per slice, normalised so loud and quiet sections stand apart.
            val rms = FloatArray(BARS) { if (counts[it] > 0) sqrt(sums[it] / counts[it]).toFloat() else 0f }
            val max = rms.maxOrNull()?.takeIf { it > 0f } ?: return null
            return FloatArray(BARS) { (rms[it] / max).pow(1.5f).coerceIn(0.05f, 1f) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun readCache(file: File): FloatArray? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val n = input.readInt()
            if (n != BARS) return null
            FloatArray(n) { input.readFloat() }
        }
    }.getOrNull()

    private fun writeCache(file: File, bars: FloatArray) {
        runCatching {
            file.parentFile?.mkdirs()
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(bars.size)
                bars.forEach(out::writeFloat)
            }
        }
    }
}
