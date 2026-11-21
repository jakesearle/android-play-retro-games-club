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
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads a GameCube or Wii disc from an RVZ (or WIA) image, Dolphin's compressed formats, as if it were
 * the plain ISO, following Dolphin's format description (`docs/WiaAndRvz.md`) and reader
 * (`Source/Core/DiscIO/WIABlob.cpp`, `WIACompression.cpp`, `LaggedFibonacciGenerator.cpp`,
 * GPL-2.0-or-later; this is a reimplementation from the format, not a copy of that code).
 *
 * The disc is divided into raw-data regions, each stored as groups of `chunkSize` bytes that
 * are compressed separately (Zstandard, LZMA, LZMA2 or bzip2). In RVZ a group may also be
 * "packed": runs of literal bytes and runs of the discs' pseudorandom padding, stored as the
 * generator's seed.
 *
 * Wii partition data is stored decrypted and without its hashes, so reading it rebuilds each
 * 2 MiB group as on the disc: the H0-H2 SHA-1 tree (plus the stored hash exceptions), then
 * AES-128-CBC encryption with the partition's title key, which the image keeps.
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
    private val partitions: List<Partition>

    private data class Region(val offset: Long, val size: Long, val firstGroup: Int, val groupCount: Int)
    private data class Group(val fileOffset: Long, val size: Int, val compressed: Boolean, val packedSize: Int)
    private data class PartData(val firstSector: Long, val sectors: Long, val firstGroup: Int, val groupCount: Int)
    private class Partition(val key: ByteArray, val data: List<PartData>) {
        val firstSector = data[0].firstSector
        val totalSectors = if (data[1].sectors != 0L) data[1].firstSector - firstSector + data[1].sectors else data[0].sectors
    }

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
        if (discType != 1 && discType != 2) throw IOException("Unknown RVZ disc type $discType")
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

        val partCount = be32(disc, 0x90)
        val partEntrySize = be32(disc, 0x94)
        partitions = if (discType != 2 || partCount <= 0) emptyList() else {
            val table = readAt(be64(disc, 0x98), partCount * partEntrySize)
            List(partCount) { i ->
                val e = table.copyOfRange(i * partEntrySize, i * partEntrySize + partEntrySize).copyOf(maxOf(partEntrySize, 48))
                Partition(e.copyOf(16), List(2) { d ->
                    val at = 16 + d * 16
                    PartData(u32(e, at), u32(e, at + 4), be32(e, at + 8), be32(e, at + 12))
                })
            }
        }

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
                n = readPartition(at, out, written, out.size - written) ?: readRegions(at, out, written, out.size - written)
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
        val starts = regions.map { it.offset - it.offset % SECTOR } +
            partitions.flatMap { p -> p.data.filter { it.sectors > 0 }.map { it.firstSector * SECTOR } }
        val next = starts.filter { it > position }.minOrNull()
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

    // ── Wii partitions ───────────────────────────────────────────────────────

    /** Copies encrypted partition data at [position], or returns null if no partition holds it. */
    private fun readPartition(position: Long, out: ByteArray, outAt: Int, want: Int): Int? {
        for ((index, p) in partitions.withIndex()) {
            val d = p.data.firstOrNull { it.sectors > 0 && position >= it.firstSector * SECTOR && position < (it.firstSector + it.sectors) * SECTOR }
                ?: continue
            val sector = position / SECTOR - p.firstSector
            val group = sector / CLUSTERS_PER_GROUP
            val encrypted = encryptedGroup(index, p, group)
            val inGroup = (position - (p.firstSector + group * CLUSTERS_PER_GROUP) * SECTOR).toInt()
            val end = (d.firstSector + d.sectors) * SECTOR
            val n = minOf(want.toLong(), (encrypted.size - inGroup).toLong(), end - position).toInt()
            encrypted.copyInto(out, outAt, inGroup, inGroup + n)
            return n
        }
        return null
    }

    private var cachedEncrypted: Pair<Int, Long>? = null
    private var encrypted = ByteArray(0)

    /** One 2 MiB group of a partition as the disc stores it: hashed and encrypted. */
    private fun encryptedGroup(index: Int, p: Partition, group: Long): ByteArray {
        if (cachedEncrypted == index to group) return encrypted
        val first = group * CLUSTERS_PER_GROUP
        val clusters = minOf(CLUSTERS_PER_GROUP.toLong(), p.totalSectors - first).toInt()
        val data = ByteArray(CLUSTERS_PER_GROUP * DATA_SIZE) // missing clusters hash as zeroes
        val exceptions = ArrayList<Pair<Int, ByteArray>>() // (offset among the group's hashes, hash)
        val usedChunks = HashSet<Int>()
        val clustersPerChunk = (chunkSize / SECTOR).toInt()
        for (c in 0 until clusters) {
            val absolute = p.firstSector + first + c
            val d = p.data.firstOrNull { it.sectors > 0 && absolute >= it.firstSector && absolute < it.firstSector + it.sectors } ?: continue
            val inData = absolute - d.firstSector
            val chunkIndex = (inData / clustersPerChunk).toInt()
            val chunk = partitionChunk(d, chunkIndex)
            val from = (inData % clustersPerChunk).toInt() * DATA_SIZE
            if (from < chunk.data.size) chunk.data.copyInto(data, c * DATA_SIZE, from, minOf(chunk.data.size, from + DATA_SIZE))
            if (usedChunks.add(d.firstGroup + chunkIndex)) {
                val chunkFirst = d.firstSector - p.firstSector + chunkIndex.toLong() * clustersPerChunk
                chunk.exceptions.forEachIndexed { list, entries ->
                    val base = (chunkFirst + list * CLUSTERS_PER_GROUP - first) * HASH_SIZE
                    for ((offset, hash) in entries) {
                        val at = base + offset
                        if (at >= 0 && at + 20 <= CLUSTERS_PER_GROUP * HASH_SIZE) exceptions += at.toInt() to hash
                    }
                }
            }
        }

        val hashes = hashTree(data)
        for ((at, hash) in exceptions) hash.copyInto(hashes[at / HASH_SIZE], at % HASH_SIZE)

        val out = ByteArray(clusters * SECTOR.toInt())
        val key = SecretKeySpec(p.key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        for (c in 0 until clusters) {
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(ByteArray(16)))
            val h = cipher.doFinal(hashes[c])
            h.copyInto(out, c * SECTOR.toInt())
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(h.copyOfRange(0x3D0, 0x3E0)))
            cipher.doFinal(data, c * DATA_SIZE, DATA_SIZE, out, c * SECTOR.toInt() + HASH_SIZE)
        }
        cachedEncrypted = index to group
        encrypted = out
        return out
    }

    /** The H0, H1 and H2 hash blocks of a 2 MiB group's 64 clusters, as on a Wii disc. */
    private fun hashTree(data: ByteArray): Array<ByteArray> {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val blocks = Array(CLUSTERS_PER_GROUP) { ByteArray(HASH_SIZE) }
        for (c in 0 until CLUSTERS_PER_GROUP) for (j in 0 until 31) {
            sha1.update(data, c * DATA_SIZE + j * 0x400, 0x400)
            sha1.digest().copyInto(blocks[c], j * 20)
        }
        for (s in 0 until 8) {
            val h1 = ByteArray(0xA0)
            for (k in 0 until 8) {
                sha1.update(blocks[s * 8 + k], 0, 0x26C)
                sha1.digest().copyInto(h1, k * 20)
            }
            for (k in 0 until 8) h1.copyInto(blocks[s * 8 + k], 0x280)
        }
        val h2 = ByteArray(0xA0)
        for (s in 0 until 8) {
            sha1.update(blocks[s * 8], 0x280, 0xA0)
            sha1.digest().copyInto(h2, s * 20)
        }
        for (b in blocks) h2.copyInto(b, 0x340)
        return blocks
    }

    private class Chunk(val data: ByteArray, val exceptions: List<List<Pair<Int, ByteArray>>>)

    private var cachedChunkIndex = -1
    private var cachedChunk = Chunk(ByteArray(0), emptyList())

    /** A partition group's decrypted data (31 KiB per cluster) and its hash exception lists. */
    private fun partitionChunk(d: PartData, index: Int): Chunk {
        val groupIndex = d.firstGroup + index
        if (groupIndex == cachedChunkIndex) return cachedChunk
        val g = groups.getOrNull(groupIndex) ?: throw IOException("RVZ group $groupIndex is missing")
        val chunkData = chunkSize / SECTOR * DATA_SIZE
        val length = minOf(chunkData, d.sectors * DATA_SIZE - index * chunkData).toInt()
        val lists = maxOf(1L, chunkSize / GROUP_TOTAL_SIZE).toInt()
        val chunk = if (g.size == 0) Chunk(ByteArray(length), List(lists) { emptyList() }) else {
            val stored = readAt(g.fileOffset, g.size)
            val method = if (g.compressed) compression else NONE
            // Exception lists come first: compressed along with the data, or stored as is and
            // padded to four bytes when the group isn't compressed.
            val raw = if (method == NONE) stored else decompressAll(stored, method)
            var at = 0
            val exceptions = List(lists) {
                val count = ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)
                at += 2
                List(count) {
                    val offset = ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)
                    val hash = raw.copyOfRange(at + 2, at + 22)
                    at += 22
                    offset to hash
                }
            }
            if (method == NONE) at = (at + 3) and 3.inv()
            val rest = raw.copyOfRange(minOf(at, raw.size), raw.size)
            val data = if (g.packedSize != 0) unpack(rest, length, index * chunkData) else rest.copyOf(length)
            Chunk(data, exceptions)
        }
        cachedChunkIndex = groupIndex
        cachedChunk = chunk
        return chunk
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

    /** Decompresses everything in [data] (the size isn't known up front). */
    private fun decompressAll(data: ByteArray, method: Int): ByteArray {
        val input: InputStream = when (method) {
            ZSTD -> ZstdInputStream(ByteArrayInputStream(data))
            BZIP2 -> BZip2CompressorInputStream(ByteArrayInputStream(data))
            LZMA -> {
                val props = lzmaProps
                if (props.size < 5) throw IOException("Bad LZMA settings")
                val dict = (props[1].toInt() and 0xFF) or ((props[2].toInt() and 0xFF) shl 8) or
                    ((props[3].toInt() and 0xFF) shl 16) or ((props[4].toInt() and 0xFF) shl 24)
                LZMAInputStream(ByteArrayInputStream(data), -1L, props[0], dict)
            }
            LZMA2 -> {
                val p = lzmaProps.firstOrNull()?.toInt()?.and(0xFF) ?: throw IOException("Bad LZMA2 settings")
                val dict = if (p >= 40) -1 else (2 or (p and 1)) shl (p / 2 + 11)
                LZMA2InputStream(ByteArrayInputStream(data), dict)
            }
            else -> throw IOException("RVZ compression $method isn't supported")
        }
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        input.use { s ->
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } catch (_: java.io.EOFException) {
                // LZMA without an end marker stops at the end of its input.
            }
        }
        return out.toByteArray()
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
        private const val DATA_SIZE = 0x7C00
        private const val HASH_SIZE = 0x400
        private const val CLUSTERS_PER_GROUP = 64
        private const val GROUP_TOTAL_SIZE = 0x200000L
        private const val SECTOR = 0x8000L
        private const val NONE = 0
        private const val PURGE = 1
        private const val BZIP2 = 2
        private const val LZMA = 3
        private const val LZMA2 = 4
        private const val ZSTD = 5

        internal fun be32(b: ByteArray, i: Int) =
            ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        private fun u32(b: ByteArray, i: Int) = be32(b, i).toLong() and 0xFFFFFFFFL
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
