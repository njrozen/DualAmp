package com.dualamp.mix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.dualamp.library.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the mixer needs to know about a song: its tempo and where the beats fall, its key, and a three-band waveform
 * (lows, mids, highs) for the mix editor.
 */
class Analysis(
    val songId: Long,
    val durationMs: Long,
    /** Beats per minute; 0 when no steady beat was found. */
    val bpm: Float,
    /** Time of a beat (the beat grid's anchor), in ms. Every other beat is this plus whole beats. */
    val beatMs: Float,
    /** 0-11 major (C..B), 12-23 minor (Cm..Bm); -1 when unknown. */
    val key: Int,
    /** Loudness per band, [WAVE_RATE] points a second, 0-255. */
    val low: ByteArray,
    val mid: ByteArray,
    val high: ByteArray,
    /** Time of a bar's first beat (a downbeat, on the beat grid): bars are this plus whole bars of 4 beats. */
    val barMs: Float = beatMs,
) {
    val beatLengthMs: Float get() = if (bpm > 0) 60_000f / bpm else 500f
    val barLengthMs: Float get() = beatLengthMs * 4
    val keyName: String get() = keyName(key)
    val camelot: String get() = camelot(key)

    /** The beat nearest [ms]. */
    fun snapToBeat(ms: Double): Double {
        if (bpm <= 0) return ms
        val b = beatLengthMs.toDouble()
        return barMs + Math.round((ms - barMs) / b) * b
    }

    /** The bar line (first beat of a bar) nearest [ms]. */
    fun snapToBar(ms: Double): Double {
        if (bpm <= 0) return ms
        val b = barLengthMs.toDouble()
        return barMs + Math.round((ms - barMs) / b) * b
    }

    /** Whether the beat at [ms] (on the grid) starts a bar. */
    fun isBarLine(ms: Double): Boolean = bpm > 0 && Math.floorMod(Math.round((ms - barMs) / beatLengthMs), 4L) == 0L

    companion object {
        const val WAVE_RATE = 100
        private val NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

        fun keyName(key: Int): String = when {
            key < 0 -> "?"
            key < 12 -> NAMES[key]
            else -> NAMES[key - 12] + "m"
        }

        /** Camelot wheel code (8B = C major, 8A = A minor): neighbours on the wheel mix well. */
        fun camelotNumber(key: Int): Int {
            val major = if (key < 12) key else (key - 12 + 3) % 12
            return ((major * 7) % 12 + 7) % 12 + 1
        }

        fun camelot(key: Int): String = if (key < 0) "?" else "${camelotNumber(key)}${if (key < 12) "B" else "A"}"

        /** How well two keys mix: 2 = same key, 1 = next to each other on the Camelot wheel, 0 = they clash, -1 unknown. */
        fun keyMatch(a: Int, b: Int): Int {
            if (a < 0 || b < 0) return -1
            if (a == b) return 2
            val na = camelotNumber(a); val nb = camelotNumber(b)
            val sameMode = (a < 12) == (b < 12)
            val step = minOf((na - nb).mod(12), (nb - na).mod(12))
            return if ((sameMode && step == 1) || (!sameMode && step == 0)) 1 else 0
        }
    }
}

/** Analyses songs in the background, one at a time, and keeps the results (in memory and on disk). */
object SongAnalyzer {
    private const val TAG = "SongAnalyzer"
    private const val VERSION = 4
    private lateinit var dir: File
    private lateinit var app: Context
    private val _results = MutableStateFlow<Map<Long, Analysis>>(emptyMap())
    val results: StateFlow<Map<Long, Analysis>> = _results.asStateFlow()
    private val failed = HashSet<Long>()
    private val queued = HashSet<Long>()
    private val work = Channel<Song>(Channel.UNLIMITED)
    // One background-priority thread: decoding and analysing songs must never take CPU from playback (two decks play
    // at once during a transition).
    private val scope = CoroutineScope(SupervisorJob() + java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "SongAnalyzer")
    }.asCoroutineDispatcher())
    private val _busy = MutableStateFlow(0)
    /** Songs waiting to be analysed. */
    val busy: StateFlow<Int> = _busy.asStateFlow()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        dir = File(app.filesDir, "mix/analysis/v$VERSION")
        scope.launch {
            // Results from older versions of the analysis are redone.
            File(app.filesDir, "mix/analysis").listFiles()?.filter { it.name != "v$VERSION" }?.forEach { it.deleteRecursively() }
        }
        scope.launch {
            for (song in work) {
                try {
                    val a = readCache(song) ?: withTimeoutOrNull(90_000) { analyse(app, song) }?.also { writeCache(it, song) }
                    if (a != null) _results.value = _results.value + (song.id to a)
                    else synchronized(failed) { failed += song.id }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Could not analyse ${song.path}", e)
                    synchronized(failed) { failed += song.id }
                } finally {
                    synchronized(queued) { queued -= song.id; _busy.value = queued.size }
                }
            }
        }
    }

    operator fun get(id: Long): Analysis? = _results.value[id]

    fun hasFailed(id: Long) = synchronized(failed) { id in failed }

    /** Analyses these songs (if not done already); [first] jump the line. */
    fun request(songs: List<Song>, first: Boolean = false) {
        if (!::app.isInitialized) return
        val todo = songs.filter { it.id !in _results.value && !hasFailed(it.id) }
        if (todo.isEmpty()) return
        // Cached results load straight away.
        scope.launch {
            val list = if (first) todo else todo
            for (s in list) {
                val add = synchronized(queued) { if (s.id in queued) false else { queued += s.id; _busy.value = queued.size; true } }
                if (add) work.trySend(s)
            }
        }
    }

    private fun cacheFile(song: Song) = File(dir, "${song.id}_${song.durationMs}.bin")

    private fun readCache(song: Song): Analysis? = runCatching {
        val f = cacheFile(song)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { i ->
            val bpm = i.readFloat(); val beat = i.readFloat(); val bar = i.readFloat(); val key = i.readInt(); val dur = i.readLong()
            val n = i.readInt()
            val low = ByteArray(n).also { i.readFully(it) }
            val mid = ByteArray(n).also { i.readFully(it) }
            val high = ByteArray(n).also { i.readFully(it) }
            Analysis(song.id, dur, bpm, beat, key, low, mid, high, bar)
        }
    }.getOrNull()

    private fun writeCache(a: Analysis, song: Song) {
        runCatching {
            dir.mkdirs()
            DataOutputStream(cacheFile(song).outputStream().buffered()).use { o ->
                o.writeFloat(a.bpm); o.writeFloat(a.beatMs); o.writeFloat(a.barMs); o.writeInt(a.key); o.writeLong(a.durationMs)
                o.writeInt(a.low.size); o.write(a.low); o.write(a.mid); o.write(a.high)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Decoding: the whole song as mono, about 11 kHz (plenty for beats, key and an outline).

    private class Pcm(val samples: FloatArray, val rate: Int)

    private suspend fun decode(context: Context, song: Song): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, song.uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var factor = maxOf(1, (rate / 11025.0).roundToInt())
            val durMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else song.durationMs
            var out = FloatArray(((durMs + 2000) * rate / 1000 / factor).toInt().coerceAtLeast(1024))
            var n = 0
            var acc = 0f; var accN = 0
            val info = MediaCodec.BufferInfo()
            var floatPcm = false
            var inputDone = false; var outputDone = false
            var lastOutput = System.nanoTime()
            while (!outputDone) {
                coroutineContext.ensureActive()
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) { codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
                var first = true
                while (!outputDone) {
                    val outIndex = codec.dequeueOutputBuffer(info, if (first) 1_000 else 0)
                    first = false
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE) && n == 0) {
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); factor = maxOf(1, (rate / 11025.0).roundToInt())
                        }
                        continue
                    }
                    if (outIndex < 0) continue
                    lastOutput = System.nanoTime()
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val ch = channels.coerceAtLeast(1)
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            var i = 0
                            while (i + ch <= fb.limit()) {
                                var v = 0f; for (c in 0 until ch) v += fb.get(i + c)
                                acc += v / ch; accN++; i += ch
                                if (accN == factor) { if (n == out.size) out = out.copyOf(out.size * 3 / 2); out[n++] = acc / factor; acc = 0f; accN = 0 }
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            var i = 0
                            while (i + ch <= sb.limit()) {
                                var v = 0f; for (c in 0 until ch) v += sb.get(i + c)
                                acc += v / ch / 32768f; accN++; i += ch
                                if (accN == factor) { if (n == out.size) out = out.copyOf(out.size * 3 / 2); out[n++] = acc / factor; acc = 0f; accN = 0 }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                if (inputDone && !outputDone && System.nanoTime() - lastOutput > 3_000_000_000L) break
            }
            if (n < 1024) return null
            return Pcm(out.copyOf(n), rate / factor)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private suspend fun analyse(context: Context, song: Song): Analysis? {
        val pcm = decode(context, song) ?: return null
        coroutineContext.ensureActive()
        return analysePcm(song.id, pcm.samples, pcm.rate)
    }

    /** Public for tests: everything below works on plain mono samples. */
    fun analysePcm(songId: Long, x: FloatArray, rate: Int): Analysis {
        val durationMs = x.size * 1000L / rate
        // Three bands with one-pole filters: lows under ~200 Hz, highs over ~2.5 kHz, mids in between.
        val aLow = 1 - exp(-2 * Math.PI * 200 / rate).toFloat()
        val aHigh = 1 - exp(-2 * Math.PI * 2500 / rate).toFloat()
        // 10 ms frames, exactly (at 11 025 Hz a frame is 110.25 samples).
        val frames = (x.size * 100L / rate).toInt()
        fun frameStart(f: Int) = (f.toLong() * rate / 100).toInt()
        val eLow = FloatArray(frames); val eMid = FloatArray(frames); val eHigh = FloatArray(frames)
        val pLow = FloatArray(frames); val pMid = FloatArray(frames); val pHigh = FloatArray(frames)
        var lp1 = 0f; var lp1b = 0f; var lp2 = 0f
        for (f in 0 until frames) {
            var sl = 0f; var sm = 0f; var sh = 0f
            var ml = 0f; var mm = 0f; var mh = 0f
            val n0 = frameStart(f); val n1 = frameStart(f + 1); val hop = (n1 - n0).coerceAtLeast(1)
            for (i in n0 until n1) {
                val v = x[i]
                lp1 += aLow * (v - lp1); lp1b += aLow * (lp1 - lp1b) // two poles for the lows
                lp2 += aHigh * (v - lp2)
                val lo = lp1b; val hi = v - lp2; val mi = lp2 - lp1b
                sl += lo * lo; sm += mi * mi; sh += hi * hi
                ml = maxOf(ml, abs(lo)); mm = maxOf(mm, abs(mi)); mh = maxOf(mh, abs(hi))
            }
            eLow[f] = sl / hop; eMid[f] = sm / hop; eHigh[f] = sh / hop
            pLow[f] = ml; pMid[f] = mm; pHigh[f] = mh
        }

        // Onsets: rises in loudness per band (the lows count double: kicks carry the beat).
        val nov = FloatArray(frames)
        for (f in 1 until frames) {
            fun rise(e: FloatArray) = maxOf(0f, (ln(e[f] + 1e-7) - ln(e[f - 1] + 1e-7)).toFloat())
            nov[f] = 2 * rise(eLow) + rise(eMid) + rise(eHigh)
        }
        // The kicks alone (lows), to put the grid on them rather than on off-beat hats or bass.
        val novLow = FloatArray(frames)
        for (f in 1 until frames) novLow[f] = maxOf(0f, (ln(eLow[f] + 1e-7) - ln(eLow[f - 1] + 1e-7)).toFloat())
        val novC = centred(nov)
        val (bpm, beatAny) = tempo(novC)
        val beatMs = if (bpm > 0) kickPhase(centred(novLow), bpm, beatAny) else beatAny
        val barMs = if (bpm > 0) downbeat(eLow, eMid, eHigh, bpm, beatMs) else beatMs
        val key = key(x, rate)

        // Waveform at WAVE_RATE points a second from the 10 ms peaks.
        val step = 100 / Analysis.WAVE_RATE
        val points = frames / step
        fun wave(p: FloatArray): ByteArray {
            val pts = FloatArray(points) { i -> var m = 0f; for (k in 0 until step) m = maxOf(m, p[i * step + k]); m }
            val sorted = pts.copyOf().also { it.sort() }
            val ref = sorted[(sorted.size * 0.995).toInt().coerceIn(0, sorted.size - 1)].coerceAtLeast(1e-4f)
            return ByteArray(points) { i -> (sqrt((pts[i] / ref).coerceIn(0f, 1f)) * 255).roundToInt().toByte() }
        }
        return Analysis(songId, durationMs, bpm, beatMs, key, wave(pLow), wave(pMid), wave(pHigh), barMs)
    }

    /**
     * Tempo and beat position from the onset curve (100 points a second): autocorrelation finds the rough tempo
     * (preferring 80-160 BPM), then folding the curve at nearby tempos finds the exact one and where its beats fall.
     */
    fun tempo(nov: FloatArray): Pair<Float, Float> {
        val n = nov.size
        if (n < 1000) return 0f to 0f
        var best = 0.0; var bestLag = 0
        for (lag in 30..110) { // 200 .. 55 BPM
            var s = 0.0
            for (i in lag until n) s += nov[i] * nov[i - lag]
            s /= (n - lag)
            val bpm = 6000.0 / lag
            val w = exp(-0.5 * Math.pow(ln(bpm / 122) / ln(2.0) / 0.6, 2.0))
            if (s * w > best) { best = s * w; bestLag = lag }
        }
        if (bestLag == 0 || best <= 0) return 0f to 0f
        val coarse = 6000.0 / bestLag
        val hist = DoubleArray(FOLD_BINS)
        fun fold(bpm: Double) = fold(nov, bpm, hist)
        /** The exact tempo near [c]: how sharply the onsets line up on it, the tempo, and where its beats fall. */
        fun refine(c: Double): Triple<Double, Double, Double> {
            var bestBpm = c; var bestScore = -1.0; var bestPhase = 0.0
            var b = c * 0.96
            while (b <= c * 1.04) {
                val (score, phase) = fold(b)
                if (score > bestScore) { bestScore = score; bestBpm = b; bestPhase = phase }
                b += 0.02
            }
            // Most music sits on a whole number of BPM.
            val rounded = Math.round(bestBpm).toDouble()
            if (abs(rounded - bestBpm) < 0.25) {
                val (score, phase) = fold(rounded)
                if (score >= bestScore * 0.97) { bestBpm = rounded; bestPhase = phase }
            }
            return Triple(bestScore, bestBpm, bestPhase)
        }
        // The autocorrelation can also land on 2/3 or 3/4 of the tempo (or 3/2, 4/3): a steady kick every 353 ms
        // repeats at 530 ms too, every three beats being two "beats" of 113 BPM. At the true tempo the onsets fold
        // onto one point of the beat; at those they spread over two or three, so the sharpest fold wins.
        var best3 = refine(coarse)
        for (r in doubleArrayOf(1.5, 2.0 / 3, 4.0 / 3, 0.75)) {
            val c = coarse * r
            if (c < 55 || c > 200) continue
            val other = refine(c)
            if (other.first > best3.first * 1.15) best3 = other
        }
        return best3.second.toFloat() to best3.third.toFloat()
    }

    private const val FOLD_BINS = 96

    /**
     * Folds the onset curve at [bpm]: how strongly its onsets gather at one point of the beat (1 = not at all), and
     * that point (a beat time, in ms).
     */
    private fun fold(nov: FloatArray, bpm: Double, hist: DoubleArray = DoubleArray(FOLD_BINS)): Pair<Double, Double> {
        val bins = hist.size
        val period = 6000.0 / bpm // in 10 ms frames
        java.util.Arrays.fill(hist, 0.0)
        for (i in nov.indices) {
            val v = nov[i]; if (v <= 0f) continue
            val ph = (i % period) / period * bins
            hist[ph.toInt().coerceIn(0, bins - 1)] += v.toDouble()
        }
        fun sm(b: Int) = hist[(b - 1 + bins) % bins] * 0.5 + hist[(b + bins) % bins] + hist[(b + 1) % bins] * 0.5
        var bi = 0; var bv = -1.0
        for (b in 0 until bins) { val v = sm(b); if (v > bv) { bv = v; bi = b } }
        // Between bins: the peak of a parabola through the best bin and its neighbours.
        val l = sm(bi - 1); val r = sm(bi + 1)
        val d = (l - 2 * bv + r).let { if (it < 0) (0.5 * (l - r) / it).coerceIn(-0.5, 0.5) else 0.0 }
        val mean = hist.sum() / bins * 2
        return (bv / (mean + 1e-9)) to ((bi + d + 0.5) / bins * period * 10) // contrast, beat time in ms
    }

    /** Takes away the curve's local average (half a second around) so only its peaks count. */
    private fun centred(nov: FloatArray): FloatArray {
        val frames = nov.size
        val out = FloatArray(frames)
        val w = 25
        var sum = 0f
        for (f in 0 until frames) {
            sum += nov[f]; if (f >= 2 * w + 1) sum -= nov[f - 2 * w - 1]
            val c = f - w
            if (c >= 0) out[c] = maxOf(0f, nov[c] - sum / (2 * w + 1))
        }
        return out
    }

    /**
     * Where the beats fall going by the kicks alone, when the song has clear ones: in techno and house the hats and
     * the bass often hit between the beats, and the whole-sound onsets can put the grid there. Otherwise [fallback].
     */
    private fun kickPhase(novLow: FloatArray, bpm: Float, fallback: Float): Float {
        val (score, phase) = fold(novLow, bpm.toDouble())
        return if (score >= 1.6) phase.toFloat() else fallback
    }

    /**
     * Which beat starts the bars. Songs change (a new part, drums coming in or out, a crash) on the first beat of a
     * bar, so the beat (of the four) where the sound changes the most from the bar before to the bar after is
     * taken as the downbeat.
     */
    private fun downbeat(eLow: FloatArray, eMid: FloatArray, eHigh: FloatArray, bpm: Float, beatMs: Float): Float {
        val beat = 6000.0 / bpm // frames
        val frames = eLow.size
        val first = beatMs / 10.0 - Math.floor(beatMs / 10.0 / beat) * beat
        val nBeats = ((frames - first) / beat).toInt()
        if (nBeats < 16) return beatMs
        // Loudness of each band through each beat (log).
        val bands = arrayOf(eLow, eMid, eHigh)
        val e = Array(3) { DoubleArray(nBeats) }
        for (j in 0 until nBeats) {
            val f0 = (first + j * beat).toInt(); val f1 = (first + (j + 1) * beat).toInt().coerceAtMost(frames)
            for (k in 0..2) {
                var s = 0.0
                for (f in f0 until f1) s += bands[k][f]
                e[k][j] = ln(s / (f1 - f0).coerceAtLeast(1) + 1e-7)
            }
        }
        // How much the sound changes from the bar before each beat to the bar after it. (Not beat to beat: snares and
        // claps on beats 2 and 4 would win that.)
        val d = DoubleArray(nBeats)
        for (j in 4..nBeats - 4) {
            var s = 0.0
            for (k in 0..2) {
                var before = 0.0; var after = 0.0
                for (q in 1..4) { before += e[k][j - q]; after += e[k][j + q - 1] }
                s += abs(after - before) / 4
            }
            d[j] = s
        }
        // A change counts where it peaks (a part starting on beat 1 also shows, less, at beats 4 and 2).
        val score = DoubleArray(4)
        for (j in 5 until nBeats - 4) score[j % 4] += maxOf(0.0, d[j] - maxOf(d[j - 1], d[j + 1]))
        val best = (0..3).maxBy { score[it] }
        val bar = beat * 4
        val t = first + best * beat
        return ((t - Math.floor(t / bar) * bar) * 10).toFloat()
    }

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** Key from the song's pitch classes (chroma) against the Krumhansl key profiles. */
    fun key(x: FloatArray, rate: Int): Int {
        val size = 4096
        if (x.size < size * 4) return -1
        val hopK = rate / 4 // four frames a second
        val re = DoubleArray(size); val im = DoubleArray(size)
        val window = DoubleArray(size) { 0.5 - 0.5 * Math.cos(2 * Math.PI * it / (size - 1)) }
        val pcOfBin = IntArray(size / 2) { k ->
            val f = k.toDouble() * rate / size
            if (f < 55 || f > 2000) -1 else (((69 + 12 * ln(f / 440) / ln(2.0)).roundToInt()) % 12 + 12) % 12
        }
        val chroma = DoubleArray(12)
        val frame = DoubleArray(12)
        var start = 0
        while (start + size <= x.size) {
            for (i in 0 until size) { re[i] = x[start + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            java.util.Arrays.fill(frame, 0.0)
            for (k in 1 until size / 2) {
                val pc = pcOfBin[k]; if (pc < 0) continue
                frame[pc] += sqrt(re[k] * re[k] + im[k] * im[k])
            }
            val total = frame.sum()
            if (total > 1e-6) for (c in 0 until 12) chroma[c] += frame[c] / total
            start += hopK
        }
        if (chroma.sum() <= 0) return -1
        var bestKey = -1; var best = -2.0
        for (tonic in 0 until 12) for (minor in 0..1) {
            val prof = if (minor == 0) MAJOR else MINOR
            val r = correlation(chroma) { prof[(it - tonic + 12) % 12] }
            if (r > best) { best = r; bestKey = tonic + 12 * minor }
        }
        return bestKey
    }

    private inline fun correlation(a: DoubleArray, b: (Int) -> Double): Double {
        val ma = a.average(); var mb = 0.0
        for (i in 0 until 12) mb += b(i)
        mb /= 12
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until 12) { val x = a[i] - ma; val y = b(i) - mb; num += x * y; da += x * x; db += y * y }
        return num / sqrt(da * db + 1e-12)
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
