package app.retrolauncher.club

import app.retrolauncher.club.disc.Chd
import com.github.luben.zstd.Zstd
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Writes bits most significant first, as CHD's bitstreams read them. */
private class BitWriter {
    private val out = ByteArrayOutputStream()
    private var acc = 0
    private var count = 0
    fun write(value: Int, bits: Int) {
        for (i in bits - 1 downTo 0) {
            acc = (acc shl 1) or ((value ushr i) and 1)
            if (++count == 8) { out.write(acc); acc = 0; count = 0 }
        }
    }
    fun alignToByte() { while (count != 0) write(0, 1) }
    fun bytes(): ByteArray { alignToByte(); return out.toByteArray() }
}

class ChdCodecsTest {
    private val hunk = Random(11).nextBytes(4096)

    @Test
    fun `zstd hunks decompress`() {
        assertArrayEquals(hunk, Chd.zstd(Zstd.compress(hunk), 0, Zstd.compress(hunk).size, hunk.size))
    }

    @Test
    fun `huff hunks read a Huffman-coded tree, then the bytes`() {
        // Every byte value gets an 8-bit code, so each byte's code is the byte itself. The tree is
        // sent as code lengths coded with a small tree of two symbols: 9 ("length 8") and 0 ("repeat").
        val w = BitWriter()
        w.write(1, 3) // small tree: symbol 0 has 1 bit
        w.write(7, 3) // lengths start at index 8
        w.write(0, 3) // index 8: unused
        w.write(1, 3) // index 9: 1 bit
        w.write(7, 3) // index 10 and up: unused
        w.write(1, 1) // symbol 9: the first code length is 8
        w.write(0, 1) // symbol 0: repeat it...
        w.write(7, 3); w.write(246, 8) // ...9 + 246 = 255 more times
        for (b in hunk) w.write(b.toInt() and 0xFF, 8)
        assertArrayEquals(hunk, Chd.huff(w.bytes(), hunk.size))
    }

    /** CHD's flac hunk: a byte-order mark, then FLAC frames with verbatim (uncompressed) 16-bit stereo subframes. */
    private fun flacHunk(data: ByteArray, little: Boolean): ByteArray {
        val w = BitWriter()
        w.write(if (little) 'L'.code else 'B'.code, 8)
        val samples = data.size / 4
        var blockSize = samples
        while (blockSize > 2048) blockSize /= 2
        fun sample(i: Int) = if (little) ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)) else ((data[i].toInt() shl 8) or (data[i + 1].toInt() and 0xFF))
        var frame = 0
        var at = 0
        while (at < samples) {
            w.write(0x3FFE, 14); w.write(0, 1); w.write(0, 1)
            w.write(7, 4) // block size from a 16-bit field below
            w.write(0, 4) // sample rate from the stream info
            w.write(1, 4) // left and right, independent
            w.write(4, 3); w.write(0, 1) // 16-bit samples
            w.write(frame++, 8) // frame number (one-byte UTF-8 form)
            w.write(blockSize - 1, 16)
            w.write(0, 8) // header CRC (not checked)
            for (channel in 0..1) {
                w.write(0, 1); w.write(1, 6); w.write(0, 1) // verbatim subframe, no wasted bits
                for (s in 0 until blockSize) w.write(sample((at + s) * 4 + channel * 2) and 0xFFFF, 16)
            }
            w.alignToByte()
            w.write(0, 16) // frame CRC (not checked)
            at += blockSize
        }
        return w.bytes()
    }

    @Test
    fun `flac hunks decode in either byte order`() {
        assertArrayEquals(hunk, Chd.rawFlac(flacHunk(hunk, little = false), hunk.size))
        assertArrayEquals(hunk, Chd.rawFlac(flacHunk(hunk, little = true), hunk.size))
    }

    /** An uncompressed CHD v5 DVD image of [image]: 2048-byte units, two per hunk. */
    private fun dvdChd(image: ByteArray): ByteArray {
        val hunkBytes = 4096
        val hunks = (image.size + hunkBytes - 1) / hunkBytes
        val mapOffset = 124L
        val metaOffset = mapOffset + hunks * 4
        val firstHunk = ((metaOffset + 16 + hunkBytes - 1) / hunkBytes).toInt()
        val out = ByteBuffer.allocate((firstHunk + hunks) * hunkBytes).order(ByteOrder.BIG_ENDIAN)
        out.put("MComprHD".toByteArray()).putInt(124).putInt(5)
        repeat(4) { out.putInt(0) } // no codecs: uncompressed
        out.putLong(image.size.toLong()).putLong(mapOffset).putLong(metaOffset).putInt(hunkBytes).putInt(2048)
        out.position(mapOffset.toInt())
        for (h in 0 until hunks) out.putInt(firstHunk + h)
        out.position(metaOffset.toInt())
        out.put("DVD ".toByteArray()).putInt(0).putLong(0) // DVD metadata, no text, last entry
        out.position(firstHunk * hunkBytes)
        out.put(image)
        return out.array()
    }

    @Test
    fun `a PSP game in a DVD CHD hashes the same as its ISO`() {
        val iso = pspIso()
        val chd = dvdChd(iso)
        val expected = md5(PSP_SFO, PSP_EBOOT)
        assertEquals(expected, RaHash.hash(RaConsoles.PSP, Folder(mapOf("g.iso" to iso)).rom("g.iso")))
        assertEquals(expected, RaHash.hash(RaConsoles.PSP, Folder(mapOf("g.chd" to chd)).rom("g.chd")))
    }
}
