package app.retrolauncher.club

import app.retrolauncher.library.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class Rom(override val name: String, val bytes: ByteArray, override val parentName: String? = null) : RomFile {
    var opens = 0
    override fun open(): InputStream { opens++; return ByteArrayInputStream(bytes) }
}

fun md5(b: ByteArray, from: Int = 0) =
    MessageDigest.getInstance("MD5").apply { update(b, from, b.size - from) }.digest().joinToString("") { "%02x".format(it) }

fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
}.toByteArray()

class RaHashTest {
    private val rom = Random(42).nextBytes(256 * 1024)

    @Test
    fun `whole-file consoles hash the whole file`() {
        assertEquals(md5(rom), RaHash.hash(RaConsoles.GAMEBOY_ADVANCE, Rom("x.gba", rom)))
        assertEquals(md5(rom), RaHash.hash(RaConsoles.MEGA_DRIVE, Rom("x.md", rom)))
    }

    @Test
    fun `NES skips the iNES header`() {
        val headered = "NES\u001a".toByteArray() + ByteArray(12) + rom
        assertEquals(md5(rom), RaHash.hash(RaConsoles.NINTENDO, Rom("x.nes", headered)))
        assertEquals(md5(rom), RaHash.hash(RaConsoles.NINTENDO, Rom("x.nes", rom)))
    }

    @Test
    fun `SNES skips a 512-byte copier header`() {
        val headered = ByteArray(512) + rom
        assertEquals(md5(rom), RaHash.hash(RaConsoles.SUPER_NINTENDO, Rom("x.smc", headered)))
        assertEquals(md5(rom), RaHash.hash(RaConsoles.SUPER_NINTENDO, Rom("x.sfc", rom)))
    }

    @Test
    fun `Lynx, 7800 and PCE headers`() {
        assertEquals(md5(rom), RaHash.hash(RaConsoles.ATARI_LYNX, Rom("x.lnx", "LYNX".toByteArray() + ByteArray(60) + rom)))
        assertEquals(md5(rom), RaHash.hash(RaConsoles.ATARI_7800, Rom("x.a78", byteArrayOf(1) + "ATARI7800".toByteArray() + ByteArray(118) + rom)))
        assertEquals(md5(rom), RaHash.hash(RaConsoles.PC_ENGINE, Rom("x.pce", ByteArray(512) + rom)))
    }

    @Test
    fun `N64 byte orders all hash as z64`() {
        val z64 = rom.copyOf().also { it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40 }
        val v64 = z64.copyOf().also { b -> for (i in b.indices step 2) { val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t } }
        val n64 = z64.copyOf().also { b -> for (i in b.indices step 4) { b.reverse(i, i + 4) } }
        val expected = md5(z64)
        assertEquals(expected, RaHash.hash(RaConsoles.NINTENDO_64, Rom("x.z64", z64)))
        assertEquals(expected, RaHash.hash(RaConsoles.NINTENDO_64, Rom("x.v64", v64)))
        assertEquals(expected, RaHash.hash(RaConsoles.NINTENDO_64, Rom("x.n64", n64)))
        assertNull(RaHash.hash(RaConsoles.NINTENDO_64, Rom("x.z64", byteArrayOf(0x12) + rom)))
    }

    @Test
    fun `NDS hashes header, code and icon`() {
        val file = ByteArray(0x8000)
        fun put(at: Int, v: Int) { for (i in 0..3) file[at + i] = (v shr (8 * i)).toByte() }
        put(0x20, 0x1000); put(0x2C, 0x200) // arm9
        put(0x30, 0x2000); put(0x3C, 0x100) // arm7
        put(0x68, 0x3000) // icon
        Random(1).nextBytes(0x1000).copyInto(file, 0x1000)
        val md = MessageDigest.getInstance("MD5")
        md.update(file, 0, 0x160); md.update(file, 0x1000, 0x200); md.update(file, 0x2000, 0x100); md.update(file, 0x3000, 0xA00)
        val expected = md.digest().joinToString("") { "%02x".format(it) }
        assertEquals(expected, RaHash.hash(RaConsoles.NINTENDO_DS, Rom("x.nds", file)))
    }

    @Test
    fun `7z cartridge ROMs hash the file inside, skipping readmes`() {
        val nes = "NES\u001a".toByteArray() + ByteArray(12) + rom
        val archive = sevenZip("readme.txt" to "hi".toByteArray(), "Folder/Game.nes" to nes)
        assertEquals(md5(rom), RaHash.hash(RaConsoles.NINTENDO, SeekableRom("Game.7z", archive)))
        // Without random access the archive can't be read, so there's no hash rather than a wrong one.
        assertEquals(null, RaHash.hash(RaConsoles.NINTENDO, Rom("Game.7z", archive)))
    }

    @Test
    fun `zipped cartridge ROMs hash the file inside, skipping readmes`() {
        val zipped = zip("readme.txt" to "hi".toByteArray(), "__MACOSX/._x.gba" to ByteArray(4), "Game.gba" to rom)
        assertEquals(md5(rom), RaHash.hash(RaConsoles.GAMEBOY_ADVANCE, Rom("Game.zip", zipped)))
        val nes = zip("Game.nes" to "NES\u001a".toByteArray() + ByteArray(12) + rom)
        assertEquals(md5(rom), RaHash.hash(RaConsoles.NINTENDO, Rom("Game.zip", nes)))
    }

    @Test
    fun `arcade hashes the file name, with FBNeo subsystem folders`() {
        assertEquals(md5("turfmast".toByteArray()), RaHash.hash(RaConsoles.ARCADE, Rom("turfmast.zip", rom, "fbneo")))
        assertEquals(md5("megadrive_sonic".toByteArray()), RaHash.hash(RaConsoles.ARCADE, Rom("sonic.zip", rom, "MegaDrive")))
    }

    @Test
    fun `other disc consoles are not supported`() {
        assertNull(RaHash.hash(RaConsoles.THREEDO, Rom("x.cue", rom)))
        assertTrue(RaHash.supports(RaConsoles.GAMEBOY))
        assertTrue(!RaHash.supports(RaConsoles.THREEDO))
    }
}

class ClubFinderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val gameRom = Random(7).nextBytes(64 * 1024)
    private val otherRom = Random(8).nextBytes(64 * 1024)
    private val pick = ClubPage.parse(ClubFixture.page)[0] // Alien Hominid (GBA), game 5068

    private val files = mutableMapOf<String, Rom>()
    private var online = true
    private var downloads = 0

    private fun game(name: String, bytes: ByteArray, modified: Long = 1000): Game {
        files[name] = Rom(name, bytes)
        return Game("/roms/gba/$name", "gba", name, name, "/roms/gba/$name", null, bytes.size.toLong(), modified)
    }

    private fun finder() = ClubFinder(
        hashLibrary = HashLibrary(File(tmp.root, "hashlib"), download = {
            downloads++
            if (!online) throw java.io.IOException("offline")
            """{"Success":true,"MD5List":{"${md5(gameRom).uppercase()}":5068,"${md5(otherRom)}":999}}"""
        }),
        hashCache = HashCache(File(tmp.root, "hashes.tsv")),
        links = RomLinks(File(tmp.root, "links.json")),
        open = { files[it.fileName] },
    )

    @Test
    fun `finds the game by contents, whatever its name`() {
        val candidates = listOf(game("Alien Hominid (USA).gba", otherRom), game("renamed.gba", gameRom))
        val r = finder().find(pick, candidates) as FindResult.Found
        assertEquals(listOf("renamed.gba"), r.matches.map { it.fileName })
        assertEquals("renamed.gba", ClubFinder.choose(r)?.fileName)
    }

    @Test
    fun `hashes are cached and linked ROMs skip the search`() {
        val candidates = listOf(game("a.gba", otherRom), game("b.gba", gameRom))
        val f = finder()
        f.find(pick, candidates)
        assertEquals(1, files["a.gba"]!!.opens)
        f.find(pick, candidates)
        assertEquals(1, files["a.gba"]!!.opens) // from the hash cache

        RomLinks(File(tmp.root, "links.json")).save(5068, candidates[1])
        online = false
        val linked = finder().find(pick, candidates) as FindResult.Found
        assertTrue(linked.linked)
        assertEquals("b.gba", linked.matches.single().fileName)

        // A changed file breaks the link and is searched for again.
        val changed = listOf(candidates[0], candidates[1].copy(lastModified = 2000))
        online = true
        val again = finder().find(pick, changed) as FindResult.Found
        assertTrue(!again.linked)
    }

    @Test
    fun `progress is reported for files read, not cached ones, and a stop ends the search`() {
        val candidates = listOf(game("x.gba", otherRom), game("y.gba", otherRom.reversedArray()), game("z.gba", gameRom))
        val reports = mutableListOf<Pair<Int, Int>>()
        finder().find(pick, candidates, progress = { d, t -> reports += d to t })
        assertEquals(3, reports.size)
        assertEquals(3 to 3, reports.last())

        reports.clear()
        finder().find(pick, candidates, progress = { d, t -> reports += d to t })
        assertTrue(reports.isEmpty()) // all hashed before: nothing slow to report

        files.clear()
        val fresh = listOf(game("p.gba", otherRom, 5), game("q.gba", gameRom, 5))
        var stop = false
        val stopped = finder().find(pick, fresh, cancelled = { stop }, progress = { _, _ -> stop = true })
        assertEquals(1, files.values.sumOf { it.opens }) // stopped after the first file
        assertTrue(stopped is FindResult.NotFound)
    }

    @Test
    fun `compressed GameCube images are reported as unreadable, not as the wrong version`() {
        val gc = pick.copy(title = "The Legend of Zelda: The Wind Waker (GC)")
        files["Wind Waker.wbfs"] = Rom("Wind Waker.wbfs", otherRom)
        val wbfs = Game("/roms/gc/Wind Waker.wbfs", "gc", "Wind Waker.wbfs", "Wind Waker", "/roms/gc/Wind Waker.wbfs", null, 10, 1)
        val result = finder().find(gc, listOf(wbfs)) as FindResult.CantCheck
        assertEquals(setOf("WBFS"), result.formats)
        assertEquals(1, result.count)
        // Chosen by hand, it's accepted without a check, as before GameCube matching existed.
        val console = RaConsoles.Console(RaConsoles.GAMECUBE, "GC", "GameCube")
        val library = HashLibrary(File(tmp.root, "hashlib2"), download = { """{"Success":true,"MD5List":{"abc":1}}""" })
        assertEquals(ClubFinder.Verdict.NotCheckable, ClubFinder.verifyChosen(library, console, 1, Rom("Wind Waker.wbfs", otherRom)))
    }

    @Test
    fun `duplicates make the player choose`() {
        val r = finder().find(pick, listOf(game("a.gba", gameRom), game("b.gba", gameRom))) as FindResult.Found
        assertEquals(2, r.matches.size)
        assertNull(ClubFinder.choose(r))
    }

    @Test
    fun `explains what is missing`() {
        assertTrue(finder().find(pick, emptyList()) is FindResult.NoGames)
        assertEquals(1, (finder().find(pick, listOf(game("a.gba", otherRom))) as FindResult.NotFound).searched)
        online = false
        val offline = ClubFinder(HashLibrary(tmp.newFolder(), download = { throw java.io.IOException() }),
            HashCache(File(tmp.root, "h2.tsv")), RomLinks(File(tmp.root, "l2.json")), open = { files[it.fileName] })
        assertTrue(offline.find(pick, listOf(game("a.gba", gameRom))) is FindResult.NoHashList)

        val wii = pick.copy(title = "Super Mario Galaxy (Wii)")
        assertTrue(finder().find(wii, listOf(game("a.iso", gameRom))) is FindResult.Unsupported)
        assertTrue(finder().find(pick.copy(title = "Odd (Zork)"), emptyList()) is FindResult.UnknownGame)
    }

    @Test
    fun `likely files are hashed first`() {
        val words = ClubFinder.words("Alien Hominid")
        assertEquals(setOf("alien", "hominid"), words)
        assertEquals(2, ClubFinder.score(words, "Alien Hominid (USA) [!].gba"))
        assertEquals(setOf("dr", "mario"), ClubFinder.words("Dr. Mario"))
        assertNotEquals(0, ClubFinder.score(ClubFinder.words("Dr. Mario"), "Dr. Mario (World).nes"))
    }
}

class VerifyChosenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val gameRom = Random(9).nextBytes(32 * 1024)
    private val gba = RaConsoles.Console(RaConsoles.GAMEBOY_ADVANCE, "GBA", "Game Boy Advance")

    private fun library(online: Boolean) = HashLibrary(tmp.newFolder(), download = {
        if (!online) throw java.io.IOException("offline")
        """{"Success":true,"MD5List":{"${md5(gameRom)}":5068}}"""
    })

    @Test
    fun `hand-picked files are checked, and refused when they can't be`() {
        val lib = library(online = true)
        assertEquals(ClubFinder.Verdict.Recognized, ClubFinder.verifyChosen(lib, gba, 5068, Rom("x.gba", gameRom)))
        assertEquals(ClubFinder.Verdict.NotRecognized, ClubFinder.verifyChosen(lib, gba, 5068, Rom("y.gba", Random(1).nextBytes(64))))
        // Offline with no saved list: can't check, so it's not accepted.
        assertEquals(ClubFinder.Verdict.NoHashList, ClubFinder.verifyChosen(library(online = false), gba, 5068, Rom("x.gba", gameRom)))
        // Disc consoles that can't be hashed yet are trusted.
        val threeDo = RaConsoles.Console(RaConsoles.THREEDO, "3DO", "3DO")
        assertEquals(ClubFinder.Verdict.NotCheckable, ClubFinder.verifyChosen(library(online = false), threeDo, 1, Rom("x.cue", gameRom)))
        val broken = object : RomFile {
            override val name = "z.gba"; override val parentName = null
            override fun open(): InputStream = throw java.io.IOException("card removed")
        }
        assertEquals(ClubFinder.Verdict.Unreadable("card removed"), ClubFinder.verifyChosen(lib, gba, 5068, broken))
    }
}

/** A 7z archive (LZMA2, the 7-Zip default) of [entries], written with Commons Compress. */
fun sevenZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val channel = org.apache.commons.compress.utils.SeekableInMemoryByteChannel()
    org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(channel).use { out ->
        for ((name, bytes) in entries) {
            val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry().apply { this.name = name; size = bytes.size.toLong() }
            out.putArchiveEntry(entry)
            out.write(bytes)
            out.closeArchiveEntry()
        }
    }
    return channel.array().copyOf(channel.size().toInt())
}

/** A ROM with random access, as files in ROM folders have. */
class SeekableRom(override val name: String, private val bytes: ByteArray) : RomFile {
    override val parentName: String? = null
    override fun open(): InputStream = ByteArrayInputStream(bytes)
    override fun openChannel(): java.nio.channels.SeekableByteChannel = org.apache.commons.compress.utils.SeekableInMemoryByteChannel(bytes)
}
