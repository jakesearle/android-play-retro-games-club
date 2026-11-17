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
        val header = track.read(sector, 32)
        if (header.size < 32) return null
        // The PS-X EXE header gives the code size 28 bytes in, not counting the 2048-byte header.
        if (String(header, 0, 7, Charsets.ISO_8859_1) == "PS-X EX") size = le32(header, 28) + 2048
        return hashFile(track, name, sector, size)
    }

    fun playstation2(track: CdTrack): String? {
        val (name, sector, size) = findExecutable(track, bootKey = "BOOT2", cdromPrefix = "cdrom0:") ?: return null
        if (track.read(sector, 4).size < 4) return null
        return hashFile(track, name, sector, size)
    }

    /** MD5 of the executable's name, then its contents ([size] bytes from [sector]). */
    private fun hashFile(track: CdTrack, name: String, sector: Int, sizeIn: Int): String? {
        val md = MessageDigest.getInstance("MD5")
        md.update(name.toByteArray(Charsets.ISO_8859_1))
        val first = track.read(sector, 2048)
        if (first.size < 2048) return null
        var size = minOf(sizeIn.toLong() and 0xFFFFFFFFL, MAX_BUFFER.toLong()).toInt()
        var current = sector
        var chunk = first
        while (true) {
            val n = minOf(size, chunk.size)
            md.update(chunk, 0, n)
            if (size <= chunk.size) break
            size -= chunk.size
            current++
            chunk = track.read(current, minOf(size, 2048))
            if (chunk.isEmpty()) break
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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
            // The primary volume descriptor is sector 16; the root directory record starts at 156.
            val pvd = track.read(16, 256)
            if (pvd.size < 256) return null
            sector = le24(pvd, 156 + 2)
            val blockSize = (pvd[128].toInt() and 0xFF) or ((pvd[129].toInt() and 0xFF) shl 8)
            numSectors = if (blockSize == 0) 1 else le32(pvd, 156 + 10) / blockSize
        }

        val name = path.toByteArray(Charsets.ISO_8859_1)
        var buffer = track.read(sector, 2048)
        if (buffer.isEmpty()) return null
        var at = 0
        while (true) {
            if (at >= buffer.size || buffer[at].toInt() == 0) {
                // End of this directory sector; directories can span several.
                if (numSectors > 1) {
                    numSectors--
                    buffer = track.read(++sector, 2048)
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
