package app.retrolauncher.club

import app.retrolauncher.club.disc.ByteArrayChannel
import app.retrolauncher.club.disc.CdImages
import app.retrolauncher.club.disc.Chd
import app.retrolauncher.club.disc.DiscHash
import app.retrolauncher.library.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.random.Random

/** A file with random access and neighbours, like a disc image in a ROM folder. */
class DiscRom(
    override val name: String,
    val bytes: ByteArray,
    private val folder: MutableMap<String, DiscRom> = mutableMapOf(),
) : RomFile {
    init { folder[name] = this }
    override val parentName: String? = "psx"
    override fun open(): InputStream = ByteArrayInputStream(bytes)
    override fun openChannel() = ByteArrayChannel(bytes)
    override fun sibling(name: String): RomFile? = folder[name]
    fun neighbour(name: String, bytes: ByteArray) = DiscRom(name, bytes, folder)
}

/** Builds minimal ISO 9660 images: a PVD, a root directory and optional subdirectories. */
object IsoBuilder {
    class Dir(val files: Map<String, ByteArray>, val dirs: Map<String, Dir> = emptyMap())

    fun build(root: Dir): ByteArray {
        val sectors = mutableListOf<ByteArray>()
        repeat(16) { sectors += ByteArray(2048) }
        val pvd = ByteArray(2048).also { sectors += it }
        sectors += ByteArray(2048); sectors += ByteArray(2048); sectors += ByteArray(2048) // 17-19
        val rootSector = place(root, sectors)
        pvd[0] = 1
        "CD001".toByteArray().copyInto(pvd, 1)
        le16(pvd, 128, 2048)
        record(pvd, 156, "\u0000", rootSector, 2048, dir = true)
        return sectors.fold(ByteArray(0)) { acc, s -> acc + s }
    }

    /** Lays out a directory and its contents; returns the directory's sector. */
    private fun place(dir: Dir, sectors: MutableList<ByteArray>): Int {
        val dirSector = sectors.size
        val block = ByteArray(2048).also { sectors += it }
        var at = 0
        at += record(block, at, "\u0000", dirSector, 2048, dir = true)
        at += record(block, at, "\u0001", dirSector, 2048, dir = true)
        for ((name, sub) in dir.dirs) {
            val s = place(sub, sectors)
            at += record(block, at, name, s, 2048, dir = true)
        }
        for ((name, data) in dir.files) {
            val start = sectors.size
            var off = 0
            while (off < data.size || off == 0) {
                sectors += data.copyOfRange(off, minOf(off + 2048, data.size)).copyOf(2048)
                off += 2048
                if (data.isEmpty()) break
            }
            at += record(block, at, "$name;1", start, data.size, dir = false)
        }
        return dirSector
    }

    private fun record(b: ByteArray, at: Int, name: String, sector: Int, size: Int, dir: Boolean): Int {
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        var length = 33 + nameBytes.size
        if (length % 2 == 1) length++
        b[at] = length.toByte()
        le32(b, at + 2, sector)
        le32(b, at + 10, size)
        b[at + 25] = if (dir) 2 else 0
        b[at + 32] = nameBytes.size.toByte()
        nameBytes.copyInto(b, at + 33)
        return length
    }

    /** Wraps 2048-byte sectors as raw mode 2 form 1 sectors (2352 bytes, data at 24). */
    fun toMode2Raw(iso: ByteArray): ByteArray {
        val out = ByteArray(iso.size / 2048 * 2352)
        for (i in 0 until iso.size / 2048) {
            val o = i * 2352
            out[o] = 0; for (k in 1..10) out[o + k] = -1; out[o + 11] = 0
            out[o + 15] = 2
            iso.copyInto(out, o + 24, i * 2048, i * 2048 + 2048)
        }
        return out
    }

    private fun le16(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, i: Int, v: Int) { for (k in 0..3) b[i + k] = (v shr (8 * k)).toByte() }
}

class DiscHashTest {
    private fun md5(vararg parts: ByteArray) =
        MessageDigest.getInstance("MD5").apply { parts.forEach { update(it) } }.digest().joinToString("") { "%02x".format(it) }

    /** A PS-X EXE whose header says 4096 bytes of code, followed by 1000 bytes that aren't hashed. */
    private val exe = ByteArray(2048 + 4096 + 1000).also {
        Random(3).nextBytes(it)
        "PS-X EXE".toByteArray().copyInto(it)
        val size = 4096
        for (k in 0..3) it[28 + k] = (size shr (8 * k)).toByte()
    }
    private val expectedPs1 = md5("SLUS_123.45".toByteArray(), exe.copyOf(2048 + 4096))

    private val ps1Iso = IsoBuilder.build(
        IsoBuilder.Dir(
            mapOf(
                "SYSTEM.CNF" to "BOOT = cdrom:\\SLUS_123.45;1\r\nTCB = 4\r\nEVENT = 10\r\n".toByteArray(),
                "SLUS_123.45" to exe,
                "DATA.BIN" to ByteArray(5000),
            )
        )
    )

    private fun hash(console: Int, rom: RomFile) = RaHash.hash(console, rom)

    @Test
    fun `PS1 hash from ISO, raw BIN, CUE and M3U`() {
        assertEquals(expectedPs1, hash(RaConsoles.PLAYSTATION, DiscRom("Game.iso", ps1Iso)))
        val raw = IsoBuilder.toMode2Raw(ps1Iso)
        assertEquals(expectedPs1, hash(RaConsoles.PLAYSTATION, DiscRom("Game.bin", raw)))

        val cue = DiscRom("Game.cue", "FILE \"Game (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n".toByteArray())
        cue.neighbour("Game (Track 1).bin", raw)
        assertEquals(expectedPs1, hash(RaConsoles.PLAYSTATION, cue))

        val m3u = cue.neighbour("Game.m3u", "# disc list\nGame.cue\n".toByteArray())
        assertEquals(expectedPs1, hash(RaConsoles.PLAYSTATION, m3u))
    }

    @Test
    fun `PS1 boot file in a subdirectory, and the PSX EXE fallback`() {
        val sub = IsoBuilder.build(
            IsoBuilder.Dir(
                mapOf("SYSTEM.CNF" to "BOOT=cdrom:\\GAME\\MAIN.EXE;1\n".toByteArray()),
                mapOf("GAME" to IsoBuilder.Dir(mapOf("MAIN.EXE" to exe))),
            )
        )
        assertEquals(md5("GAME\\MAIN.EXE".toByteArray(), exe.copyOf(6144)), hash(RaConsoles.PLAYSTATION, DiscRom("x.iso", sub)))

        val noCnf = IsoBuilder.build(IsoBuilder.Dir(mapOf("PSX.EXE" to exe)))
        assertEquals(md5("PSX.EXE".toByteArray(), exe.copyOf(6144)), hash(RaConsoles.PLAYSTATION, DiscRom("x.iso", noCnf)))
    }

    @Test
    fun `PS2 hash uses BOOT2 and the file's full size`() {
        val elf = ByteArray(9000).also { Random(5).nextBytes(it); byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()).copyInto(it) }
        val iso = IsoBuilder.build(
            IsoBuilder.Dir(mapOf("SYSTEM.CNF" to "BOOT2 = cdrom0:\\SLES_500.01;1\r\nVER = 1.00\r\n".toByteArray(), "SLES_500.01" to elf))
        )
        assertEquals(md5("SLES_500.01".toByteArray(), elf), hash(RaConsoles.PLAYSTATION_2, DiscRom("x.iso", iso)))
        // A PS2 disc has no PS1 boot line (BOOT2 isn't BOOT) and no PSX.EXE.
        assertNull(hash(RaConsoles.PLAYSTATION, DiscRom("x.iso", iso)))
    }

    @Test
    fun `not a disc image, or not a data disc`() {
        assertNull(hash(RaConsoles.PLAYSTATION, DiscRom("x.zip", ps1Iso)))
        val audioCue = DiscRom("a.cue", "FILE \"a.bin\" BINARY\n  TRACK 01 AUDIO\n    INDEX 01 00:00:00\n".toByteArray())
        audioCue.neighbour("a.bin", ByteArray(2352 * 20))
        assertNull(hash(RaConsoles.PLAYSTATION, audioCue))
        val missingBin = DiscRom("b.cue", "FILE \"gone.bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n".toByteArray())
        assertNull(hash(RaConsoles.PLAYSTATION, missingBin))
    }

    @Test
    fun `BIN tracks next to a CUE aren't separate candidates`() {
        fun g(path: String) = Game(path, "psx", path.substringAfterLast('/'), "", path, null)
        val kept = ClubFinder.discAware(
            listOf(g("/r/psx/A/A.cue"), g("/r/psx/A/A (Track 1).bin"), g("/r/psx/A/A (Track 2).bin"), g("/r/psx/B.bin"), g("/r/psx/C.chd"))
        ).map { it.fileName }
        assertEquals(listOf("A.cue", "B.bin", "C.chd"), kept)
    }
}

/** Opt-in: `-PdiscFile=/path/to/game.chd` checks the CHD reader and PS1 hash on a real image. */
class RealDiscTest {
    @Test
    fun `a real CHD decompresses to its stored CRCs and hashes`() {
        val path = System.getProperty("disc.file").orEmpty()
        assumeTrue("no disc file given", path.isNotEmpty())
        val file = File(path)
        val started = System.currentTimeMillis()
        Chd(FileChannel.open(file.toPath(), StandardOpenOption.READ)).use { chd ->
            println("hunks=${chd.hunkCount} hunkBytes=${chd.hunkBytes} tracks=${chd.tracks}")
            var checked = 0
            var bad = 0
            for (h in 0 until chd.hunkCount) {
                val crc = chd.storedCrc(h) ?: continue
                if (Chd.crc16(chd.hunk(h)) != crc) bad++
                checked++
            }
            println("CRC-checked $checked hunks, $bad mismatches, in ${System.currentTimeMillis() - started} ms")
            assertEquals(0, bad)
            assertTrue(checked > 0)
        }
        val rom = object : RomFile {
            override val name = file.name
            override val parentName = file.parentFile?.name
            override fun open(): InputStream = file.inputStream()
            override fun openChannel() = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        }
        val t = System.currentTimeMillis()
        val hash = RaHash.hash(RaConsoles.PLAYSTATION, rom)
        println("PS1 hash: $hash in ${System.currentTimeMillis() - t} ms")
        CdImages.openFirstTrack(rom)!!.use { track ->
            val cnf = DiscHash.findFileSector(track, "SYSTEM.CNF")!!
            println("SYSTEM.CNF: " + String(track.read(cnf.first, cnf.second), Charsets.ISO_8859_1).replace("\r\n", " | "))
        }
        assertTrue(hash != null)
    }
}
