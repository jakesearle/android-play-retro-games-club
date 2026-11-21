package app.retrolauncher.club

import app.retrolauncher.club.RaConsoles.AMSTRAD_PC
import app.retrolauncher.club.RaConsoles.APPLE_II
import app.retrolauncher.club.RaConsoles.ARCADE
import app.retrolauncher.club.RaConsoles.ARCADIA_2001
import app.retrolauncher.club.RaConsoles.ATARI_2600
import app.retrolauncher.club.RaConsoles.ATARI_7800
import app.retrolauncher.club.RaConsoles.ATARI_JAGUAR
import app.retrolauncher.club.RaConsoles.ATARI_LYNX
import app.retrolauncher.club.RaConsoles.COLECOVISION
import app.retrolauncher.club.RaConsoles.COMMODORE_64
import app.retrolauncher.club.RaConsoles.ELEKTOR_TV_GAMES_COMPUTER
import app.retrolauncher.club.RaConsoles.FAIRCHILD_CHANNEL_F
import app.retrolauncher.club.RaConsoles.FAMICOM_DISK_SYSTEM
import app.retrolauncher.club.RaConsoles.GAMEBOY
import app.retrolauncher.club.RaConsoles.GAMEBOY_ADVANCE
import app.retrolauncher.club.RaConsoles.GAMEBOY_COLOR
import app.retrolauncher.club.RaConsoles.GAME_GEAR
import app.retrolauncher.club.RaConsoles.INTELLIVISION
import app.retrolauncher.club.RaConsoles.INTERTON_VC_4000
import app.retrolauncher.club.RaConsoles.MAGNAVOX_ODYSSEY2
import app.retrolauncher.club.RaConsoles.MASTER_SYSTEM
import app.retrolauncher.club.RaConsoles.MEGADUCK
import app.retrolauncher.club.RaConsoles.MEGA_DRIVE
import app.retrolauncher.club.RaConsoles.MSX
import app.retrolauncher.club.RaConsoles.NEOGEO_POCKET
import app.retrolauncher.club.RaConsoles.NINTENDO
import app.retrolauncher.club.RaConsoles.NINTENDO_64
import app.retrolauncher.club.RaConsoles.NINTENDO_DS
import app.retrolauncher.club.RaConsoles.NINTENDO_DSI
import app.retrolauncher.club.RaConsoles.ORIC
import app.retrolauncher.club.RaConsoles.PC8800
import app.retrolauncher.club.RaConsoles.PC_ENGINE
import app.retrolauncher.club.RaConsoles.POKEMON_MINI
import app.retrolauncher.club.RaConsoles.SEGA_32X
import app.retrolauncher.club.RaConsoles.SG1000
import app.retrolauncher.club.RaConsoles.SUPERVISION
import app.retrolauncher.club.RaConsoles.SUPER_CASSETTEVISION
import app.retrolauncher.club.RaConsoles.SUPER_NINTENDO
import app.retrolauncher.club.RaConsoles.TI83
import app.retrolauncher.club.RaConsoles.TIC80
import app.retrolauncher.club.RaConsoles.UZEBOX
import app.retrolauncher.club.RaConsoles.VECTREX
import app.retrolauncher.club.RaConsoles.VIRTUAL_BOY
import app.retrolauncher.club.RaConsoles.WASM4
import app.retrolauncher.club.RaConsoles.WONDERSWAN
import app.retrolauncher.club.RaConsoles.ZX_SPECTRUM
import app.retrolauncher.club.disc.CdImages
import app.retrolauncher.club.disc.DiscHash
import app.retrolauncher.club.disc.GameCube
import app.retrolauncher.club.disc.Gcz
import app.retrolauncher.club.disc.Rvz
import app.retrolauncher.club.disc.Wii
import app.retrolauncher.club.disc.TrackChoice
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/** A ROM file to hash, from a raw path or a `content://` document. */
/**
 * The file is in a format the app can't read (compressed GameCube images, DVD-type CHDs), so
 * whether it's a recognised version can't be checked, which is different from "it isn't".
 */
class UnreadableFormatException(val format: String) : IOException("$format files can't be checked yet")

interface RomFile {
    val name: String
    /** The folder the file is in, for arcade subsystem folders; may be null. */
    val parentName: String?

    @Throws(IOException::class)
    fun open(): InputStream

    /** Random access, needed for disc images (CHD, BIN, ISO). Null if unavailable. */
    @Throws(IOException::class)
    fun openChannel(): java.nio.channels.SeekableByteChannel? = null

    /** A file in the same folder, such as a CUE sheet's BIN or an M3U's disc; null if unavailable. */
    fun sibling(name: String): RomFile? = null
}

/**
 * RetroAchievements ROM hashes, as rcheevos computes them for cartridge-based consoles
 * (`src/rhash/hash.c` and `hash_rom.c`, MIT licence), so a ROM counts only if RA recognises
 * its contents. As in the NextUI Pak, a cartridge ROM in a .zip or .7z is hashed by the
 * file inside, because that is what emulators hand rcheevos.
 *
 * Disc consoles are hashed by [DiscHash] and [GameCube]: PlayStation, PlayStation 2, Sega CD,
 * Saturn, PC Engine CD and Dreamcast (CHD, CUE/BIN, ISO, GDI, M3U), PSP (ISO, CSO, PBP) and
 * GameCube (ISO/GCM, RVZ/WIA, GCZ) and Wii discs (ISO, RVZ/WIA, GCZ). Others (WiiWare, 3DO, Neo Geo CD, ...) aren't
 * supported: [supports] returns false and the caller falls back to a ROM the user picks.
 */
object RaHash {
    /** rcheevos hashes at most the first 64 MB. */
    private const val MAX_BUFFER = 64 * 1024 * 1024

    private val WHOLE_FILE = setOf(
        ARCADIA_2001, ATARI_2600, ATARI_JAGUAR, COLECOVISION, ELEKTOR_TV_GAMES_COMPUTER, FAIRCHILD_CHANNEL_F,
        GAMEBOY, GAMEBOY_ADVANCE, GAMEBOY_COLOR, GAME_GEAR, INTELLIVISION, INTERTON_VC_4000, MAGNAVOX_ODYSSEY2,
        MASTER_SYSTEM, MEGADUCK, NEOGEO_POCKET, ORIC, POKEMON_MINI, SEGA_32X, SG1000, SUPERVISION, TI83, TIC80,
        UZEBOX, VECTREX, VIRTUAL_BOY, WASM4, WONDERSWAN, ZX_SPECTRUM,
        // Whole file, except .m3u playlists (not supported here).
        MEGA_DRIVE, AMSTRAD_PC, APPLE_II, COMMODORE_64, MSX, PC8800,
    )

    /** Consoles whose hash skips a header, which needs the whole file in memory. */
    private val BUFFERED = setOf(ATARI_7800, ATARI_LYNX, FAMICOM_DISK_SYSTEM, NINTENDO, PC_ENGINE, SUPER_CASSETTEVISION, SUPER_NINTENDO)

    /** Files that are never ROMs (artwork, saves, notes), skipped inside zips. */
    private val IGNORED = setOf(
        "txt", "png", "jpg", "jpeg", "gif", "bmp", "xml", "dat", "nfo", "pdf",
        "sav", "srm", "state", "cfg", "ini", "db", "json", "log", "ips", "bps", "ups",
    )

    /** Disc consoles and the image formats their hash reads. */
    private val DISC = setOf(
        RaConsoles.PLAYSTATION, RaConsoles.PLAYSTATION_2, RaConsoles.SEGA_CD, RaConsoles.SATURN,
        RaConsoles.PC_ENGINE_CD, RaConsoles.DREAMCAST, RaConsoles.PSP, RaConsoles.GAMECUBE,
        RaConsoles.WII,
    )
    /** Compressed GameCube and Wii images that can't be read yet (RVZ, WIA and GCZ can). */
    private val COMPRESSED_GAMECUBE = setOf("wbfs", "ciso")

    private val DISC_IMAGES = setOf("chd", "cue", "iso", "bin", "img", "m3u", "gdi", "cso", "gcm", "pbp", "rvz", "wia", "gcz")

    fun supports(consoleId: Int): Boolean =
        consoleId in WHOLE_FILE || consoleId in BUFFERED || consoleId == NINTENDO_64 ||
            consoleId == NINTENDO_DS || consoleId == NINTENDO_DSI || consoleId == ARCADE || consoleId in DISC

    fun isIgnored(fileName: String): Boolean =
        fileName.startsWith('.') || fileName.substringAfterLast('.', "").lowercase() in IGNORED

    /** The file's RA hash for [consoleId], or null if unsupported or the file isn't that console's format. */
    @Throws(IOException::class)
    fun hash(consoleId: Int, file: RomFile): String? {
        if (!supports(consoleId)) return null
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if ((consoleId == RaConsoles.GAMECUBE || consoleId == RaConsoles.WII) && ext in COMPRESSED_GAMECUBE) throw UnreadableFormatException(ext.uppercase())
        if (consoleId == ARCADE) return arcade(file)
        if (consoleId in DISC) return disc(consoleId, file)
        if (file.name.endsWith(".zip", ignoreCase = true)) {
            val (innerName, bytes) = firstRomInZip(file) ?: return null
            return hash(consoleId, BytesRom(innerName, file.parentName, bytes))
        }
        if (file.name.endsWith(".7z", ignoreCase = true)) {
            val (innerName, bytes) = firstRomIn7z(file) ?: return null
            return hash(consoleId, BytesRom(innerName, file.parentName, bytes))
        }
        return when (consoleId) {
            in WHOLE_FILE -> file.open().use { md5(it, MAX_BUFFER.toLong()) }
            in BUFFERED -> buffered(consoleId, file.open().use { readUpTo(it, MAX_BUFFER) })
            NINTENDO_64 -> n64(file)
            NINTENDO_DS, NINTENDO_DSI -> nintendoDs(file)
            else -> null
        }
    }

    // ── Console rules ────────────────────────────────────────────────────────

    private fun disc(consoleId: Int, file: RomFile): String? {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext !in DISC_IMAGES) return null
        return when (consoleId) {
            // A plain disc image, read directly (no CD sectors): compressed RVZ/GCZ aren't readable.
            RaConsoles.GAMECUBE -> when (ext) {
                "iso", "gcm" -> file.openChannel()?.use { GameCube.hash(it) }
                // Dolphin's compressed formats, read as the plain disc.
                "rvz", "wia" -> file.openChannel()?.let { Rvz(it) }?.use { GameCube.hash(it) }
                "gcz" -> file.openChannel()?.let { Gcz(it) }?.use { GameCube.hash(it::read) }
                else -> null
            }
            RaConsoles.WII -> when (ext) {
                "iso" -> file.openChannel()?.use { Wii.hash(it) }
                // Read as the original disc: Wii partitions are re-encrypted.
                "rvz", "wia" -> file.openChannel()?.let { Rvz(it) }?.use { Wii.hash(it) }
                "gcz" -> file.openChannel()?.let { Gcz(it) }?.use { Wii.hash(it::read) }
                else -> null
            }
            // A PBP bundles the game's files; rcheevos hashes all of it.
            RaConsoles.PSP -> if (ext == "pbp") file.open().use { md5(it, MAX_BUFFER.toLong()) }
                else CdImages.openFirstTrack(file)?.use { DiscHash.psp(it) }
            RaConsoles.DREAMCAST -> DiscHash.dreamcast { CdImages.openTrack(file, it) }
            RaConsoles.PC_ENGINE_CD -> CdImages.openTrack(file, TrackChoice.FirstData)?.use { DiscHash.pcEngineCd(it) }
            RaConsoles.SEGA_CD, RaConsoles.SATURN -> CdImages.openFirstTrack(file)?.use { DiscHash.segaCd(it) }
            RaConsoles.PLAYSTATION -> CdImages.openFirstTrack(file)?.use { DiscHash.playstation(it) }
            else -> CdImages.openFirstTrack(file)?.use { DiscHash.playstation2(it) }
        }
    }

    private fun buffered(consoleId: Int, b: ByteArray): String = when (consoleId) {
        ATARI_7800 -> if (b.size > 10 && ascii(b, 1, "ATARI7800")) md5(b, 128) else md5(b)
        ATARI_LYNX -> if (b.size > 5 && ascii(b, 0, "LYNX") && b[4] == 0.toByte()) md5(b, 64) else md5(b)
        NINTENDO, FAMICOM_DISK_SYSTEM ->
            if (b.size >= 4 && (ascii(b, 0, "NES\u001a") || ascii(b, 0, "FDS\u001a"))) md5(b, 16) else md5(b)
        // The PCE header has no marks: anything 512 bytes over a multiple of 8 KB.
        PC_ENGINE -> if (b.size and 512 != 0) md5(b, 512) else md5(b)
        SUPER_CASSETTEVISION -> if (b.size >= 6 && ascii(b, 0, "EmuSCV")) md5(b, 32) else md5(b)
        SUPER_NINTENDO -> if (b.size % 0x2000 == 512) md5(b, 512) else md5(b)
        else -> md5(b)
    }

    /** N64 ROMs come in three byte orders; RA hashes the big-endian (.z64) form. */
    private fun n64(file: RomFile): String? = file.open().use { input ->
        val first = input.read()
        if (first < 0) return null
        val mode = when (first) {
            0x80, 0xE8, 0x22 -> 0 // z64, or 64DD (not swapped)
            0x37 -> 16 // v64, byte-swapped
            0x40 -> 32 // n64, little-endian
            else -> return null // not an N64 ROM
        }
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(65536)
        var pending = first // the byte already read to detect the format
        var remaining = MAX_BUFFER.toLong()
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            var filled = 0
            if (pending >= 0) { buf[0] = pending.toByte(); filled = 1; pending = -1 }
            while (filled < want) {
                val r = input.read(buf, filled, want - filled)
                if (r < 0) break
                filled += r
            }
            if (filled == 0) break
            // Chunks are multiples of 4 bytes except the last, as in rcheevos.
            if (mode == 16) {
                var i = 0
                while (i + 1 < filled) { val t = buf[i]; buf[i] = buf[i + 1]; buf[i + 1] = t; i += 2 }
            } else if (mode == 32) {
                var i = 0
                while (i + 3 < filled) {
                    val b0 = buf[i]; val b1 = buf[i + 1]
                    buf[i] = buf[i + 3]; buf[i + 1] = buf[i + 2]; buf[i + 2] = b1; buf[i + 3] = b0
                    i += 4
                }
            }
            md.update(buf, 0, filled)
            remaining -= filled
            if (filled < want) break
        }
        hex(md.digest())
    }

    /** NDS: the 0x160-byte header, the arm9 and arm7 code, and 0xA00 bytes of icon and titles. */
    private fun nintendoDs(file: RomFile): String? {
        var offset = 0L
        var header = readAt(file, 0, 512)
        if (header.size < 512) return null
        if (u8(header, 0) == 0x2E && u8(header, 1) == 0 && u8(header, 2) == 0 && u8(header, 3) == 0xEA &&
            u8(header, 0xB0) == 0x44 && u8(header, 0xB1) == 0x46 && u8(header, 0xB2) == 0x96 && u8(header, 0xB3) == 0
        ) {
            offset = 512 // SuperCard header
            header = readAt(file, offset, 512)
        }
        val arm9Addr = le32(header, 0x20); val arm9Size = le32(header, 0x2C)
        val arm7Addr = le32(header, 0x30); val arm7Size = le32(header, 0x3C)
        val iconAddr = le32(header, 0x68)
        if (arm9Size + arm7Size > 16L * 1024 * 1024) return null // not a DS ROM

        val md = MessageDigest.getInstance("MD5")
        md.update(header, 0, 0x160)
        md.update(readAt(file, arm9Addr + offset, arm9Size.toInt()))
        md.update(readAt(file, arm7Addr + offset, arm7Size.toInt()))
        md.update(readAt(file, iconAddr + offset, 0xA00).copyOf(0xA00)) // zero-padded if short
        return hex(md.digest())
    }

    /**
     * Arcade games are identified by file name without extension, plus the folder name for
     * FBNeo's console subsystems ("megadrive_sonic").
     */
    internal fun arcade(file: RomFile): String {
        val base = file.name.substringBeforeLast('.', file.name)
        val folder = file.parentName?.lowercase()
        val subsystems = setOf(
            "nes", "fds", "sms", "msx", "ngp", "pce", "chf", "sgx", "tg16", "msx1", "neocd", "coleco", "sg1000",
            "genesis", "gamegear", "megadriv", "pcengine", "channelf", "spectrum", "megadrive", "supergrafx",
            "zxspectrum", "mastersystem", "colecovision",
        )
        val text = if (folder != null && folder.length < 16 && folder in subsystems) "${folder}_$base" else base
        return md5(text.toByteArray(Charsets.UTF_8))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The first file in a zip that looks like a ROM, skipping folders, dot-files and readmes. */
    private fun firstRomInZip(file: RomFile): Pair<String, ByteArray>? = ZipInputStream(file.open()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name.substringAfterLast('/')
            if (!entry.isDirectory && name.isNotEmpty() && !entry.name.startsWith("__MACOSX/") && !isIgnored(name)) {
                return name to readUpTo(zip, MAX_BUFFER)
            }
            entry = zip.nextEntry
        }
        null
    }

    /**
     * The first file in a 7z archive that looks like a ROM, as for zips. 7z keeps its index at
     * the end of the file, so it needs random access ([RomFile.openChannel]); without it the
     * archive can't be read and gets no hash.
     */
    private fun firstRomIn7z(file: RomFile): Pair<String, ByteArray>? {
        val channel = file.openChannel() ?: return null
        return SevenZFile.builder().setSeekableByteChannel(channel).get().use { archive ->
            val entry = archive.entries.firstOrNull { e ->
                val name = e.name.orEmpty().substringAfterLast('/')
                !e.isDirectory && e.hasStream() && name.isNotEmpty() && !e.name.startsWith("__MACOSX/") && !isIgnored(name)
            } ?: return@use null
            entry.name.substringAfterLast('/') to archive.getInputStream(entry).use { readUpTo(it, MAX_BUFFER) }
        }
    }

    /**
     * The ROM inside a .zip or .7z (the first file that looks like one), as (name, bytes); null
     * for a file that isn't an archive. Used to patch a zipped base ROM.
     */
    @Throws(IOException::class)
    fun unwrap(file: RomFile): Pair<String, ByteArray>? = when {
        file.name.endsWith(".zip", ignoreCase = true) -> firstRomInZip(file)
        file.name.endsWith(".7z", ignoreCase = true) -> firstRomIn7z(file)
        else -> null
    }

    private class BytesRom(override val name: String, override val parentName: String?, val bytes: ByteArray) : RomFile {
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun readAt(file: RomFile, offset: Long, length: Int): ByteArray = file.open().use { input ->
        var toSkip = offset
        while (toSkip > 0) {
            val skipped = input.skip(toSkip)
            if (skipped <= 0) { if (input.read() < 0) return ByteArray(0); toSkip-- } else toSkip -= skipped
        }
        readUpTo(input, length)
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        var total = 0
        while (total < max) {
            val r = input.read(buf, 0, minOf(buf.size, max - total))
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }

    private fun md5(input: InputStream, max: Long): String {
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(65536)
        var remaining = max
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r < 0) break
            md.update(buf, 0, r)
            remaining -= r
        }
        return hex(md.digest())
    }

    private fun md5(bytes: ByteArray, skip: Int = 0): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(bytes, skip, bytes.size - skip)
        return hex(md.digest())
    }

    private fun ascii(b: ByteArray, at: Int, text: String): Boolean =
        b.size >= at + text.length && text.indices.all { b[at + it] == text[it].code.toByte() }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
