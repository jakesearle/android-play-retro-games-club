package app.retrolauncher.club

import app.retrolauncher.club.disc.ByteArrayChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.zip.Deflater
import kotlin.random.Random

/** Files in one folder, with random access and siblings, as ROM folders give them. */
private class Folder(val files: Map<String, ByteArray>) {
    fun rom(name: String): RomFile = object : RomFile {
        override val name = name
        override val parentName: String? = null
        override fun open(): InputStream = ByteArrayInputStream(files.getValue(name))
        override fun openChannel(): SeekableByteChannel = ByteArrayChannel(files.getValue(name))
        override fun sibling(name: String): RomFile? = if (name in files) rom(name) else null
    }
}

private fun md5(vararg parts: ByteArray) =
    MessageDigest.getInstance("MD5").apply { parts.forEach(::update) }.digest().joinToString("") { "%02x".format(it) }

/** An ISO 9660 directory record. */
private fun record(name: String, sector: Int, size: Int): ByteArray {
    val n = name.toByteArray(Charsets.ISO_8859_1)
    val length = 33 + n.size + (if (n.size % 2 == 0) 1 else 0)
    return ByteArray(length).also { r ->
        r[0] = length.toByte()
        ByteBuffer.wrap(r, 2, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sector)
        ByteBuffer.wrap(r, 10, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(size)
        r[32] = n.size.toByte()
        n.copyInto(r, 33)
    }
}

/**
 * A minimal ISO 9660 image of 2048-byte sectors: [sectors] are placed by relative sector; the
 * root directory is at [root] (an absolute sector, as on the disc, so [base] is subtracted).
 */
private fun iso(count: Int, root: Int, base: Int = 0, sectors: Map<Int, ByteArray>): ByteArray {
    val image = ByteArray(count * 2048)
    val pvd = ByteArray(2048)
    pvd[128] = 0; pvd[129] = 8 // logical block size 2048
    record("\u0000", root, 2048).copyInto(pvd, 156)
    pvd.copyInto(image, 16 * 2048)
    for ((sector, bytes) in sectors) bytes.copyInto(image, (sector - base) * 2048)
    return image
}

private fun dir(vararg records: ByteArray) = records.fold(ByteArray(0)) { acc, r -> acc + r }

/** Raw 2352-byte MODE1 sectors (sync, header, 2048 bytes of data, EDC/ECC left zero). */
private fun raw(data: ByteArray): ByteArray {
    val sectors = data.size / 2048
    val out = ByteArray(sectors * 2352)
    for (i in 0 until sectors) {
        out[i * 2352 + 15] = 1
        data.copyInto(out, i * 2352 + 16, i * 2048, (i + 1) * 2048)
    }
    return out
}

class DiscConsolesTest {
    @Test
    fun `Sega CD and Saturn hash the first 512 bytes of track 1`() {
        val segaCd = ByteArray(4 * 2048).also { "SEGADISCSYSTEM  ".toByteArray().copyInto(it); Random(1).nextBytes(496).copyInto(it, 16) }
        assertEquals(md5(segaCd.copyOf(512)), RaHash.hash(RaConsoles.SEGA_CD, Folder(mapOf("g.iso" to segaCd)).rom("g.iso")))

        val saturn = ByteArray(4 * 2048).also { "SEGA SEGASATURN ".toByteArray().copyInto(it); Random(2).nextBytes(496).copyInto(it, 16) }
        val cue = "FILE \"Game (Track 1).bin\" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n"
        val folder = Folder(mapOf("Game.cue" to cue.toByteArray(), "Game (Track 1).bin" to raw(saturn)))
        assertEquals(md5(saturn.copyOf(512)), RaHash.hash(RaConsoles.SATURN, folder.rom("Game.cue")))
        assertNull(RaHash.hash(RaConsoles.SATURN, Folder(mapOf("x.iso" to ByteArray(4096))).rom("x.iso")))
    }

    @Test
    fun `PC Engine CD hashes the title and boot sectors of the first data track`() {
        val data = ByteArray(6 * 2048)
        val header = ByteArray(128).also {
            it[2] = 2; it[3] = 2 // program at sector 2, two sectors
            "PC Engine CD-ROM SYSTEM".toByteArray().copyInto(it, 32)
            "MY GAME TITLE         ".toByteArray().copyInto(it, 106)
        }
        header.copyInto(data, 2048)
        Random(3).nextBytes(4096).copyInto(data, 2 * 2048)
        // Track 1 is audio, as on most PC Engine CDs; the data is track 2, in its own file.
        val cue = "FILE \"a.bin\" BINARY\n  TRACK 01 AUDIO\n    INDEX 01 00:00:00\n" +
            "FILE \"d.bin\" BINARY\n  TRACK 02 MODE1/2048\n    INDEX 01 00:00:00\n"
        val folder = Folder(mapOf("g.cue" to cue.toByteArray(), "a.bin" to ByteArray(2352 * 10), "d.bin" to data))
        val expected = md5(header.copyOfRange(106, 128), data.copyOfRange(2 * 2048, 4 * 2048))
        assertEquals(expected, RaHash.hash(RaConsoles.PC_ENGINE_CD, folder.rom("g.cue")))
    }

    @Test
    fun `Dreamcast hashes IP_BIN and the boot file from track 3 at sector 45000`() {
        val base = 45000
        val meta = ByteArray(2048).also {
            "SEGA SEGAKATANA ".toByteArray().copyInto(it)
            "1ST_READ.BIN    ".toByteArray().copyInto(it, 96)
        }
        val boot = Random(4).nextBytes(3000)
        val track3 = iso(
            count = 40, root = base + 20, base = base,
            sectors = mapOf(base to meta, base + 20 to dir(record("1ST_READ.BIN;1", base + 30, boot.size)), base + 30 to boot),
        )
        val gdi = "3\n1 0 4 2352 track01.bin 0\n2 600 0 2352 track02.raw 0\n3 45000 4 2352 track03.bin 0\n"
        val folder = Folder(mapOf(
            "Game.gdi" to gdi.toByteArray(), "track01.bin" to raw(ByteArray(2048 * 2)),
            "track02.raw" to ByteArray(2352 * 4), "track03.bin" to raw(track3),
        ))
        assertEquals(md5(meta.copyOf(256), boot), RaHash.hash(RaConsoles.DREAMCAST, folder.rom("Game.gdi")))
    }

    private val sfo = Random(5).nextBytes(1200)
    private val eboot = Random(6).nextBytes(5000)
    private val pspIso = iso(
        count = 40, root = 20,
        sectors = mapOf(
            20 to dir(record("PSP_GAME", 21, 2048)),
            21 to dir(record("PARAM.SFO;1", 25, sfo.size), record("SYSDIR", 22, 2048)),
            22 to dir(record("EBOOT.BIN;1", 30, eboot.size)),
            25 to sfo, 30 to eboot,
        ),
    )

    @Test
    fun `PSP hashes PARAM_SFO then EBOOT_BIN, from an ISO or a CSO`() {
        val expected = md5(sfo, eboot)
        assertEquals(expected, RaHash.hash(RaConsoles.PSP, Folder(mapOf("g.iso" to pspIso)).rom("g.iso")))
        assertEquals(expected, RaHash.hash(RaConsoles.PSP, Folder(mapOf("g.cso" to cso(pspIso))).rom("g.cso")))
        val pbp = Random(7).nextBytes(9000)
        assertEquals(md5(pbp), RaHash.hash(RaConsoles.PSP, Folder(mapOf("EBOOT.PBP" to pbp)).rom("EBOOT.PBP")))
    }

    /** A CSO v1 of [image]: 2048-byte blocks, raw Deflate, with one block stored to cover both kinds. */
    private fun cso(image: ByteArray): ByteArray {
        val blocks = image.size / 2048
        val body = ByteArrayOutputStream()
        val index = IntArray(blocks + 1)
        val headerSize = 24 + (blocks + 1) * 4
        for (b in 0 until blocks) {
            index[b] = headerSize + body.size()
            val block = image.copyOfRange(b * 2048, (b + 1) * 2048)
            if (b == 1) { index[b] = index[b] or Int.MIN_VALUE; body.write(block); continue }
            val deflater = Deflater(9, true).apply { setInput(block); finish() }
            val buf = ByteArray(4096)
            while (!deflater.finished()) body.write(buf, 0, deflater.deflate(buf))
            deflater.end()
        }
        index[blocks] = headerSize + body.size()
        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        header.put("CISO".toByteArray()).putInt(24).putLong(image.size.toLong()).putInt(2048).put(1).put(0).putShort(0)
        index.forEach { header.putInt(it) }
        return header.array() + body.toByteArray()
    }

    @Test
    fun `GameCube hashes the disc header, apploader and boot program segments`() {
        val disc = ByteArray(0x30000)
        val be = ByteBuffer.wrap(disc).order(ByteOrder.BIG_ENDIAN)
        be.putInt(0x1C, 0xC2339F3D.toInt())
        be.putInt(0x2440 + 0x14, 0x100) // apploader body
        be.putInt(0x2440 + 0x18, 0x40) // trailer
        be.putInt(0x420, 0x20000) // main.dol
        be.putInt(0x20000 + 0, 0x20100); be.putInt(0x20000 + 0x90, 0x200) // code segment 0
        be.putInt(0x20000 + 7 * 4, 0x20400); be.putInt(0x20000 + 0x90 + 7 * 4, 0x80) // data segment 0
        Random(8).nextBytes(0x400).copyInto(disc, 0x20100)
        val headerSize = 0x2440 + 0x20 + 0x100 + 0x40
        val expected = md5(disc.copyOf(headerSize), disc.copyOfRange(0x20100, 0x20300), disc.copyOfRange(0x20400, 0x20480))
        assertEquals(expected, RaHash.hash(RaConsoles.GAMECUBE, Folder(mapOf("g.iso" to disc)).rom("g.iso")))
        // Compressed RVZ can't be read: reported as such, not as "no match".
        org.junit.Assert.assertThrows(UnreadableFormatException::class.java) {
            RaHash.hash(RaConsoles.GAMECUBE, Folder(mapOf("g.rvz" to disc)).rom("g.rvz"))
        }
    }
}
