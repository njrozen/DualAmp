package com.dualamp.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Comment tags read from small files built here: MP3 (ID3v2.3 / 2.4), FLAC and M4A, with a picture before the comment. */
class CommentsTest {
    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte())
    private fun synchsafe(n: Int) = byteArrayOf(((n ushr 21) and 0x7f).toByte(), ((n ushr 14) and 0x7f).toByte(), ((n ushr 7) and 0x7f).toByte(), (n and 0x7f).toByte())
    private fun bytes(vararg parts: ByteArray) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    private fun read(b: ByteArray) = Comments.commentOf(ByteArrayInputStream(b))

    private fun id3(version: Int, frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = bytes(*frames.map { (id, data) ->
            bytes(id.toByteArray(Charsets.ISO_8859_1), if (version >= 4) synchsafe(data.size) else be32(data.size), byteArrayOf(0, 0), data)
        }.toTypedArray())
        return bytes("ID3".toByteArray(), byteArrayOf(version.toByte(), 0, 0), synchsafe(body.size), body, ByteArray(500) /* audio */)
    }

    private fun comm(text: String, enc: Int = 3) = bytes(
        byteArrayOf(enc.toByte()), "eng".toByteArray(),
        if (enc == 1) bytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()), byteArrayOf(0, 0)) else byteArrayOf(0),
        if (enc == 1) bytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()), text.toByteArray(Charsets.UTF_16LE)) else text.toByteArray(Charsets.UTF_8),
    )

    @Test fun mp3v23_afterPicture() {
        val apic = ByteArray(200_000) { 7 }
        val file = id3(3, listOf("TIT2" to bytes(byteArrayOf(3), "Song".toByteArray()), "APIC" to apic, "COMM" to comm("Leaked 2021")))
        assertEquals("Leaked 2021", read(file))
    }

    @Test fun mp3v24_utf16() {
        val file = id3(4, listOf("COMM" to comm("unreleased", enc = 1), "TALB" to bytes(byteArrayOf(3), "Album".toByteArray())))
        assertEquals("unreleased", read(file))
    }

    @Test fun mp3_noComment() {
        assertEquals("", read(id3(3, listOf("TIT2" to bytes(byteArrayOf(3), "Song".toByteArray())))))
    }

    @Test fun flac_afterPicture() {
        fun block(type: Int, last: Boolean, data: ByteArray) =
            bytes(byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()), data)
        fun entry(s: String) = s.toByteArray().let { bytes(le32(it.size), it) }
        val vorbis = bytes(entry("reference"), le32(2), entry("TITLE=Song"), entry("COMMENT=leak"))
        val file = bytes("fLaC".toByteArray(), block(0, false, ByteArray(34)), block(6, false, ByteArray(150_000)), block(4, true, vorbis), ByteArray(300))
        assertEquals("leak", read(file))
    }

    @Test fun m4a_afterAudio() {
        fun box(type: String, vararg body: ByteArray) = bytes(be32(8 + body.sumOf { it.size }), type.toByteArray(Charsets.ISO_8859_1), *body)
        val data = box("data", be32(1), be32(0), "Leaks".toByteArray())
        val moov = box("moov", box("mvhd", ByteArray(100)), box("udta", box("meta", ByteArray(4), box("hdlr", ByteArray(25)), box("ilst", box("©cmt", data)))))
        val file = bytes(box("ftyp", "M4A ".toByteArray(), ByteArray(12)), box("mdat", ByteArray(100_000)), moov)
        assertEquals("Leaks", read(file))
    }

    @Test fun leakWords() {
        assertTrue(Leaks.isLeak("Leak"))
        assertTrue(Leaks.isLeak("this was leaked in 2020"))
        assertTrue(Leaks.isLeak("UNRELEASED"))
        assertTrue(Leaks.isLeak("snippet / leaks"))
        assertFalse(Leaks.isLeak("Bleak House"))
        assertFalse(Leaks.isLeak("Released 2019"))
        assertFalse(Leaks.isLeak(""))
        assertFalse(Leaks.isLeak(null))
    }
}
