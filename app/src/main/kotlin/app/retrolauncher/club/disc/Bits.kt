package app.retrolauncher.club.disc

/**
 * Reads bits most-significant first, as libchdr's `bitstream.c` does. Reading past the end
 * returns zeros (libchdr relies on this at the end of a compressed map).
 */
internal class BitReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    private var offset = start
    private var buffer = 0L
    private var bits = 0

    /** Bits read so far; [bytePosition] rounds up after [alignToByte]. */
    val bytePosition: Int get() = offset - bits / 8

    fun peek(n: Int): Int {
        if (n == 0) return 0
        while (bits < n) {
            val b = if (offset < end) data[offset].toInt() and 0xFF else 0
            offset++
            buffer = (buffer shl 8) or b.toLong()
            bits += 8
        }
        return ((buffer ushr (bits - n)) and ((1L shl n) - 1)).toInt()
    }

    fun remove(n: Int) {
        bits -= n
        buffer = buffer and ((1L shl bits) - 1)
    }

    fun read(n: Int): Int {
        if (n == 0) return 0
        if (n > 24) return (read(n - 16) shl 16) or read(16)
        val v = peek(n)
        remove(n)
        return v
    }

    /** A two's-complement value of [n] bits. */
    fun readSigned(n: Int): Int {
        if (n == 0) return 0
        val v = read(n)
        return if (n < 32 && v and (1 shl (n - 1)) != 0) v - (1 shl n) else v
    }

    /** Counts zero bits up to the next 1 bit, consuming both. */
    fun readUnary(): Int {
        var count = 0
        while (read(1) == 0) {
            count++
            if (count > 1 shl 20) throw ChdException("Corrupt unary code")
        }
        return count
    }

    fun alignToByte() = remove(bits % 8)

    val exhausted: Boolean get() = offset - bits / 8 >= end
}

/**
 * Canonical Huffman decoder, ported from libchdr's `huffman.c` (BSD-3-Clause): the RLE-encoded
 * tree import for the CHD v5 hunk map, and the Huffman-encoded one for the `huff` codec.
 */
internal class Huffman(private val numCodes: Int, private val maxBits: Int) {
    private val bitsOf = IntArray(numCodes)
    private val codeOf = IntArray(numCodes)
    private val lookup = IntArray(1 shl maxBits)

    fun importTreeRle(br: BitReader) {
        val numBits = when {
            maxBits >= 16 -> 5
            maxBits >= 8 -> 4
            else -> 3
        }
        var cur = 0
        while (cur < numCodes) {
            var nodeBits = br.read(numBits)
            if (nodeBits != 1) {
                bitsOf[cur++] = nodeBits
            } else {
                nodeBits = br.read(numBits)
                if (nodeBits == 1) {
                    bitsOf[cur++] = nodeBits
                } else {
                    var repeat = br.read(numBits) + 3
                    while (repeat-- > 0) {
                        if (cur >= numCodes) throw ChdException("Corrupt Huffman tree")
                        bitsOf[cur++] = nodeBits
                    }
                }
            }
        }
        assignCanonicalCodes()
        buildLookup()
    }

    /**
     * Reads a tree stored as code lengths that are themselves Huffman-coded with a small
     * 24-symbol tree (libchdr's `huffman_import_tree_huffman`), as the `huff` codec stores it.
     */
    fun importTreeHuffman(br: BitReader) {
        val small = Huffman(24, 6)
        small.bitsOf[0] = br.read(3)
        val start = br.read(3) + 1
        var count = 0
        for (index in 1 until 24) {
            if (index < start || count == 7) {
                small.bitsOf[index] = 0
            } else {
                count = br.read(3)
                small.bitsOf[index] = if (count == 7) 0 else count
            }
        }
        small.assignCanonicalCodes()
        small.buildLookup()

        var rleFullBits = 0
        var temp = numCodes - 9
        while (temp != 0) { temp = temp shr 1; rleFullBits++ }

        var cur = 0
        var last = 0
        while (cur < numCodes) {
            val value = small.decode(br)
            if (value != 0) {
                last = value - 1
                bitsOf[cur++] = last
            } else {
                var run = br.read(3) + 2
                if (run == 7 + 2) run += br.read(rleFullBits)
                while (run != 0 && cur < numCodes) { bitsOf[cur++] = last; run-- }
            }
        }
        assignCanonicalCodes()
        buildLookup()
    }

    private fun assignCanonicalCodes() {
        val histogram = IntArray(33)
        for (b in bitsOf) {
            if (b > maxBits) throw ChdException("Corrupt Huffman tree")
            histogram[b]++
        }
        var start = 0
        for (length in 32 downTo 1) {
            val next = (start + histogram[length]) shr 1
            histogram[length] = start
            start = next
        }
        for (i in 0 until numCodes) if (bitsOf[i] > 0) codeOf[i] = histogram[bitsOf[i]]++
    }

    private fun buildLookup() {
        for (i in 0 until numCodes) {
            val bits = bitsOf[i]
            if (bits == 0) continue
            val shift = maxBits - bits
            val first = codeOf[i] shl shift
            val last = ((codeOf[i] + 1) shl shift) - 1
            for (j in first..last) lookup[j] = (i shl 5) or bits
        }
    }

    fun decode(br: BitReader): Int {
        val entry = lookup[br.peek(maxBits)]
        br.remove(entry and 0x1F)
        return entry ushr 5
    }
}

class ChdException(message: String) : java.io.IOException(message)
