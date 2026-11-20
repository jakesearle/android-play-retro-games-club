package app.retrolauncher.club.disc

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.LZMA2InputStream
import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Reads a GameCube disc from an RVZ (or WIA) image, Dolphin's compressed formats, as if it were
 * the plain ISO, following Dolphin's format description (`docs/WiaAndRvz.md`) and reader
 * (`Source/Core/DiscIO/WIABlob.cpp`, `WIACompression.cpp`, `LaggedFibonacciGenerator.cpp`,
 * GPL-2.0-or-later; this is a reimplementation from the format, not a copy of that code).
 *
 * The disc is divided into raw-data regions, each stored as groups of `chunkSize` bytes that
 * are compressed separately (Zstandard, LZMA, LZMA2 or bzip2). In RVZ a group may also be
 * "packed": runs of literal bytes and runs of the discs' pseudorandom padding, stored as the
 * generator's seed. Only GameCube discs are read: Wii partitions are stored decrypted and
 * without hashes, which RetroAchievements' Wii hash doesn't need this reader for.
 */
class Rvz(private val channel: SeekableByteChannel) : Closeable {
    val isoSize: Long
    private val rvz: Boolean
    private val compression: Int
    private val chunkSize: Long
    private val discHeader: ByteArray
    private val lzmaProps: ByteArray
    private val regions: List<Region>
    private val groups: List<Group>

    private data class Region(val offset: Long, val size: Long, val firstGroup: Int, val groupCount: Int)
    private data class Group(val fileOffset: Long, val size: Int, val compressed: Boolean, val packedSize: Int)

    init {
        val head = readAt(0, 0x48)
        val magic = String(head, 0, 4, Charsets.ISO_8859_1)
        rvz = when (magic) {
            "RVZ\u0001" -> true
            "WIA\u0001" -> false
            else -> throw IOException("Not an RVZ or WIA file")
        }
        val discSize = be32(head, 12)
        isoSize = be64(head, 0x24)
        val disc = readAt(0x48, maxOf(discSize, 0xDC))
        val discType = be32(disc, 0)
        if (discType != 1) throw IOException("Only GameCube RVZ images are supported (disc type $discType)")
        compression = be32(disc, 4)
        chunkSize = be32(disc, 12).toLong() and 0xFFFFFFFFL
        discHeader = disc.copyOfRange(0x10, 0x90)
        val rawCount = be32(disc, 0xB4)
        val rawOffset = be64(disc, 0xB8)
        val rawSize = be32(disc, 0xC0)
        val groupCount = be32(disc, 0xC4)
        val groupOffset = be64(disc, 0xC8)
        val groupSize = be32(disc, 0xD0)
        lzmaProps = disc.copyOfRange(0xD5, 0xD5 + minOf(disc[0xD4].toInt() and 0xFF, 7))
        if (compression == PURGE) throw IOException("WIA PURGE compression isn't supported")

        val raw = decompress(readAt(rawOffset, rawSize), compression, rawCount * 24)
        regions = List(rawCount) { i ->
            Region(be64(raw, i * 24), be64(raw, i * 24 + 8), be32(raw, i * 24 + 16), be32(raw, i * 24 + 20))
        }
        val entrySize = if (rvz) 12 else 8
        val table = decompress(readAt(groupOffset, groupSize), compression, groupCount * entrySize)
        groups = List(groupCount) { i ->
            val at = i * entrySize
            val size = be32(table, at + 4)
            Group(
                fileOffset = (be32(table, at).toLong() and 0xFFFFFFFFL) shl 2,
                size = if (rvz) size and 0x7FFFFFFF else size,
                compressed = if (rvz) size < 0 else true,
                packedSize = if (rvz) be32(table, at + 8) else 0,
            )
        }
    }

    /** [size] bytes of the disc from [position], as the plain ISO has them. */
    fun read(position: Long, size: Int): ByteArray {
        if (position < 0 || size <= 0 || position >= isoSize) return ByteArray(0)
        val out = ByteArray(minOf(size.toLong(), isoSize - position).toInt())
        var at = position
        var written = 0
        while (written < out.size) {
            val n: Int
            if (at < HEADER_SIZE) {
                n = minOf(out.size - written, (HEADER_SIZE - at).toInt())
                discHeader.copyInto(out, written, at.toInt(), at.toInt() + n)
            } else {
                n = readRegions(at, out, written, out.size - written)
            }
            written += n
            at += n
        }
        return out
    }

    /** Copies data at [position] from the region holding it (zeroes outside every region); returns how many bytes. */
    private fun readRegions(position: Long, out: ByteArray, outAt: Int, want: Int): Int {
        for (r in regions) {
            // Each region's data starts at its offset rounded down to a 32 KiB sector.
            val skipped = r.offset % SECTOR
            val start = r.offset - skipped
            val end = r.offset + r.size
            if (position < start || position >= end) continue
            val index = ((position - start) / chunkSize).toInt()
            if (index >= r.groupCount) break
            val groupStart = start + index * chunkSize
            val groupLength = minOf(chunkSize, end - groupStart)
            val data = groupData(r.firstGroup + index, groupLength.toInt(), groupStart - start)
            val inGroup = (position - groupStart).toInt()
            val n = minOf(want.toLong(), groupLength - inGroup).toInt()
            data.copyInto(out, outAt, inGroup, inGroup + n)
            return n
        }
        // Not covered by any region: the next region's start, or the rest of the request, is zeroes.
        val next = regions.map { it.offset - it.offset % SECTOR }.filter { it > position }.minOrNull()
        val n = if (next == null) want else minOf(want.toLong(), next - position).toInt()
        out.fill(0, outAt, outAt + n)
        return n
    }

    private var cachedIndex = -1
    private var cached = ByteArray(0)

    /** A group's data, decompressed and unpacked ([length] bytes; [offsetInRegion] seeds the padding generator). */
    private fun groupData(index: Int, length: Int, offsetInRegion: Long): ByteArray {
        if (index == cachedIndex) return cached
        val g = groups.getOrNull(index) ?: throw IOException("RVZ group $index is missing")
        val data = when {
            g.size == 0 -> ByteArray(length)
            else -> {
                val stored = readAt(g.fileOffset, g.size)
                val method = if (g.compressed) compression else NONE
                if (g.packedSize != 0) unpack(decompress(stored, method, g.packedSize), length, offsetInRegion)
                else decompress(stored, method, length).copyOf(length)
            }
        }
        cachedIndex = index
        cached = data
        return data
    }

    /** Decodes RVZ packing: literal runs, and padding runs regenerated from a 68-byte seed. */
    private fun unpack(packed: ByteArray, length: Int, offsetInRegion: Long): ByteArray {
        val out = ByteArray(length)
        var inAt = 0
        var outAt = 0
        var dataOffset = offsetInRegion
        while (inAt + 4 <= packed.size && outAt < length) {
            val header = be32(packed, inAt)
            inAt += 4
            val size = minOf((header and 0x7FFFFFFF).toLong(), (length - outAt).toLong()).toInt()
            if (header < 0) {
                val lfg = PaddingGenerator(packed, inAt)
                inAt += PaddingGenerator.SEED_BYTES
                lfg.skip((dataOffset % SECTOR).toInt())
                lfg.bytes(out, outAt, size)
            } else {
                packed.copyInto(out, outAt, inAt, inAt + size)
                inAt += size
            }
            outAt += size
            dataOffset += size
        }
        return out
    }

    private fun decompress(data: ByteArray, method: Int, length: Int): ByteArray {
        if (method == NONE) return data
        val input: InputStream = when (method) {
            ZSTD -> ZstdInputStream(ByteArrayInputStream(data))
            BZIP2 -> BZip2CompressorInputStream(ByteArrayInputStream(data))
            LZMA -> {
                val props = lzmaProps
                if (props.size < 5) throw IOException("Bad LZMA settings")
                val dict = (props[1].toInt() and 0xFF) or ((props[2].toInt() and 0xFF) shl 8) or
                    ((props[3].toInt() and 0xFF) shl 16) or ((props[4].toInt() and 0xFF) shl 24)
                LZMAInputStream(ByteArrayInputStream(data), length.toLong(), props[0], dict)
            }
            LZMA2 -> {
                val p = lzmaProps.firstOrNull()?.toInt()?.and(0xFF) ?: throw IOException("Bad LZMA2 settings")
                val dict = if (p >= 40) -1 else (2 or (p and 1)) shl (p / 2 + 11)
                LZMA2InputStream(ByteArrayInputStream(data), dict)
            }
            else -> throw IOException("RVZ compression $method isn't supported")
        }
        return input.use { s ->
            val out = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = s.read(out, got, length - got)
                if (n < 0) break
                got += n
            }
            out
        }
    }

    private fun readAt(position: Long, size: Int): ByteArray {
        val buf = ByteBuffer.allocate(size)
        channel.position(position)
        while (buf.hasRemaining()) if (channel.read(buf) < 0) throw IOException("RVZ file is truncated")
        return buf.array()
    }

    override fun close() = channel.close()

    companion object {
        private const val HEADER_SIZE = 0x80L
        private const val SECTOR = 0x8000L
        private const val NONE = 0
        private const val PURGE = 1
        private const val BZIP2 = 2
        private const val LZMA = 3
        private const val LZMA2 = 4
        private const val ZSTD = 5

        internal fun be32(b: ByteArray, i: Int) =
            ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        private fun be64(b: ByteArray, i: Int) = (be32(b, i).toLong() shl 32) or (be32(b, i + 4).toLong() and 0xFFFFFFFFL)
    }
}

/**
 * The pseudorandom padding on GameCube and Wii discs: a lagged Fibonacci generator (xor,
 * j = 32, k = 521) started from a 17-word seed, as Dolphin's format description gives it.
 * Words are kept already transformed for output (bits 16-23 come from a shift by 18), so the
 * output is each word's bytes, big-endian.
 */
internal class PaddingGenerator(seed: ByteArray, at: Int) {
    private val buffer = IntArray(K)
    private var position = 0 // bytes into the buffer

    init {
        for (i in 0 until SEED_WORDS) buffer[i] = Rvz.be32(seed, at + i * 4)
        for (i in SEED_WORDS until K) buffer[i] = (buffer[i - 17] shl 23) xor (buffer[i - 16] ushr 9) xor buffer[i - 1]
        for (i in 0 until K) buffer[i] = (buffer[i] and 0xFF00FFFF.toInt()) or ((buffer[i] ushr 2) and 0x00FF0000)
        repeat(4) { forward() }
    }

    private fun forward() {
        for (i in 0 until J) buffer[i] = buffer[i] xor buffer[i + K - J]
        for (i in J until K) buffer[i] = buffer[i] xor buffer[i - J]
    }

    fun skip(count: Int) {
        position += count
        while (position >= K * 4) { forward(); position -= K * 4 }
    }

    fun bytes(out: ByteArray, at: Int, count: Int) {
        for (i in 0 until count) {
            val word = buffer[position / 4]
            out[at + i] = (word ushr (24 - 8 * (position % 4))).toByte()
            if (++position == K * 4) { forward(); position = 0 }
        }
    }

    companion object {
        const val SEED_WORDS = 17
        const val SEED_BYTES = SEED_WORDS * 4
        private const val J = 32
        private const val K = 521
    }
}
