package app.retrolauncher.club.disc

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.util.zip.Inflater

/**
 * Reads a GameCube disc from a GCZ image (Dolphin's older compressed format) as if it were the
 * plain ISO. Layout, from Dolphin's `CompressedBlob.h`: a 32-byte little-endian header (magic
 * 0xB10BC001, sub-type, compressed data size, disc size, block size, block count), a table of
 * block offsets whose top bit marks a block stored uncompressed, a table of Adler-32 checksums,
 * then the blocks, each zlib-compressed on its own.
 */
class Gcz(private val channel: SeekableByteChannel) : Closeable {
    val discSize: Long
    private val blockSize: Int
    private val compressedSize: Long
    private val pointers: LongArray
    private val dataOffset: Long
    private var cachedBlock = -1
    private var cached = ByteArray(0)

    init {
        val h = readAt(0, 32)
        if (h.getInt(0) != MAGIC) throw IOException("Not a GCZ file")
        compressedSize = h.getLong(8)
        discSize = h.getLong(16)
        blockSize = h.getInt(24)
        val blocks = h.getInt(28)
        if (blocks <= 0 || blockSize <= 0) throw IOException("Bad GCZ header")
        val table = readAt(32, blocks * 8)
        pointers = LongArray(blocks) { table.getLong(it * 8) }
        dataOffset = 32L + blocks * 12L
    }

    /** [size] bytes of the disc from [position], as the plain ISO has them (shorter at the end). */
    fun read(position: Long, size: Int): ByteArray {
        if (position < 0 || size <= 0 || position >= discSize) return ByteArray(0)
        val out = ByteArray(minOf(size.toLong(), discSize - position).toInt())
        var written = 0
        while (written < out.size) {
            val at = position + written
            val index = (at / blockSize).toInt()
            val block = block(index)
            val inBlock = (at % blockSize).toInt()
            val n = minOf(out.size - written, block.size - inBlock)
            if (n <= 0) break
            block.copyInto(out, written, inBlock, inBlock + n)
            written += n
        }
        return out
    }

    private fun block(index: Int): ByteArray {
        if (index == cachedBlock) return cached
        if (index !in pointers.indices) throw IOException("GCZ block $index is missing")
        val start = pointers[index] and OFFSET_MASK
        val end = if (index + 1 < pointers.size) pointers[index + 1] and OFFSET_MASK else compressedSize
        val stored = readAt(dataOffset + start, (end - start).toInt()).array()
        val data = if (pointers[index] and STORED != 0L) {
            stored.copyOf(blockSize)
        } else {
            val inflater = Inflater()
            try {
                inflater.setInput(stored)
                val out = ByteArray(blockSize)
                var got = 0
                while (got < blockSize && !inflater.finished()) {
                    val n = inflater.inflate(out, got, blockSize - got)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    got += n
                }
                out
            } finally {
                inflater.end()
            }
        }
        cachedBlock = index
        cached = data
        return data
    }

    private fun readAt(position: Long, size: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(position)
        while (buf.hasRemaining()) if (channel.read(buf) < 0) throw IOException("GCZ file is truncated")
        return buf.flip() as ByteBuffer
    }

    override fun close() = channel.close()

    private companion object {
        const val MAGIC = 0xB10BC001.toInt()
        const val STORED = Long.MIN_VALUE // 1 << 63
        const val OFFSET_MASK = Long.MAX_VALUE
    }
}
