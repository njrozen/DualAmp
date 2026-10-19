package com.dualamp.library

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import android.util.LruCache
import com.dualamp.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A song's lyrics. [synced]: each line has the time it is sung (from a .lrc file, timed tags or lrclib.net);
 * otherwise the lines are spread evenly over the song.
 */
class Lyrics(val lines: List<Line>, val synced: Boolean, val source: String) {
    data class Line(val timeMs: Long, val text: String)

    /** The line being sung at [positionMs] (-1 before the first one). */
    fun indexAt(positionMs: Long, durationMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (!synced) {
            if (durationMs <= 0) return 0
            return ((positionMs.toDouble() / durationMs) * lines.size).toInt().coerceIn(0, lines.size - 1)
        }
        var lo = 0; var hi = lines.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    companion object {
        private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
        private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
        private val TAG = Regex("""^\[[a-zA-Z]+:.*]$""")

        /** Reads LRC text ("[01:02.34]line"); text without times becomes unsynced lyrics. */
        fun parse(text: String, source: String): Lyrics? {
            // "null" is what an empty lyrics field turns into on the way (lrclib.net, some taggers): no lyrics.
            if (text.isBlank() || text.trim().equals("null", ignoreCase = true)) return null
            val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val timed = ArrayList<Line>()
            val plain = ArrayList<Line>()
            for (raw in text.lineSequence()) {
                val line = raw.trim().removePrefix("﻿")
                val times = TIME.findAll(line).toList()
                if (times.isNotEmpty() && line.startsWith("[")) {
                    val words = line.substring(times.last().range.last + 1).replace(WORD_TIME, "").trim()
                    for (t in times) {
                        val (m, s, f) = t.destructured
                        val frac = when (f.length) { 0 -> 0L; 1 -> f.toLong() * 100; 2 -> f.toLong() * 10; else -> f.take(3).toLong() }
                        // LRC's [offset:] is how much sooner the lines are sung, in ms.
                        timed += Line((m.toLong() * 60_000 + s.toLong() * 1000 + frac - offset).coerceAtLeast(0), words)
                    }
                } else if (line.isNotEmpty() && !TAG.matches(line) && !line.equals("null", ignoreCase = true)) {
                    plain += Line(0, line)
                }
            }
            if (timed.any { it.text.isNotBlank() }) return Lyrics(timed.sortedBy { it.timeMs }, true, source)
            if (plain.isNotEmpty()) return Lyrics(plain, false, source)
            return null
        }
    }
}

/** Finds lyrics: a .lrc file next to the song, lyrics in the song's tags, then lrclib.net (Settings > Lyrics). */
object LyricsRepository {
    private const val TAG = "Lyrics"
    private val memory = LruCache<String, Lyrics>(32)
    /** Songs known to have no lyrics anywhere, this session. */
    private val missing = HashSet<String>()

    private fun key(song: Song) = song.remoteUrl?.let { "r" + it.hashCode().toUInt().toString(16) } ?: song.id.toString()

    suspend fun load(context: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val key = key(song)
        memory.get(key)?.let { return@withContext it }
        if (key in missing) return@withContext null
        val found = runCatching { find(context.applicationContext, song, key) }
            .onFailure { Log.w(TAG, "Lyrics for ${song.title}", it) }.getOrNull()
        if (found != null) memory.put(key, found) else missing += key
        found
    }

    /** Forget a "no lyrics" answer (e.g. after online lookup is turned on). */
    fun retryMissing() = missing.clear()

    private fun find(context: Context, song: Song, key: String): Lyrics? {
        if (!song.isRemote) {
            lrcFile(context, song)?.let { Lyrics.parse(it, "lrc")?.let { l -> return l } }
            embedded(context, song)?.let { return it }
        }
        // Answers from lrclib.net are kept on disk (also "none", for a week).
        val cache = File(context.filesDir, "lyrics/$key.lrc")
        val none = File(context.filesDir, "lyrics/$key.none")
        if (cache.exists()) return Lyrics.parse(cache.readText(), "lrclib")
        if (!Prefs.lyricsOnline.value) return null
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7L * 24 * 3600 * 1000) return null
        val text = online(song)?.takeUnless { it.isBlank() || it.trim().equals("null", ignoreCase = true) }
        cache.parentFile?.mkdirs()
        if (text == null) { none.writeText(""); return null }
        none.delete()
        cache.writeText(text)
        return Lyrics.parse(text, "lrclib")
    }

    // --- .lrc next to the song -------------------------------------------------------------------------

    private fun lrcFile(context: Context, song: Song): String? {
        val base = song.path.substringBeforeLast('.', song.path)
        for (path in listOf("$base.lrc", "$base.LRC")) {
            val f = File(path)
            // Android 13+ only lets apps read audio files directly; a folder granted in Settings > Lyrics covers the rest.
            runCatching { if (f.canRead()) return f.readText() }
            readFromGrantedFolder(context, path)?.let { return it }
        }
        return null
    }

    private fun readFromGrantedFolder(context: Context, path: String): String? {
        for (perm in context.contentResolver.persistedUriPermissions) {
            val tree = perm.uri
            val root = LibraryRepository.pathFromTree(tree)?.trimEnd('/') ?: continue
            if (!path.startsWith("$root/")) continue
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val docId = treeId.trimEnd('/') + "/" + path.removePrefix("$root/")
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val text = runCatching { context.contentResolver.openInputStream(doc)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text != null) return text
        }
        return null
    }

    // --- lyrics in the song's tags (ID3 USLT / SYLT, FLAC LYRICS) --------------------------------------

    private fun embedded(context: Context, song: Song): Lyrics? =
        context.contentResolver.openInputStream(song.uri)?.use { input ->
            val head = ByteArray(10)
            if (input.readFully(head) < 4) return null
            when {
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> id3(input, head)
                head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> flac(input, head)
                else -> null
            }
        }

    private fun InputStream.readFully(buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }

    private fun synchsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)

    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun id3(input: InputStream, head: ByteArray): Lyrics? {
        val version = head[3].toInt()
        val size = synchsafe(head, 6)
        if (size <= 0 || size > 64 shl 20) return null
        val tag = ByteArray(size)
        val read = input.readFully(tag)
        var pos = 0
        var unsynced: Lyrics? = null
        while (pos + 10 <= read) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val len = if (version >= 4) synchsafe(tag, pos + 4) else int32(tag, pos + 4)
            val start = pos + 10
            if (len <= 0 || start + len > read) break
            when (id) {
                "SYLT" -> sylt(tag, start, len)?.let { return it }
                "USLT" -> if (unsynced == null) unsynced = uslt(tag, start, len)
            }
            pos = start + len
        }
        return unsynced
    }

    private fun charset(enc: Int) = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** End of a terminated string from [from] (exclusive), for ID3 encoding [enc]. */
    private fun terminator(b: ByteArray, from: Int, end: Int, enc: Int): Int {
        if (enc == 1 || enc == 2) {
            var i = from
            while (i + 1 < end) { if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i; i += 2 }
            return end
        }
        var i = from
        while (i < end) { if (b[i].toInt() == 0) return i; i++ }
        return end
    }

    private fun uslt(b: ByteArray, start: Int, len: Int): Lyrics? {
        val end = start + len
        val enc = b[start].toInt()
        val descEnd = terminator(b, start + 4, end, enc)
        val textStart = descEnd + if (enc == 1 || enc == 2) 2 else 1
        if (textStart >= end) return null
        val text = String(b, textStart, end - textStart, charset(enc)).trim('\u0000')
        // Many taggers store LRC (with times) in USLT.
        return Lyrics.parse(text, "tags")
    }

    private fun sylt(b: ByteArray, start: Int, len: Int): Lyrics? {
        val end = start + len
        val enc = b[start].toInt()
        if (b[start + 4].toInt() != 2) return null // only millisecond timestamps
        val wide = enc == 1 || enc == 2
        var p = terminator(b, start + 6, end, enc) + if (wide) 2 else 1
        val lines = ArrayList<Lyrics.Line>()
        while (p < end) {
            val t = terminator(b, p, end, enc)
            val text = String(b, p, t - p, charset(enc)).trim().trim('\u0000')
            p = t + if (wide) 2 else 1
            if (p + 4 > end) break
            lines += Lyrics.Line(int32(b, p).toLong() and 0xffffffffL, text)
            p += 4
        }
        return if (lines.any { it.text.isNotBlank() }) Lyrics(lines.sortedBy { it.timeMs }, true, "tags") else null
    }

    private fun flac(input: InputStream, head: ByteArray): Lyrics? {
        // After "fLaC": metadata blocks, each a 4-byte header (last flag + type, 24-bit length) and its body.
        val s = java.io.SequenceInputStream(java.io.ByteArrayInputStream(head, 4, head.size - 4), input)
        val header = ByteArray(4)
        while (s.readFully(header) == 4) {
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7f
            val len = ((header[1].toInt() and 0xff) shl 16) or ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            if (type == 4) { val body = ByteArray(len); return if (s.readFully(body) == len) vorbis(body) else null }
            if (last) return null
            var skip = len.toLong()
            while (skip > 0) { val n = s.skip(skip); if (n <= 0) return null; skip -= n }
        }
        return null
    }

    private fun vorbis(b: ByteArray): Lyrics? {
        fun le32(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        var p = 0
        val vendor = le32(p); p += 4 + vendor
        if (p + 4 > b.size) return null
        val count = le32(p); p += 4
        repeat(count) {
            if (p + 4 > b.size) return null
            val n = le32(p); p += 4
            if (n < 0 || p + n > b.size) return null
            val entry = String(b, p, n, Charsets.UTF_8); p += n
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val name = entry.substring(0, eq).uppercase()
                if (name == "LYRICS" || name == "UNSYNCEDLYRICS" || name == "SYNCEDLYRICS") Lyrics.parse(entry.substring(eq + 1), "tags")?.let { return it }
            }
        }
        return null
    }

    // --- lrclib.net --------------------------------------------------------------------------------------

    /** "05. Desperado" -> "Desperado"; "Sky (feat. X)" -> "Sky". */
    private fun cleanTitle(title: String) = title
        .replace(Regex("""^\d{1,3}\s*[.\-]\s+"""), "")
        .replace(Regex("""\s*[(\[](feat|ft|with)\.?\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun cleanArtist(artist: String) = artist
        .split(Regex("""\s*(,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE)).first().trim()

    private fun online(song: Song): String? {
        val title = cleanTitle(song.title)
        val artist = cleanArtist(song.artist)
        if (title.isBlank() || artist.isBlank() || artist.equals("<unknown>", true) || artist.equals("Unknown Artist", true)) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val exact = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}" +
            "&album_name=${enc(song.album)}&duration=${song.durationMs / 1000}"
        get(exact)?.let { body -> pick(JSONObject(body))?.let { return it } }
        val search = get("https://lrclib.net/api/search?artist_name=${enc(artist)}&track_name=${enc(title)}") ?: return null
        val results = JSONArray(search)
        // Prefer timed lyrics from a version about as long as this one.
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }
            .sortedBy { kotlin.math.abs(it.optDouble("duration", 0.0) * 1000 - song.durationMs) }
        candidates.firstOrNull { it.optString("syncedLyrics").isNotBlank() && kotlin.math.abs(it.optDouble("duration", 0.0) * 1000 - song.durationMs) < 5000 }
            ?.let { return it.getString("syncedLyrics") }
        return candidates.firstNotNullOfOrNull { pick(it) }
    }

    private fun pick(o: JSONObject): String? {
        if (o.optBoolean("instrumental")) return null
        o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        return o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
    }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "DualAmp (https://github.com/njrozen/DualAmp)")
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}
