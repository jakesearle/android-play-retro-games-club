package app.retrolauncher.club.disc

import app.retrolauncher.club.RomFile
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** A track of a disc image, read as 2048-byte user-data sectors. */
interface CdTrack : Closeable {
    /** Up to [size] (≤ 2048) bytes of user data from the track's sector [lba] (0 = its first); shorter or empty past the end. */
    fun read(lba: Int, size: Int = 2048): ByteArray

    /**
     * The disc's sector number for this track's first sector. ISO 9660 records absolute sector
     * numbers, so a track that doesn't start at 0 (a Dreamcast GD-ROM's data track starts at
     * 45000) needs it to find files.
     */
    val firstSector: Int get() = 0
}

/** Reads by the disc's absolute sector number (as ISO 9660 directories give them). */
fun CdTrack.readAbsolute(sector: Int, size: Int = 2048): ByteArray = read(sector - firstSector, size)

/** Which track to open, as rcheevos asks for them. */
sealed interface TrackChoice {
    data class Number(val number: Int) : TrackChoice
    data object FirstData : TrackChoice
    data object Last : TrackChoice
}

/**
 * Opens a track of a disc image, the way emulators hand it to rcheevos: CHD, CUE (with its
 * BINs), GDI (Dreamcast), raw BIN, ISO, CSO (compressed ISO), or an M3U playlist (its first disc).
 */
object CdImages {
    fun openFirstTrack(file: RomFile, depth: Int = 0): CdTrack? = openTrack(file, TrackChoice.Number(1), depth)

    fun openTrack(file: RomFile, choice: TrackChoice, depth: Int = 0): CdTrack? {
        if (depth > 2) return null
        return when (file.name.substringAfterLast('.', "").lowercase()) {
            "chd" -> file.openChannel()?.let { chdTrack(it, choice) }
            "cue" -> cueTrack(file, choice)
            "gdi" -> gdiTrack(file, choice)
            "iso", "gcm" -> if (isFirst(choice)) file.openChannel()?.let { RawTrack(it, sectorSize = 2048, dataOffset = 0, startOffset = 0) } else null
            "cso" -> if (isFirst(choice)) file.openChannel()?.let { Cso(it) } else null
            "bin", "img" -> if (isFirst(choice)) file.openChannel()?.let(::binTrack) else null
            "m3u" -> firstLine(file)?.let { file.sibling(it) }?.let { openTrack(it, choice, depth + 1) }
            else -> null
        }
    }

    /** A single-track image has only track 1, which is also its first data and last track. */
    private fun isFirst(choice: TrackChoice) = choice !is TrackChoice.Number || choice.number == 1

    /** Picks the track from (number, is data) pairs, in disc order. */
    private fun <T> choose(tracks: List<T>, choice: TrackChoice, number: (T) -> Int, isData: (T) -> Boolean): T? = when (choice) {
        is TrackChoice.Number -> tracks.firstOrNull { number(it) == choice.number }
        TrackChoice.FirstData -> tracks.firstOrNull(isData)
        TrackChoice.Last -> tracks.lastOrNull()
    }

    private fun chdTrack(channel: SeekableByteChannel, choice: TrackChoice): CdTrack? {
        val chd = Chd(channel)
        // No CD tracks and 2048-byte units: a DVD image (PS2, PSP), plain 2048-byte sectors.
        if (chd.tracks.isEmpty() && chd.unitBytes == DVD_SECTOR) {
            if (!isFirst(choice)) return null.also { chd.close() }
            return object : CdTrack {
                override fun read(lba: Int, size: Int): ByteArray {
                    if (lba < 0 || lba.toLong() * DVD_SECTOR >= chd.logicalSize) return ByteArray(0)
                    return chd.sector(lba).copyOf(minOf(size, DVD_SECTOR))
                }

                override fun close() = chd.close()
            }
        }
        val tracks = chd.tracks.ifEmpty { listOf(Chd.Track(1, "MODE1_RAW", 0, 0, false, 0)) }
        val track = choose(tracks, choice, { it.number }, { userDataOffset(it.type) != null })
            ?: return null.also { chd.close() }
        val offset = userDataOffset(track.type) ?: return null.also { chd.close() }
        val skip = if (track.pregapInData) track.pregap else 0
        val first = absoluteStart(tracks, track, chd.gdRom)
        return object : CdTrack {
            override val firstSector = first
            override fun read(lba: Int, size: Int): ByteArray {
                if (lba < 0 || (track.frames > 0 && lba + skip >= track.frames)) return ByteArray(0)
                val sector = chd.sector(track.startFrame + skip + lba)
                return sector.copyOfRange(offset, minOf(offset + size, offset + 2048, sector.size))
            }

            override fun close() = chd.close()
        }
    }

    /**
     * Where a CHD track starts on the disc. CHDs store tracks back to back (padded to 4 frames),
     * so this adds up the tracks before it, with their pregaps. A GD-ROM's high-density area
     * (track 3 on) starts at sector 45000.
     */
    private fun absoluteStart(tracks: List<Chd.Track>, track: Chd.Track, gdRom: Boolean): Int {
        fun length(t: Chd.Track) = t.frames + if (t.pregapInData) 0 else t.pregap
        if (gdRom && track.number >= 3) {
            return GD_HIGH_DENSITY + tracks.filter { it.number in 3 until track.number }.sumOf(::length) +
                if (track.pregapInData) track.pregap else 0
        }
        return tracks.filter { it.number < track.number }.sumOf(::length) + track.pregap
    }

    /** Where user data starts in a CHD track's 2352-byte frame. */
    private fun userDataOffset(type: String): Int? = when (type.uppercase()) {
        "MODE1", "MODE2_FORM1" -> 0
        "MODE2", "MODE2_FORM_MIX" -> 8
        "MODE1_RAW" -> 16
        "MODE2_RAW" -> 24
        else -> null // AUDIO, MODE2_FORM2
    }

    /** A raw BIN: 2352-byte sectors whose mode byte says where data starts, or 2048-byte ISO sectors. */
    private fun binTrack(channel: SeekableByteChannel): CdTrack {
        val size = channel.size()
        if (size % 2352 != 0L && size % 2048 == 0L) return RawTrack(channel, 2048, 0, 0)
        val header = ByteBuffer.allocate(16)
        channel.position(0)
        channel.read(header)
        val mode = header.get(15).toInt()
        return RawTrack(channel, 2352, if (mode == 1) 16 else 24, 0)
    }

    private val FILE = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))""", RegexOption.IGNORE_CASE)
    private val TRACK = Regex("""^\s*TRACK\s+(\d+)\s+(\S+)""", RegexOption.IGNORE_CASE)
    private val INDEX1 = Regex("""^\s*INDEX\s+01\s+(\d+):(\d+):(\d+)""", RegexOption.IGNORE_CASE)

    /** A track in a CUE sheet: its BIN, mode, and where its data starts in that file (in frames). */
    internal data class CueTrack(val number: Int, val file: String, val mode: String, val indexFrame: Int)

    internal fun parseCue(text: String): List<CueTrack> {
        val out = mutableListOf<CueTrack>()
        var fileName: String? = null
        var number = 0
        var mode: String? = null
        for (line in text.lines()) {
            FILE.find(line)?.let { fileName = it.groupValues[1].ifEmpty { it.groupValues[2] } }
            TRACK.find(line)?.let { number = it.groupValues[1].toInt(); mode = it.groupValues[2].uppercase() }
            val i = INDEX1.find(line)
            if (i != null && mode != null && fileName != null) {
                val (m, sec, f) = i.destructured
                out += CueTrack(number, fileName!!, mode!!, (m.toInt() * 60 + sec.toInt()) * 75 + f.toInt())
                mode = null
            }
        }
        return out
    }

    /** Sector size and where user data starts in it, for a CUE track mode; null for audio. */
    private fun cueGeometry(mode: String): Pair<Int, Int>? = when (mode) {
        "MODE1/2048" -> 2048 to 0
        "MODE1/2352" -> 2352 to 16
        "MODE2/2336" -> 2336 to 8
        "MODE2/2352" -> 2352 to 24
        else -> null
    }

    /** A track of a CUE sheet, read from its BIN file in the same folder. */
    private fun cueTrack(cue: RomFile, choice: TrackChoice): CdTrack? {
        val tracks = parseCue(cue.open().use { it.readBytes() }.decodeToString())
        // A disc that starts with audio isn't a data disc, as far as track 1 goes.
        val track = choose(tracks, choice, { it.number }, { cueGeometry(it.mode) != null }) ?: return null
        val (sectorSize, dataOffset) = cueGeometry(track.mode) ?: return null
        // The disc's sector number: the frames of every earlier BIN, plus this track's index in its own.
        var first = track.indexFrame
        for (file in tracks.map { it.file }.distinct().takeWhile { it != track.file }) {
            val size = cue.sibling(file)?.openChannel()?.use { it.size() } ?: 0L
            val earlier = tracks.first { it.file == file }
            first += (size / (cueGeometry(earlier.mode)?.first ?: 2352)).toInt()
        }
        val channel = cue.sibling(track.file)?.openChannel() ?: return null
        return RawTrack(channel, sectorSize, dataOffset, track.indexFrame.toLong() * sectorSize, first)
    }

    /**
     * A track of a Dreamcast GDI: each line is "track sector type sectorSize file offset", where
     * type 4 is data and 0 audio, and the sector is where the track starts on the disc.
     */
    private fun gdiTrack(gdi: RomFile, choice: TrackChoice): CdTrack? {
        val tracks = parseGdi(gdi.open().use { it.readBytes() }.decodeToString())
        val track = choose(tracks, choice, { it.number }, { it.data }) ?: return null
        if (!track.data) return null
        val channel = gdi.sibling(track.file)?.openChannel() ?: return null
        val dataOffset = if (track.sectorSize == 2352) 16 else 0
        return RawTrack(channel, track.sectorSize, dataOffset, track.offset, track.sector)
    }

    internal data class GdiTrack(val number: Int, val sector: Int, val data: Boolean, val sectorSize: Int, val file: String, val offset: Long)

    private val GDI_LINE = Regex("""^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(?:"([^"]+)"|(\S+))\s+(-?\d+)""")

    internal fun parseGdi(text: String): List<GdiTrack> = text.lines().drop(1).mapNotNull { line ->
        val m = GDI_LINE.find(line) ?: return@mapNotNull null
        val g = m.groupValues
        GdiTrack(g[1].toInt(), g[2].toInt(), g[3] == "4", g[4].toInt(), g[5].ifEmpty { g[6] }, g[7].toLong())
    }

    private fun firstLine(file: RomFile): String? =
        file.open().use { it.readBytes() }.decodeToString().lines()
            .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private class RawTrack(
        private val channel: SeekableByteChannel,
        private val sectorSize: Int,
        private val dataOffset: Int,
        private val startOffset: Long,
        override val firstSector: Int = 0,
    ) : CdTrack {
        override fun read(lba: Int, size: Int): ByteArray {
            if (lba < 0) return ByteArray(0)
            val position = startOffset + lba.toLong() * sectorSize + dataOffset
            val want = minOf(size, 2048, sectorSize - dataOffset)
            if (position >= channel.size()) return ByteArray(0)
            val buf = ByteBuffer.allocate(want)
            channel.position(position)
            while (buf.hasRemaining()) if (channel.read(buf) < 0) break
            return buf.array().copyOf(buf.position())
        }

        override fun close() = channel.close()
    }

    private const val DVD_SECTOR = 2048

    /** Where a GD-ROM's high-density area starts. */
    const val GD_HIGH_DENSITY = 45000
}

/**
 * A CSO (compressed ISO, common for PSP games): a header, an index of block offsets, then
 * blocks that are raw Deflate unless the index entry's top bit marks them stored. PPSSPP hashes
 * the decompressed image, so a CSO gets the same RetroAchievements hash as its ISO.
 */
class Cso(private val channel: SeekableByteChannel) : CdTrack {
    private val blockSize: Int
    private val align: Int
    private val index: IntArray
    private var cached = -1
    private var block = ByteArray(0)

    init {
        val h = readAt(0, 24)
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "CISO") throw IOException("Not a CSO file")
        val total = le64(h, 8)
        blockSize = le32(h, 16)
        align = h[21].toInt() and 0xFF
        if (blockSize <= 0 || blockSize % 2048 != 0) throw IOException("Unsupported CSO block size $blockSize")
        val blocks = ((total + blockSize - 1) / blockSize).toInt()
        val raw = readAt(24, (blocks + 1) * 4)
        index = IntArray(blocks + 1) { le32(raw, it * 4) }
    }

    override fun read(lba: Int, size: Int): ByteArray {
        val offset = lba.toLong() * 2048
        val n = (offset / blockSize).toInt()
        if (lba < 0 || n >= index.size - 1) return ByteArray(0)
        if (n != cached) { block = decode(n); cached = n }
        val at = (offset % blockSize).toInt()
        return block.copyOfRange(at, minOf(at + minOf(size, 2048), block.size))
    }

    private fun decode(n: Int): ByteArray {
        val stored = index[n] < 0
        val start = (index[n].toLong() and 0x7FFFFFFF) shl align
        val end = (index[n + 1].toLong() and 0x7FFFFFFF) shl align
        val data = readAt(start, (end - start).toInt().coerceAtMost(blockSize + 1024))
        if (stored) return data.copyOf(blockSize)
        val inflater = java.util.zip.Inflater(true)
        return try {
            inflater.setInput(data)
            val out = ByteArray(blockSize)
            var got = 0
            while (got < blockSize && !inflater.finished()) {
                val k = inflater.inflate(out, got, blockSize - got)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                got += k
            }
            out
        } finally {
            inflater.end()
        }
    }

    private fun readAt(position: Long, size: Int): ByteArray {
        val buf = ByteBuffer.allocate(size)
        channel.position(position)
        while (buf.hasRemaining()) if (channel.read(buf) < 0) break
        return buf.array().copyOf(buf.position())
    }

    override fun close() = channel.close()

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun le64(b: ByteArray, i: Int) = (le32(b, i).toLong() and 0xFFFFFFFFL) or (le32(b, i + 4).toLong() shl 32)
}

/** A seekable view of bytes in memory, for tests and small files. */
class ByteArrayChannel(private val bytes: ByteArray) : SeekableByteChannel {
    private var pos = 0L
    private var open = true
    override fun read(dst: ByteBuffer): Int {
        if (pos >= bytes.size) return -1
        val n = minOf(dst.remaining().toLong(), bytes.size - pos).toInt()
        dst.put(bytes, pos.toInt(), n)
        pos += n
        return n
    }
    override fun write(src: ByteBuffer): Int = throw IOException("read-only")
    override fun position() = pos
    override fun position(newPosition: Long): SeekableByteChannel = apply { pos = newPosition }
    override fun size() = bytes.size.toLong()
    override fun truncate(size: Long): SeekableByteChannel = throw IOException("read-only")
    override fun isOpen() = open
    override fun close() { open = false }
}
