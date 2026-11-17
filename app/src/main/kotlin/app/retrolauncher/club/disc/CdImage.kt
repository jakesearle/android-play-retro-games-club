package app.retrolauncher.club.disc

import app.retrolauncher.club.RomFile
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** The data track of a disc image, read as 2048-byte user-data sectors by logical block address. */
interface CdTrack : Closeable {
    /** Up to [size] (≤ 2048) bytes of user data from sector [lba]; shorter or empty past the end. */
    fun read(lba: Int, size: Int = 2048): ByteArray
}

/**
 * Opens track 1 of a disc image, the way emulators hand it to rcheevos: CHD, CUE (with its
 * BIN), raw BIN, ISO, or an M3U playlist (its first disc).
 */
object CdImages {
    fun openFirstTrack(file: RomFile, depth: Int = 0): CdTrack? {
        if (depth > 2) return null
        return when (file.name.substringAfterLast('.', "").lowercase()) {
            "chd" -> file.openChannel()?.let(::chdTrack)
            "cue" -> cueTrack(file)
            "iso" -> file.openChannel()?.let { RawTrack(it, sectorSize = 2048, dataOffset = 0, startOffset = 0) }
            "bin", "img" -> file.openChannel()?.let(::binTrack)
            "m3u" -> firstLine(file)?.let { file.sibling(it) }?.let { openFirstTrack(it, depth + 1) }
            else -> null
        }
    }

    private fun chdTrack(channel: SeekableByteChannel): CdTrack {
        val chd = Chd(channel)
        val track = chd.tracks.firstOrNull() ?: Chd.Track(1, "MODE1_RAW", 0, 0, false, 0)
        val offset = userDataOffset(track.type) ?: throw ChdException("Track 1 is ${track.type}, not data")
        val skip = if (track.pregapInData) track.pregap else 0
        return object : CdTrack {
            override fun read(lba: Int, size: Int): ByteArray {
                if (lba < 0 || (track.frames > 0 && lba + skip >= track.frames)) return ByteArray(0)
                val sector = chd.sector(track.startFrame + skip + lba)
                return sector.copyOfRange(offset, minOf(offset + size, offset + 2048, sector.size))
            }

            override fun close() = chd.close()
        }
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

    /** The first track of a CUE sheet, read from its BIN file in the same folder. */
    private fun cueTrack(cue: RomFile): CdTrack? {
        val lines = cue.open().use { it.readBytes() }.decodeToString().lines()
        var fileName: String? = null
        var mode: String? = null
        for (line in lines) {
            FILE.find(line)?.let { fileName = it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val t = TRACK.find(line)
            if (t != null && mode == null) mode = t.groupValues[2].uppercase()
            val i = INDEX1.find(line)
            if (i != null && mode != null) {
                val (m, s, f) = i.destructured
                val frame = (m.toInt() * 60 + s.toInt()) * 75 + f.toInt()
                val bin = fileName?.let { cue.sibling(it) } ?: return null
                val channel = bin.openChannel() ?: return null
                val (sectorSize, dataOffset) = when (mode) {
                    "MODE1/2048" -> 2048 to 0
                    "MODE1/2352" -> 2352 to 16
                    "MODE2/2336" -> 2336 to 8
                    "MODE2/2352" -> 2352 to 24
                    else -> return null.also { channel.close() } // AUDIO first: not a data disc
                }
                return RawTrack(channel, sectorSize, dataOffset, frame.toLong() * sectorSize)
            }
        }
        return null
    }

    private fun firstLine(file: RomFile): String? =
        file.open().use { it.readBytes() }.decodeToString().lines()
            .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private class RawTrack(
        private val channel: SeekableByteChannel,
        private val sectorSize: Int,
        private val dataOffset: Int,
        private val startOffset: Long,
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
