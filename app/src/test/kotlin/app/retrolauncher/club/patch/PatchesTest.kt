package app.retrolauncher.club.patch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

class PatchesTest {
    private val base = Random(1).nextBytes(4096)

    private fun applied(format: PatchFormat, patch: ByteArray, source: ByteArray = base): ByteArray {
        val out = File.createTempFile("patched", ".bin")
        try {
            Patcher.apply(format, patch, ByteBuffer.wrap(source), out)
            return out.readBytes()
        } finally {
            out.delete()
        }
    }

    @Test
    fun `IPS writes records and runs, and can extend the ROM`() {
        val p = ByteArrayOutputStream()
        p.write("PATCH".toByteArray())
        p.write(byteArrayOf(0, 0, 16, 0, 3, 1, 2, 3)) // 3 bytes at 16
        p.write(byteArrayOf(0, 16, 0, 0, 0, 0, 8, 0x7F)) // a run of 8 at 4096 (past the end)
        p.write("EOF".toByteArray())
        val out = applied(PatchFormat.IPS, p.toByteArray())
        assertEquals(4104, out.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), out.copyOfRange(16, 19))
        assertArrayEquals(ByteArray(8) { 0x7F }, out.copyOfRange(4096, 4104))
        assertArrayEquals(base.copyOfRange(19, 4096), out.copyOfRange(19, 4096))
    }

    private fun vcdiffNumber(v: Int): ByteArray {
        val groups = mutableListOf(v and 0x7F)
        var rest = v ushr 7
        while (rest != 0) { groups.add(0, (rest and 0x7F) or 0x80); rest = rest ushr 7 }
        return groups.map { it.toByte() }.toByteArray()
    }

    @Test
    fun `xdelta windows add, run and copy from the base and from their own output`() {
        // Target: 100 new bytes, then 500 bytes of the base from 1000, then a run of 30 zeroes,
        // then a 64-byte copy of its own last 4 bytes (overlapping), then 200 base bytes from 0.
        val added = Random(2).nextBytes(100)
        val segmentLength = base.size
        val data = ByteArrayOutputStream(); val inst = ByteArrayOutputStream(); val addr = ByteArrayOutputStream()
        data.write(added); inst.write(1); inst.write(vcdiffNumber(100)) // ADD, size follows
        inst.write(19); inst.write(vcdiffNumber(500)); addr.write(vcdiffNumber(1000)) // COPY mode 0 (absolute)
        data.write(0); inst.write(0); inst.write(vcdiffNumber(30)) // RUN of 30
        val here = segmentLength + 630
        inst.write(19); inst.write(vcdiffNumber(64)); addr.write(vcdiffNumber(here - 4)) // overlapping self copy
        inst.write(19); inst.write(vcdiffNumber(200)); addr.write(vcdiffNumber(0))
        val expected = added + base.copyOfRange(1000, 1500) + ByteArray(30) +
            ByteArray(64).also { val tail = byteArrayOf(0, 0, 0, 0); for (i in it.indices) it[i] = tail[i % 4] } +
            base.copyOfRange(0, 200)

        val body = ByteArrayOutputStream()
        body.write(vcdiffNumber(expected.size)); body.write(0) // window size, no compression
        body.write(vcdiffNumber(data.size())); body.write(vcdiffNumber(inst.size())); body.write(vcdiffNumber(addr.size()))
        body.write(ByteArray(4)) // Adler-32 (skipped)
        body.write(data.toByteArray()); body.write(inst.toByteArray()); body.write(addr.toByteArray())
        val patch = ByteArrayOutputStream()
        patch.write(byteArrayOf(0xD6.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0, 0x04))
        patch.write(vcdiffNumber(4)); patch.write("note".toByteArray()) // application header
        patch.write(0x01 or 0x04) // window from the source, with an Adler-32
        patch.write(vcdiffNumber(segmentLength)); patch.write(vcdiffNumber(0))
        patch.write(vcdiffNumber(body.size())); patch.write(body.toByteArray())

        assertArrayEquals(expected, applied(PatchFormat.VCDIFF, patch.toByteArray()))
    }

    @Test
    fun `BPS refuses the wrong base ROM, and its required CRC is readable without patching`() {
        // A tiny BPS: source 4096, target 4096, one SourceRead of everything.
        fun n(v: Long): ByteArray {
            val out = ByteArrayOutputStream(); var d = v
            while (true) { val x = (d and 0x7F).toInt(); d = d shr 7; if (d == 0L) { out.write(0x80 or x); break }; out.write(x); d-- }
            return out.toByteArray()
        }
        val crc = Patcher.crc32(ByteBuffer.wrap(base))
        val body = "BPS1".toByteArray() + n(4096) + n(4096) + n(0) + n(((4096L - 1) shl 2) or 0)
        fun le(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val withoutPatchCrc = body + le(crc) + le(crc)
        val patch = withoutPatchCrc + le(0)
        assertEquals(crc, Patcher.requiredSourceCrc(PatchFormat.BPS, patch))
        assertNull(Patcher.requiredSourceCrc(PatchFormat.IPS, patch))
        assertArrayEquals(base, applied(PatchFormat.BPS, patch))
        try {
            applied(PatchFormat.BPS, patch, Random(3).nextBytes(4096))
            error("should refuse")
        } catch (e: PatchException) {
            assertEquals(true, e.message!!.contains("isn't the one"))
        }
    }
}
