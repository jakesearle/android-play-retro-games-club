package app.retrolauncher.club.disc

import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.zip.Inflater

/**
 * Reads CHD v5 disc images (MAME's compressed format, the usual one for PS1 games), ported
 * from libchdr (BSD-3-Clause). Supports the codecs `chdman createcd` uses: `cdlz` (LZMA),
 * `cdzl` (Deflate) and `cdfl` (FLAC), plus plain `lzma` and `zlib`. Others (`cdzs`/zstd,
 * `huff`, parent CHDs) report a [ChdException].
 *
 * Hunks are rebuilt byte for byte, including subcode and the ECC that `cdlz`/`cdzl` strip,
 * so each one can be checked against the CRC-16 stored in the map.
 */
class Chd(private val channel: SeekableByteChannel) : Closeable {
    val hunkBytes: Int
    val unitBytes: Int
    private val logicalBytes: Long
    private val codecs = IntArray(4)
    private val mapOffset: Long
    private val metaOffset: Long
    val hunkCount: Int

    private lateinit var mapType: ByteArray
    private lateinit var mapLength: IntArray
    private lateinit var mapOffsetOf: LongArray
    private lateinit var mapCrc: IntArray

    /** CD tracks from the CHT2/CHTR metadata, in order. */
    val tracks: List<Track>

    data class Track(val number: Int, val type: String, val frames: Int, val pregap: Int, val pregapInData: Boolean, val startFrame: Int)

    init {
        val h = read(0, 124)
        if (String(h, 0, 8, Charsets.ISO_8859_1) != "MComprHD") throw ChdException("Not a CHD file")
        val version = be32(h, 12)
        if (version != 5) throw ChdException("CHD version $version isn't supported (only v5)")
        for (i in 0 until 4) codecs[i] = be32(h, 16 + 4 * i)
        logicalBytes = be64(h, 32)
        mapOffset = be64(h, 40)
        metaOffset = be64(h, 48)
        hunkBytes = be32(h, 56)
        unitBytes = be32(h, 60)
        if (hunkBytes <= 0 || unitBytes <= 0 || hunkBytes % unitBytes != 0) throw ChdException("Bad CHD geometry")
        hunkCount = ((logicalBytes + hunkBytes - 1) / hunkBytes).toInt()
        // A parent SHA-1 means the image depends on another CHD.
        if ((104 until 124).any { h[it] != 0.toByte() }) throw ChdException("Parent CHDs aren't supported")
        readMap()
        tracks = readTracks()
    }

    // ── Map ──────────────────────────────────────────────────────────────────

    private fun readMap() {
        mapType = ByteArray(hunkCount)
        mapLength = IntArray(hunkCount)
        mapOffsetOf = LongArray(hunkCount)
        mapCrc = IntArray(hunkCount)

        if (codecs[0] == 0) {
            // Uncompressed CHD: a table of 4-byte hunk offsets, in hunks.
            val raw = read(mapOffset, hunkCount * 4)
            for (i in 0 until hunkCount) {
                mapType[i] = NONE.toByte()
                mapOffsetOf[i] = (be32(raw, i * 4).toLong() and 0xFFFFFFFFL) * hunkBytes
                mapLength[i] = hunkBytes
            }
            return
        }

        val header = read(mapOffset, 16)
        val mapBytes = be32(header, 0)
        val firstOffset = be48(header, 4)
        val lengthBits = header[12].toInt() and 0xFF
        val selfBits = header[13].toInt() and 0xFF
        val parentBits = header[14].toInt() and 0xFF
        val compressed = read(mapOffset + 16, mapBytes)
        val br = BitReader(compressed)

        // Pass 1: the compression type of each hunk, Huffman- and run-length coded.
        val huffman = Huffman(16, 8)
        huffman.importTreeRle(br)
        var last = 0
        var repeat = 0
        for (h in 0 until hunkCount) {
            if (repeat > 0) {
                mapType[h] = last.toByte(); repeat--
                continue
            }
            when (val v = huffman.decode(br)) {
                RLE_SMALL -> { mapType[h] = last.toByte(); repeat = 2 + huffman.decode(br) }
                RLE_LARGE -> {
                    mapType[h] = last.toByte()
                    repeat = 2 + 16 + (huffman.decode(br) shl 4)
                    repeat += huffman.decode(br)
                }
                else -> { mapType[h] = v.toByte(); last = v }
            }
        }

        // Pass 2: lengths, offsets and CRCs.
        var current = firstOffset
        var lastSelf = 0L
        var lastParent = 0L
        for (h in 0 until hunkCount) {
            var offset = current
            var length = 0
            var crc = 0
            when (mapType[h].toInt()) {
                in TYPE_0..TYPE_3 -> { length = br.read(lengthBits); current += length; crc = br.read(16) }
                NONE -> { length = hunkBytes; current += length; crc = br.read(16) }
                SELF -> { lastSelf = br.read(selfBits).toLong(); offset = lastSelf }
                PARENT -> { offset = br.read(parentBits).toLong(); lastParent = offset }
                SELF_1, SELF_0 -> {
                    if (mapType[h].toInt() == SELF_1) lastSelf++
                    mapType[h] = SELF.toByte(); offset = lastSelf
                }
                PARENT_SELF -> {
                    mapType[h] = PARENT.toByte()
                    lastParent = h.toLong() * hunkBytes / unitBytes; offset = lastParent
                }
                PARENT_1, PARENT_0 -> {
                    if (mapType[h].toInt() == PARENT_1) lastParent += hunkBytes / unitBytes
                    mapType[h] = PARENT.toByte(); offset = lastParent
                }
                else -> throw ChdException("Bad CHD map entry")
            }
            mapOffsetOf[h] = offset
            mapLength[h] = length
            mapCrc[h] = crc
        }
    }

    // ── Hunks ────────────────────────────────────────────────────────────────

    private val cache = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>) = size > 8
    }

    /** The decompressed hunk. CD hunks hold [hunkBytes] / [unitBytes] frames of 2352 + 96 bytes. */
    fun hunk(index: Int): ByteArray {
        if (index !in 0 until hunkCount) throw ChdException("Hunk $index out of range")
        cache[index]?.let { return it }
        val data = when (val type = mapType[index].toInt()) {
            in TYPE_0..TYPE_3 -> decompress(codecs[type], read(mapOffsetOf[index], mapLength[index]))
            NONE -> read(mapOffsetOf[index], hunkBytes)
            SELF -> hunk(mapOffsetOf[index].toInt())
            else -> throw ChdException("Parent CHDs aren't supported")
        }
        cache[index] = data
        return data
    }

    /** The stored CRC-16 of a hunk, for verification; null for copied hunks. */
    internal fun storedCrc(index: Int): Int? = when (mapType[index].toInt()) {
        in TYPE_0..TYPE_3, NONE -> mapCrc[index]
        else -> null
    }

    private fun decompress(codec: Int, src: ByteArray): ByteArray = when (codec) {
        CD_LZMA, CD_ZLIB -> cdDecompress(src, lzma = codec == CD_LZMA)
        CD_FLAC -> cdFlac(src)
        LZMA -> lzma(src, 0, src.size, hunkBytes)
        ZLIB -> inflate(src, 0, src.size, hunkBytes)
        else -> throw ChdException("CHD codec ${fourCc(codec)} isn't supported")
    }

    /** cdlz/cdzl: an ECC bitmap, the compressed sector data, then Deflate-compressed subcode. */
    private fun cdDecompress(src: ByteArray, lzma: Boolean): ByteArray {
        val frames = hunkBytes / FRAME_SIZE
        val lengthBytes = if (hunkBytes < 65536) 2 else 3
        val eccBytes = (frames + 7) / 8
        val headerBytes = eccBytes + lengthBytes
        var baseLength = ((src[eccBytes].toInt() and 0xFF) shl 8) or (src[eccBytes + 1].toInt() and 0xFF)
        if (lengthBytes > 2) baseLength = (baseLength shl 8) or (src[eccBytes + 2].toInt() and 0xFF)
        val sectors = frames * SECTOR_SIZE
        val base = if (lzma) lzma(src, headerBytes, baseLength, sectors) else inflate(src, headerBytes, baseLength, sectors)
        val subStart = headerBytes + baseLength
        val sub = inflate(src, subStart, src.size - subStart, frames * SUBCODE_SIZE)
        val out = ByteArray(hunkBytes)
        for (f in 0 until frames) {
            System.arraycopy(base, f * SECTOR_SIZE, out, f * FRAME_SIZE, SECTOR_SIZE)
            System.arraycopy(sub, f * SUBCODE_SIZE, out, f * FRAME_SIZE + SECTOR_SIZE, SUBCODE_SIZE)
            // The sync header and ECC were stripped from this sector: regenerate them.
            if (src[f / 8].toInt() and (1 shl (f % 8)) != 0) {
                System.arraycopy(SYNC, 0, out, f * FRAME_SIZE, SYNC.size)
                Ecc.generate(out, f * FRAME_SIZE)
            }
        }
        return out
    }

    /** cdfl: sector data as 16-bit big-endian stereo FLAC, then Deflate-compressed subcode. */
    private fun cdFlac(src: ByteArray): ByteArray {
        val frames = hunkBytes / FRAME_SIZE
        var blockSize = frames * SECTOR_SIZE / 4
        while (blockSize > SECTOR_SIZE) blockSize /= 2
        val (base, subStart) = Flac.decodeCdFrames(src, 0, src.size, frames * SECTOR_SIZE / 4, blockSize)
        val sub = inflate(src, subStart, src.size - subStart, frames * SUBCODE_SIZE)
        val out = ByteArray(hunkBytes)
        for (f in 0 until frames) {
            System.arraycopy(base, f * SECTOR_SIZE, out, f * FRAME_SIZE, SECTOR_SIZE)
            System.arraycopy(sub, f * SUBCODE_SIZE, out, f * FRAME_SIZE + SECTOR_SIZE, SUBCODE_SIZE)
        }
        return out
    }

    private fun lzma(src: ByteArray, offset: Int, length: Int, outSize: Int): ByteArray {
        // libchdr encodes with lc=3, lp=0, pb=2 and no end marker; the window never exceeds a hunk.
        val props = ((2 * 5 + 0) * 9 + 3).toByte()
        val dict = maxOf(4096, Integer.highestOneBit(maxOf(outSize, 1)) shl 1)
        val input = LZMAInputStream(ByteArrayInputStream(src, offset, length), outSize.toLong(), props, dict)
        val out = ByteArray(outSize)
        var n = 0
        while (n < outSize) {
            val r = input.read(out, n, outSize - n)
            if (r < 0) break
            n += r
        }
        if (n < outSize) throw ChdException("Short LZMA hunk")
        return out
    }

    private fun inflate(src: ByteArray, offset: Int, length: Int, outSize: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(src, offset, length)
            val out = ByteArray(outSize)
            var n = 0
            while (n < outSize && !inflater.finished()) {
                val r = inflater.inflate(out, n, outSize - n)
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
            if (n < outSize) throw ChdException("Short Deflate hunk")
            return out
        } finally {
            inflater.end()
        }
    }

    // ── Tracks ───────────────────────────────────────────────────────────────

    private fun readTracks(): List<Track> {
        val found = mutableListOf<Track>()
        var next = metaOffset
        var guard = 0
        while (next != 0L && guard++ < 1000) {
            val h = read(next, 16)
            val tag = be32(h, 0)
            val length = be32(h, 4) and 0xFFFFFF
            val following = be64(h, 8)
            if (tag == CHT2 || tag == CHTR) {
                val text = String(read(next + 16, length), Charsets.ISO_8859_1).trimEnd('\u0000')
                fun field(name: String) = Regex("""$name:(\S+)""").find(text)?.groupValues?.get(1)
                found += Track(
                    number = field("TRACK")?.toIntOrNull() ?: (found.size + 1),
                    type = field("TYPE") ?: "MODE1",
                    frames = field("FRAMES")?.toIntOrNull() ?: 0,
                    pregap = field("PREGAP")?.toIntOrNull() ?: 0,
                    pregapInData = field("PGTYPE")?.startsWith("V") == true,
                    startFrame = 0,
                )
            }
            next = following
        }
        // Tracks are stored one after another, each padded to a multiple of 4 frames.
        var frame = 0
        return found.sortedBy { it.number }.map { t ->
            val placed = t.copy(startFrame = frame)
            frame += t.frames + (4 - t.frames % 4) % 4
            placed
        }
    }

    /** Reads [size] bytes of a frame's sector (2352 bytes of it), by absolute frame number. */
    fun sector(frame: Int): ByteArray {
        val framesPerHunk = hunkBytes / unitBytes
        val hunk = hunk(frame / framesPerHunk)
        val at = (frame % framesPerHunk) * unitBytes
        return hunk.copyOfRange(at, at + minOf(SECTOR_SIZE, unitBytes))
    }

    // ── IO ───────────────────────────────────────────────────────────────────

    private fun read(position: Long, size: Int): ByteArray {
        val buf = ByteBuffer.allocate(size)
        channel.position(position)
        while (buf.hasRemaining()) if (channel.read(buf) < 0) throw ChdException("CHD file is truncated")
        return buf.array()
    }

    override fun close() = channel.close()

    companion object {
        const val SECTOR_SIZE = 2352
        const val SUBCODE_SIZE = 96
        const val FRAME_SIZE = SECTOR_SIZE + SUBCODE_SIZE

        private const val TYPE_0 = 0
        private const val TYPE_3 = 3
        private const val NONE = 4
        private const val SELF = 5
        private const val PARENT = 6
        private const val RLE_SMALL = 7
        private const val RLE_LARGE = 8
        private const val SELF_0 = 9
        private const val SELF_1 = 10
        private const val PARENT_SELF = 11
        private const val PARENT_0 = 12
        private const val PARENT_1 = 13

        private fun tag(s: String) = s.fold(0) { acc, c -> (acc shl 8) or c.code }
        private val CD_LZMA = tag("cdlz")
        private val CD_ZLIB = tag("cdzl")
        private val CD_FLAC = tag("cdfl")
        private val LZMA = tag("lzma")
        private val ZLIB = tag("zlib")
        private val CHT2 = tag("CHT2")
        private val CHTR = tag("CHTR")
        private val SYNC = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)

        private fun fourCc(v: Int) = String(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()), Charsets.ISO_8859_1)

        internal fun be32(b: ByteArray, i: Int) =
            ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

        private fun be48(b: ByteArray, i: Int): Long {
            var v = 0L
            for (k in 0 until 6) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
            return v
        }

        private fun be64(b: ByteArray, i: Int): Long = (be32(b, i).toLong() shl 32) or (be32(b, i + 4).toLong() and 0xFFFFFFFFL)

        /** CRC-16/CCITT-FALSE, as libchdr stores per hunk. */
        internal fun crc16(data: ByteArray): Int {
            var crc = 0xFFFF
            for (byte in data) {
                crc = crc xor ((byte.toInt() and 0xFF) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
                crc = crc and 0xFFFF
            }
            return crc
        }
    }
}

/**
 * CD-ROM sector ECC (Reed-Solomon P and Q parity), regenerated for sectors whose ECC `cdlz`
 * and `cdzl` stripped. Ported from libchdr's `cdrom.c`.
 */
internal object Ecc {
    private val fLut = IntArray(256)
    private val bLut = IntArray(256)

    init {
        for (i in 0 until 256) {
            val j = ((i shl 1) xor (if (i and 0x80 != 0) 0x11D else 0)) and 0xFF
            fLut[i] = j
            bLut[i xor j] = i
        }
    }

    /** Fills P (86×2 bytes at 0x81C) and Q (52×2 bytes at 0x8C8) parity for the sector at [base]. */
    fun generate(sector: ByteArray, base: Int) {
        val mode2 = sector[base + 15].toInt() == 2
        // Offsets count from the header, 12 bytes in; mode 2 hashes the header as zeros.
        fun source(offset: Int) = if (mode2 && offset < 4) 0 else sector[base + 12 + offset].toInt() and 0xFF
        block(sector, base, ::source, majorCount = 86, minorCount = 24, majorMult = 2, minorInc = 86, dest = 0x81C)
        block(sector, base, ::source, majorCount = 52, minorCount = 43, majorMult = 86, minorInc = 88, dest = 0x8C8)
    }

    private inline fun block(
        sector: ByteArray, base: Int, source: (Int) -> Int,
        majorCount: Int, minorCount: Int, majorMult: Int, minorInc: Int, dest: Int,
    ) {
        val size = majorCount * minorCount
        for (major in 0 until majorCount) {
            var index = (major shr 1) * majorMult + (major and 1)
            var a = 0
            var b = 0
            for (minor in 0 until minorCount) {
                val v = source(index)
                index += minorInc
                if (index >= size) index -= size
                a = a xor v
                b = b xor v
                a = fLut[a]
            }
            a = bLut[fLut[a] xor b]
            sector[base + dest + major] = a.toByte()
            sector[base + dest + major + majorCount] = (a xor b).toByte()
        }
    }
}
