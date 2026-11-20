package app.retrolauncher.club.disc

import java.security.MessageDigest

/**
 * RetroAchievements hashes for PlayStation and PlayStation 2 discs, ported from rcheevos
 * (`src/rhash/hash_disc.c`, MIT): the MD5 of the boot executable's name followed by the
 * executable itself, found through SYSTEM.CNF on the disc's ISO 9660 file system.
 */
object DiscHash {
    /** rcheevos hashes at most the first 64 MB of a file. */
    private const val MAX_BUFFER = 64 * 1024 * 1024

    fun playstation(track: CdTrack): String? {
        var found = findExecutable(track, bootKey = "BOOT", cdromPrefix = "cdrom:")
        if (found == null) {
            findFileSector(track, "PSX.EXE")?.let { (sector, size) -> found = Triple("PSX.EXE", sector, size) }
        }
        var (name, sector, size) = found ?: return null
        val header = track.readAbsolute(sector, 32)
        if (header.size < 32) return null
        // The PS-X EXE header gives the code size 28 bytes in, not counting the 2048-byte header.
        if (String(header, 0, 7, Charsets.ISO_8859_1) == "PS-X EX") size = le32(header, 28) + 2048
        return hashFile(track, name, sector, size)
    }

    fun playstation2(track: CdTrack): String? {
        val (name, sector, size) = findExecutable(track, bootKey = "BOOT2", cdromPrefix = "cdrom0:") ?: return null
        if (track.readAbsolute(sector, 4).size < 4) return null
        return hashFile(track, name, sector, size)
    }

    /** MD5 of the executable's name, then its contents ([size] bytes from [sector]). */
    private fun hashFile(track: CdTrack, name: String, sector: Int, sizeIn: Int): String? {
        val md = MessageDigest.getInstance("MD5")
        md.update(name.toByteArray(Charsets.ISO_8859_1))
        if (!appendFile(md, track, sector, sizeIn)) return null
        return hex(md)
    }

    /**
     * Adds a file's contents ([size] bytes from absolute [sector]) to [md], as rcheevos'
     * rc_hash_cd_file: the first sector must read in full; at most 64 MB is hashed.
     */
    private fun appendFile(md: MessageDigest, track: CdTrack, sector: Int, sizeIn: Int): Boolean {
        val first = track.readAbsolute(sector, 2048)
        if (first.size < 2048) return false
        var size = minOf(sizeIn.toLong() and 0xFFFFFFFFL, MAX_BUFFER.toLong()).toInt()
        var current = sector
        var chunk = first
        while (true) {
            val n = minOf(size, chunk.size)
            md.update(chunk, 0, n)
            if (size <= chunk.size) break
            size -= chunk.size
            current++
            chunk = track.readAbsolute(current, minOf(size, 2048))
            if (chunk.isEmpty()) break
        }
        return true
    }

    private fun hex(md: MessageDigest) = md.digest().joinToString("") { "%02x".format(it) }

    private fun startsWith(b: ByteArray, at: Int, text: String) =
        b.size >= at + text.length && String(b, at, text.length, Charsets.ISO_8859_1) == text

    // ── Sega CD and Saturn ───────────────────────────────────────────────────

    /** The first 512 bytes of track 1 (the volume and ROM headers), if they're a Sega CD or Saturn's. */
    fun segaCd(track1: CdTrack): String? {
        val header = track1.read(0, 512)
        if (header.size < 512 || !(startsWith(header, 0, "SEGADISCSYSTEM  ") || startsWith(header, 0, "SEGA SEGASATURN "))) return null
        return hex(MessageDigest.getInstance("MD5").apply { update(header) })
    }

    // ── PC Engine CD ─────────────────────────────────────────────────────────

    /**
     * The first data track's boot header (sector 1): the 22-byte title, then the program
     * sectors it names. GameExpress discs have a plain file system instead: their BOOT.BIN.
     */
    fun pcEngineCd(dataTrack: CdTrack): String? {
        val header = dataTrack.read(1, 128)
        if (header.size < 128) return null
        val md = MessageDigest.getInstance("MD5")
        if (startsWith(header, 32, "PC Engine CD-ROM SYSTEM")) {
            md.update(header, 106, 22)
            val sector = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
            val count = header[3].toInt() and 0xFF
            for (i in 0 until count) md.update(dataTrack.read(sector + i, 2048).copyOf(2048))
            return hex(md)
        }
        val (sector, size) = findFileSector(dataTrack, "BOOT.BIN") ?: return null
        if (size >= MAX_BUFFER) return null
        if (!appendFile(md, dataTrack, sector, size)) return null
        return hex(md)
    }

    // ── Dreamcast ────────────────────────────────────────────────────────────

    /**
     * IP.BIN's first 256 bytes (track 3 on a GD-ROM, or the first data track of a MIL-CD), then
     * the boot executable it names: in that track, or else in the disc's last track.
     */
    fun dreamcast(open: (TrackChoice) -> CdTrack?): String? {
        var track = open(TrackChoice.Number(3))
        var meta = track?.read(0, 256) ?: ByteArray(0)
        if (!startsWith(meta, 0, "SEGA SEGAKATANA ")) {
            track?.close()
            track = open(TrackChoice.FirstData) ?: return null
            meta = track.read(0, 256)
            if (!startsWith(meta, 0, "SEGA SEGAKATANA ")) return null.also { track.close() }
        }
        try {
            val md = MessageDigest.getInstance("MD5")
            md.update(meta, 0, 256)
            var length = 0
            while (length < 16 && !Character.isWhitespace(meta[96 + length].toInt().toChar())) length++
            if (length == 0) return null
            val exe = String(meta, 96, length, Charsets.ISO_8859_1)
            val (sector, size) = findFileSector(track!!, exe) ?: return null
            if (track.readAbsolute(sector, 1).isEmpty()) {
                track.close()
                track = open(TrackChoice.Last) ?: return null
            }
            if (!appendFile(md, track!!, sector, size)) return null
            return hex(md)
        } finally {
            track?.close()
        }
    }

    // ── PSP ──────────────────────────────────────────────────────────────────

    /** PSP_GAME/PARAM.SFO (the game's identity) followed by PSP_GAME/SYSDIR/EBOOT.BIN. */
    fun psp(track: CdTrack): String? {
        val md = MessageDigest.getInstance("MD5")
        val (sfo, sfoSize) = findFileSector(track, "PSP_GAME\\PARAM.SFO") ?: return null
        if (!appendFile(md, track, sfo, sfoSize)) return null
        val (boot, bootSize) = findFileSector(track, "PSP_GAME\\SYSDIR\\EBOOT.BIN") ?: return null
        if (!appendFile(md, track, boot, bootSize)) return null
        return hex(md)
    }

    /** Reads SYSTEM.CNF and finds the boot executable it names: (name, sector, size). */
    private fun findExecutable(track: CdTrack, bootKey: String, cdromPrefix: String): Triple<String, Int, Int>? {
        val (cnfSector, _) = findFileSector(track, "SYSTEM.CNF") ?: return null
        val text = String(track.read(cnfSector, 2047), Charsets.ISO_8859_1).substringBefore('\u0000')
        for (line in text.split('\n')) {
            if (!line.startsWith(bootKey)) continue
            var rest = line.substring(bootKey.length).trimStart()
            if (!rest.startsWith("=")) continue
            rest = rest.substring(1).trimStart()
            if (rest.startsWith(cdromPrefix)) rest = rest.substring(cdromPrefix.length)
            rest = rest.trimStart('\\')
            val name = rest.takeWhile { !it.isWhitespace() && it != ';' }.take(63)
            val (sector, size) = findFileSector(track, name) ?: return null
            return Triple(name, sector, size)
        }
        return null
    }

    /**
     * Finds a file on the disc's ISO 9660 file system, as rcheevos' `rc_cd_find_file_sector`
     * does (including its quirks: only a subdirectory's first sector is searched).
     * Returns (sector, size) or null.
     */
    fun findFileSector(track: CdTrack, pathIn: String): Pair<Int, Int>? {
        var path = pathIn.removePrefix("\\")
        var sector: Int
        var numSectors = 0
        val slash = path.lastIndexOf('\\')
        if (slash >= 0) {
            sector = findFileSector(track, path.substring(0, slash))?.first ?: return null
            path = path.substring(slash + 1)
        } else {
            // The primary volume descriptor is the track's sector 16; the root directory record starts at 156.
            val pvd = track.read(16, 256)
            if (pvd.size < 256) return null
            sector = le24(pvd, 156 + 2)
            val blockSize = (pvd[128].toInt() and 0xFF) or ((pvd[129].toInt() and 0xFF) shl 8)
            numSectors = if (blockSize == 0) 1 else le32(pvd, 156 + 10) / blockSize
        }

        val name = path.toByteArray(Charsets.ISO_8859_1)
        // Directory records give the disc's sector numbers, so read by absolute sector.
        var buffer = track.readAbsolute(sector, 2048)
        if (buffer.isEmpty()) return null
        var at = 0
        while (true) {
            if (at >= buffer.size || buffer[at].toInt() == 0) {
                // End of this directory sector; directories can span several.
                if (numSectors > 1) {
                    numSectors--
                    buffer = track.readAbsolute(++sector, 2048)
                    if (buffer.isNotEmpty()) { at = 0; continue }
                }
                return null
            }
            val nameLength = byteAt(buffer, at + 32)
            if ((nameLength == name.size || byteAt(buffer, at + 33 + name.size) == ';'.code) &&
                at + 33 + name.size <= buffer.size &&
                (name.indices).all { equalsIgnoreCase(buffer[at + 33 + it], name[it]) }
            ) {
                return le24(buffer, at + 2) to le32(buffer, at + 10)
            }
            val recordLength = buffer[at].toInt() and 0xFF
            at += recordLength
        }
    }

    private fun byteAt(b: ByteArray, i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
    private fun equalsIgnoreCase(a: Byte, b: Byte) = Character.toUpperCase(a.toInt().toChar()) == Character.toUpperCase(b.toInt().toChar())
    private fun le24(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16)
    private fun le32(b: ByteArray, i: Int) = le24(b, i) or ((b[i + 3].toInt() and 0xFF) shl 24)
}
