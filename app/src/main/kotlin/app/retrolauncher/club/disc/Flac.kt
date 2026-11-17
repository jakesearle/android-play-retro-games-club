package app.retrolauncher.club.disc

/**
 * A minimal FLAC frame decoder for CHD's `cdfl` codec, which stores CD sectors as 16-bit
 * stereo FLAC frames with no stream header (libchdr feeds them to dr_flac with a synthetic
 * STREAMINFO). Written from the FLAC format specification.
 */
internal object Flac {
    /**
     * Decodes frames from [src] until [samplesPerChannel] stereo samples are produced, and
     * writes them big-endian and interleaved (L, R), as libchdr's cdfl codec does. Also
     * returns the offset just past the last frame, where CHD's subcode data starts.
     */
    fun decodeCdFrames(src: ByteArray, start: Int, end: Int, samplesPerChannel: Int, defaultBlockSize: Int): Pair<ByteArray, Int> {
        val out = ByteArray(samplesPerChannel * 4)
        val br = BitReader(src, start, end)
        var produced = 0
        while (produced < samplesPerChannel) {
            val (left, right) = decodeFrame(br, defaultBlockSize)
            val n = minOf(left.size, samplesPerChannel - produced)
            for (i in 0 until n) {
                val o = (produced + i) * 4
                val l = left[i]
                val r = right[i]
                out[o] = (l shr 8).toByte(); out[o + 1] = l.toByte()
                out[o + 2] = (r shr 8).toByte(); out[o + 3] = r.toByte()
            }
            produced += n
        }
        return out to br.bytePosition
    }

    private fun decodeFrame(br: BitReader, defaultBlockSize: Int): Pair<IntArray, IntArray> {
        if (br.read(14) != 0x3FFE) throw ChdException("FLAC frame sync not found")
        br.read(1) // reserved
        br.read(1) // blocking strategy
        val blockSizeCode = br.read(4)
        val sampleRateCode = br.read(4)
        val channelAssignment = br.read(4)
        val sampleSizeCode = br.read(3)
        br.read(1) // reserved
        skipUtf8Number(br)
        val blockSize = when (blockSizeCode) {
            0 -> defaultBlockSize
            1 -> 192
            in 2..5 -> 576 shl (blockSizeCode - 2)
            6 -> br.read(8) + 1
            7 -> br.read(16) + 1
            else -> 256 shl (blockSizeCode - 8)
        }
        when (sampleRateCode) {
            12 -> br.read(8)
            13, 14 -> br.read(16)
        }
        br.read(8) // header CRC-8
        val bps = when (sampleSizeCode) {
            0, 4 -> 16
            1 -> 8
            2 -> 12
            5 -> 20
            6 -> 24
            7 -> 32
            else -> throw ChdException("Bad FLAC sample size")
        }
        if (channelAssignment > 10 || (channelAssignment < 8 && channelAssignment != 1)) {
            throw ChdException("CHD FLAC must be stereo")
        }
        // The side channel needs one extra bit.
        val a = decodeSubframe(br, blockSize, bps + if (channelAssignment == 9) 1 else 0)
        val b = decodeSubframe(br, blockSize, bps + if (channelAssignment == 8 || channelAssignment == 10) 1 else 0)
        when (channelAssignment) {
            8 -> for (i in 0 until blockSize) b[i] = a[i] - b[i] // left, side → right
            9 -> for (i in 0 until blockSize) a[i] += b[i] // side, right → left
            10 -> for (i in 0 until blockSize) { // mid, side
                val side = b[i]
                val mid = (a[i] shl 1) or (side and 1)
                a[i] = (mid + side) shr 1
                b[i] = (mid - side) shr 1
            }
        }
        br.alignToByte()
        br.read(16) // frame CRC-16
        return a to b
    }

    private fun skipUtf8Number(br: BitReader) {
        val first = br.read(8)
        var extra = 0
        var mask = 0x80
        while (first and mask != 0) { extra++; mask = mask shr 1 }
        repeat(maxOf(0, extra - 1)) { br.read(8) }
    }

    private fun decodeSubframe(br: BitReader, blockSize: Int, bpsIn: Int): IntArray {
        if (br.read(1) != 0) throw ChdException("Bad FLAC subframe")
        val type = br.read(6)
        var wasted = 0
        if (br.read(1) == 1) wasted = br.readUnary() + 1
        val bps = bpsIn - wasted
        val s = IntArray(blockSize)
        when {
            type == 0 -> s.fill(br.readSigned(bps))
            type == 1 -> for (i in 0 until blockSize) s[i] = br.readSigned(bps)
            type in 8..12 -> {
                val order = type - 8
                for (i in 0 until order) s[i] = br.readSigned(bps)
                residual(br, s, blockSize, order)
                for (i in order until blockSize) s[i] += when (order) {
                    0 -> 0
                    1 -> s[i - 1]
                    2 -> 2 * s[i - 1] - s[i - 2]
                    3 -> 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3]
                    else -> 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4]
                }
            }
            type in 32..63 -> {
                val order = type - 31
                for (i in 0 until order) s[i] = br.readSigned(bps)
                val precision = br.read(4) + 1
                if (precision == 16) throw ChdException("Bad FLAC LPC precision")
                val shift = br.readSigned(5)
                val coefs = IntArray(order) { br.readSigned(precision) }
                residual(br, s, blockSize, order)
                for (i in order until blockSize) {
                    var sum = 0L
                    for (j in 0 until order) sum += coefs[j].toLong() * s[i - 1 - j]
                    s[i] += (sum shr shift).toInt()
                }
            }
            else -> throw ChdException("Bad FLAC subframe type $type")
        }
        if (wasted > 0) for (i in 0 until blockSize) s[i] = s[i] shl wasted
        return s
    }

    /** Rice-coded residuals, added into [s] from [order] on (prediction is added after). */
    private fun residual(br: BitReader, s: IntArray, blockSize: Int, order: Int) {
        val method = br.read(2)
        if (method > 1) throw ChdException("Bad FLAC residual coding")
        val paramBits = if (method == 0) 4 else 5
        val escape = if (method == 0) 15 else 31
        val partitionOrder = br.read(4)
        val partitions = 1 shl partitionOrder
        var i = order
        for (p in 0 until partitions) {
            val count = (blockSize shr partitionOrder) - if (p == 0) order else 0
            val param = br.read(paramBits)
            if (param == escape) {
                val raw = br.read(5)
                repeat(count) { s[i++] = br.readSigned(raw) }
            } else {
                repeat(count) {
                    val q = br.readUnary()
                    val v = (q shl param) or br.read(param)
                    s[i++] = (v ushr 1) xor -(v and 1)
                }
            }
        }
    }
}
