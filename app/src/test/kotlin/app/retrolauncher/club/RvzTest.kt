package app.retrolauncher.club

import app.retrolauncher.club.disc.ByteArrayChannel
import app.retrolauncher.club.disc.Rvz
import com.github.luben.zstd.Zstd
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import kotlin.random.Random

class RvzTest {
    /** The padding generator exactly as Dolphin's format description writes it (shift by 18 at output). */
    private fun specPadding(seed: IntArray, count: Int): ByteArray {
        val b = IntArray(521)
        seed.copyInto(b)
        for (i in 17 until 521) b[i] = (b[i - 17] shl 23) xor (b[i - 16] ushr 9) xor b[i - 1]
        fun advance() {
            for (i in 0 until 32) b[i] = b[i] xor b[i + 521 - 32]
            for (i in 32 until 521) b[i] = b[i] xor b[i - 32]
        }
        repeat(4) { advance() }
        val out = ByteArray(count)
        var w = 0
        var o = 0
        while (o < count) {
            val x = b[w]
            for (v in intArrayOf(x ushr 24, x ushr 18, x ushr 8, x)) if (o < count) out[o++] = v.toByte()
            if (++w == 521) { advance(); w = 0 }
        }
        return out
    }

    /** A small GameCube disc: header, apploader sizes, a boot program, then 32 KiB of padding. */
    private fun disc(padding: ByteArray): ByteArray {
        val disc = ByteArray(0x30000)
        val be = ByteBuffer.wrap(disc).order(ByteOrder.BIG_ENDIAN)
        "GTEST1".toByteArray().copyInto(disc)
        be.putInt(0x1C, 0xC2339F3D.toInt())
        be.putInt(0x2440 + 0x14, 0x100); be.putInt(0x2440 + 0x18, 0x40)
        be.putInt(0x420, 0x18000)
        be.putInt(0x18000, 0x18100); be.putInt(0x18000 + 0x90, 0x4000)
        Random(9).nextBytes(0x4000).copyInto(disc, 0x18100)
        // 0x10000-0x17fff stays zero (a group stored as "all zeroes"); the last 32 KiB is padding.
        padding.copyInto(disc, 0x28000)
        return disc
    }

    /** An RVZ of [disc] in 32 KiB groups, exercising each way a group can be stored. */
    private fun rvz(disc: ByteArray, seed: IntArray): ByteArray {
        val chunk = 0x8000
        val groups = disc.size / chunk
        val body = ByteArrayOutputStream()
        val headerSize = 0x48 + 0xDC
        fun place(bytes: ByteArray): Long {
            while ((headerSize + body.size()) % 4 != 0) body.write(0)
            val at = (headerSize + body.size()).toLong()
            body.write(bytes)
            return at
        }
        val table = ByteBuffer.allocate(groups * 12).order(ByteOrder.BIG_ENDIAN)
        for (g in 0 until groups) {
            val data = disc.copyOfRange(g * chunk, (g + 1) * chunk)
            when (g) {
                1 -> { val at = place(data); table.putInt((at shr 2).toInt()).putInt(data.size).putInt(0) } // stored, not compressed
                2 -> table.putInt(0).putInt(0).putInt(0) // all zeroes
                5 -> { // packed: one padding run, from its seed
                    val packed = ByteBuffer.allocate(4 + 68).order(ByteOrder.BIG_ENDIAN).putInt(chunk or Int.MIN_VALUE)
                    seed.forEach { packed.putInt(it) }
                    val z = Zstd.compress(packed.array())
                    val at = place(z)
                    table.putInt((at shr 2).toInt()).putInt(z.size or Int.MIN_VALUE).putInt(packed.capacity())
                }
                else -> { val z = Zstd.compress(data); val at = place(z); table.putInt((at shr 2).toInt()).putInt(z.size or Int.MIN_VALUE).putInt(0) }
            }
        }
        val raw = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).putLong(0x80).putLong(disc.size - 0x80L).putInt(0).putInt(groups).array()
        val rawZ = Zstd.compress(raw)
        val rawAt = place(rawZ)
        val groupZ = Zstd.compress(table.array())
        val groupAt = place(groupZ)

        val head = ByteBuffer.allocate(0x48).order(ByteOrder.BIG_ENDIAN)
        head.put("RVZ\u0001".toByteArray()).putInt(0x01000000).putInt(0x00030000).putInt(0xDC)
        head.put(ByteArray(20)).putLong(disc.size.toLong()).putLong(0).put(ByteArray(20))
        val d = ByteBuffer.allocate(0xDC).order(ByteOrder.BIG_ENDIAN)
        d.putInt(1).putInt(5).putInt(5).putInt(chunk).put(disc, 0, 0x80)
        d.putInt(0).putInt(0x30).putLong(0).put(ByteArray(20))
        d.putInt(1).putLong(rawAt).putInt(rawZ.size)
        d.putInt(groups).putLong(groupAt).putInt(groupZ.size)
        return head.array() + d.array() + body.toByteArray()
    }

    /** A GCZ of [disc] in 16 KiB blocks: zlib-compressed, except one stored as is. */
    private fun gcz(disc: ByteArray): ByteArray {
        val block = 0x4000
        val blocks = disc.size / block
        val body = ByteArrayOutputStream()
        val pointers = LongArray(blocks)
        for (b in 0 until blocks) {
            val data = disc.copyOfRange(b * block, (b + 1) * block)
            pointers[b] = body.size().toLong()
            if (b == 3) { pointers[b] = pointers[b] or Long.MIN_VALUE; body.write(data); continue }
            val d = java.util.zip.Deflater(9).apply { setInput(data); finish() }
            val buf = ByteArray(block * 2)
            while (!d.finished()) body.write(buf, 0, d.deflate(buf))
            d.end()
        }
        val head = ByteBuffer.allocate(32 + blocks * 12).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0xB10BC001.toInt()).putInt(0).putLong(body.size().toLong()).putLong(disc.size.toLong()).putInt(block).putInt(blocks)
        pointers.forEach { head.putLong(it) }
        repeat(blocks) { head.putInt(0) } // checksums, not checked
        return head.array() + body.toByteArray()
    }

    @Test
    fun `a GCZ reads back as the plain disc and hashes the same`() {
        val disc = disc(specPadding(IntArray(17) { it * 7919 }, 0x8000))
        val image = gcz(disc)
        app.retrolauncher.club.disc.Gcz(ByteArrayChannel(image)).use { g ->
            assertArrayEquals(disc, g.read(0, disc.size))
            assertArrayEquals(disc.copyOfRange(0x3FF0, 0x4100), g.read(0x3FF0, 0x110))
        }
        fun rom(name: String, bytes: ByteArray) = object : RomFile {
            override val name = name
            override val parentName: String? = null
            override fun open(): InputStream = ByteArrayInputStream(bytes)
            override fun openChannel(): SeekableByteChannel = ByteArrayChannel(bytes)
        }
        assertEquals(RaHash.hash(RaConsoles.GAMECUBE, rom("g.iso", disc)), RaHash.hash(RaConsoles.GAMECUBE, rom("g.gcz", image)))
    }

    @Test
    fun `an RVZ reads back as the plain disc and hashes the same`() {
        val seed = IntArray(17) { Random(it).nextInt() }
        val padding = specPadding(seed, 0x8000)
        val disc = disc(padding)
        val image = rvz(disc, seed)

        Rvz(ByteArrayChannel(image)).use { r ->
            assertEquals(disc.size.toLong(), r.isoSize)
            assertArrayEquals(disc, r.read(0, disc.size))
            // A read that starts mid-group and crosses into the next.
            assertArrayEquals(disc.copyOfRange(0x7FF0, 0x8100), r.read(0x7FF0, 0x110))
        }
        fun rom(name: String, bytes: ByteArray) = object : RomFile {
            override val name = name
            override val parentName: String? = null
            override fun open(): InputStream = ByteArrayInputStream(bytes)
            override fun openChannel(): SeekableByteChannel = ByteArrayChannel(bytes)
        }
        val iso = RaHash.hash(RaConsoles.GAMECUBE, rom("g.iso", disc))
        assertEquals(iso, RaHash.hash(RaConsoles.GAMECUBE, rom("g.rvz", image)))
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(disc, 0, 0x2440 + 0x20 + 0x140); md5.update(disc, 0x18100, 0x4000)
        assertEquals(md5.digest().joinToString("") { "%02x".format(it) }, iso)
    }
}
