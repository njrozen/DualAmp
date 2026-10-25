package com.dualamp.library

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream

/**
 * The comment tag of each song (Android's media store does not provide it). Read from the file itself, only the tags
 * at its start (pictures are skipped, not read): ID3v2 COMM (MP3), Vorbis COMMENT / DESCRIPTION (FLAC, Ogg, Opus) and
 * MP4 ©cmt (M4A). Kept in a cache by song id and file date, so only new or changed files are read on later scans.
 */
object Comments {
    private const val TAG = "Comments"
    private const val CACHE_VERSION = 1

    private class Entry(val modified: Long, val comment: String)

    /** Comment per song id ("" when it has none). */
    fun read(context: Context, songs: List<Song>): Map<Long, String> {
        val file = File(context.filesDir, "comments.cache")
        val cache = load(file)
        val out = HashMap<Long, String>(songs.size)
        var readNow = 0
        for (s in songs) {
            val cached = cache[s.id]
            if (cached != null && cached.modified == s.modified) { out[s.id] = cached.comment; continue }
            val c = runCatching { context.contentResolver.openInputStream(s.uri)?.use { commentOf(BufferedInputStream(it, 16384)) } }
                .getOrNull().orEmpty()
            out[s.id] = c
            cache[s.id] = Entry(s.modified, c)
            readNow++
        }
        if (readNow > 0) {
            Log.i(TAG, "Read the comment tag of $readNow songs")
            // Drop songs that are gone.
            val ids = songs.map { it.id }.toHashSet()
            cache.keys.retainAll(ids)
            save(file, cache)
        }
        return out
    }

    private fun load(file: File): HashMap<Long, Entry> {
        val map = HashMap<Long, Entry>()
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_VERSION) return map
                repeat(input.readInt()) { map[input.readLong()] = Entry(input.readLong(), input.readUTF()) }
            }
        }
        return map
    }

    private fun save(file: File, map: Map<Long, Entry>) {
        runCatching {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(CACHE_VERSION)
                out.writeInt(map.size)
                for ((id, e) in map) { out.writeLong(id); out.writeLong(e.modified); out.writeUTF(e.comment.take(4000)) }
            }
        }
    }

    // --- reading ---------------------------------------------------------------------------------------------

    private fun InputStream.readFully(buf: ByteArray, len: Int = buf.size): Boolean {
        var n = 0
        while (n < len) { val r = read(buf, n, len - n); if (r < 0) return false; n += r }
        return true
    }

    private fun InputStream.skipFully(count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = skip(left)
            if (n <= 0) { if (read() < 0) return false; left-- } else left -= n
        }
        return true
    }

    /** The comment in one file's tags ("" if none). */
    internal fun commentOf(input: InputStream): String {
        val head = ByteArray(10)
        if (!input.readFully(head)) return ""
        val s = String(head, Charsets.ISO_8859_1)
        return when {
            s.startsWith("ID3") -> id3(input, head)
            s.startsWith("fLaC") -> flac(input, head)
            s.startsWith("OggS") -> ogg(input, head)
            s.substring(4, 8) == "ftyp" -> mp4(input, head)
            else -> ""
        }
    }

    private fun synchsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    /** ID3v2.3 / 2.4: every COMM frame's text, joined. Other frames (pictures too) are skipped unread. */
    private fun id3(input: InputStream, head: ByteArray): String {
        val version = head[3].toInt()
        if (version < 3) return ""
        var left = synchsafe(head, 6).toLong()
        val found = ArrayList<String>()
        val frame = ByteArray(10)
        while (left >= 10) {
            if (!input.readFully(frame)) break
            left -= 10
            val id = String(frame, 0, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || !id.all { it.isLetterOrDigit() }) break
            val size = (if (version >= 4) synchsafe(frame, 4) else be32(frame, 4)).toLong()
            if (size <= 0 || size > left) break
            if (id == "COMM" && size < 65536) {
                val body = ByteArray(size.toInt())
                if (!input.readFully(body)) break
                comm(body)?.let { found += it }
            } else if (!input.skipFully(size)) break
            left -= size
        }
        return found.joinToString("\n")
    }

    private fun comm(b: ByteArray): String? {
        if (b.size < 5) return null
        val enc = b[0].toInt()
        val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        val wide = enc == 1 || enc == 2
        // Language (3 bytes), short description (terminated), then the text.
        var i = 4
        if (wide) { while (i + 1 < b.size && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2; i += 2 }
        else { while (i < b.size && b[i].toInt() != 0) i++; i += 1 }
        if (i >= b.size) return null
        return String(b, i, b.size - i, cs).trim('\u0000', ' ', '\n')
    }

    /** FLAC: the Vorbis comment block's COMMENT / DESCRIPTION; other blocks (pictures too) skipped. */
    private fun flac(input: InputStream, head: ByteArray): String {
        // head: "fLaC" + the first block header (4 bytes) + 2 bytes of that block.
        var header = head.copyOfRange(4, 8)
        var pending = head.copyOfRange(8, 10)
        while (true) {
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7f
            val len = ((header[1].toInt() and 0xff) shl 16) or ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            if (type == 4) {
                val body = ByteArray(len)
                val take = minOf(pending.size, len)
                System.arraycopy(pending, 0, body, 0, take)
                if (take < len) {
                    val rest = ByteArray(len - take)
                    if (!input.readFully(rest)) return ""
                    System.arraycopy(rest, 0, body, take, rest.size)
                }
                return vorbis(body, 0)
            }
            if (last) return ""
            if (!input.skipFully((len - pending.size).toLong())) return ""
            header = ByteArray(4)
            if (!input.readFully(header)) return ""
            pending = ByteArray(0)
        }
    }

    /** Ogg Vorbis / Opus: the comment header is in the first pages; looks for it in the first 256 KB. */
    private fun ogg(input: InputStream, head: ByteArray): String {
        val buf = ByteArray(256 * 1024)
        System.arraycopy(head, 0, buf, 0, head.size)
        var n = head.size
        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        // "\u0003vorbis" (Vorbis) or "OpusTags" (Opus), then the comment list. Page headers in between are rare this early.
        val text = String(buf, 0, n, Charsets.ISO_8859_1)
        val at = text.indexOf("\u0003vorbis").let { if (it >= 0) it + 7 else text.indexOf("OpusTags").let { o -> if (o >= 0) o + 8 else -1 } }
        if (at < 0) return ""
        return runCatching { vorbis(buf.copyOfRange(at, n), 0) }.getOrDefault("")
    }

    private fun vorbis(b: ByteArray, start: Int): String {
        var p = start
        if (p + 4 > b.size) return ""
        p += 4 + le32(b, p) // vendor
        if (p + 4 > b.size) return ""
        val count = le32(b, p); p += 4
        val found = ArrayList<String>()
        repeat(count.coerceAtMost(10_000)) {
            if (p + 4 > b.size) return found.joinToString("\n")
            val len = le32(b, p); p += 4
            if (len < 0 || p + len > b.size) return found.joinToString("\n")
            val entry = String(b, p, len, Charsets.UTF_8); p += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (key == "COMMENT" || key == "DESCRIPTION") found += entry.substring(eq + 1)
            }
        }
        return found.joinToString("\n")
    }

    /** MP4 / M4A: moov > udta > meta > ilst > ©cmt > data. The audio (mdat) is skipped. */
    private fun mp4(input: InputStream, head: ByteArray): String {
        // head: the ftyp box header + 2 bytes of it.
        var boxSize = be32(head, 0).toLong()
        if (!input.skipFully(boxSize - 10)) return ""
        val h = ByteArray(8)
        while (input.readFully(h)) {
            boxSize = be32(h, 0).toLong() and 0xffffffffL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            if (boxSize < 8) return ""
            if (type == "moov") {
                if (boxSize > 64L * 1024 * 1024) return ""
                val body = ByteArray((boxSize - 8).toInt())
                if (!input.readFully(body)) return ""
                return findCmt(body, 0, body.size)
            }
            if (!input.skipFully(boxSize - 8)) return ""
        }
        return ""
    }

    private fun findCmt(b: ByteArray, from: Int, to: Int): String {
        var p = from
        while (p + 8 <= to) {
            val size = be32(b, p)
            if (size < 8 || p + size > to) return ""
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            when (type) {
                "udta", "ilst" -> findCmt(b, p + 8, p + size).let { if (it.isNotEmpty()) return it }
                "meta" -> findCmt(b, p + 12, p + size).let { if (it.isNotEmpty()) return it } // meta has 4 bytes of flags
                "©cmt" -> {
                    // data box: size, "data", 8 bytes of type / locale, then the text.
                    val d = p + 8
                    if (d + 16 <= p + size && String(b, d + 4, 4, Charsets.ISO_8859_1) == "data") {
                        val len = be32(b, d) - 16
                        if (len > 0 && d + 16 + len <= p + size) return String(b, d + 16, len, Charsets.UTF_8)
                    }
                }
            }
            p += size
        }
        return ""
    }
}

/** Songs whose comment tag says "leak", "leaks", "leaked" or "unreleased" (whole words, any case). */
object Leaks {
    private val WORDS = Regex("""\b(leak|leaks|leaked|unreleased)\b""", RegexOption.IGNORE_CASE)
    fun isLeak(comment: String?): Boolean = !comment.isNullOrBlank() && WORDS.containsMatchIn(comment)
}
