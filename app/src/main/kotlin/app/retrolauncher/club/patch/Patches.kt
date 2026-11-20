package app.retrolauncher.club.patch

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.CRC32

/** The patch formats RAPatches uses, recognised by file extension. */
enum class PatchFormat(vararg val extensions: String) {
    IPS("ips"), BPS("bps"), VCDIFF("xdelta", "vcdiff");

    companion object {
        fun of(fileName: String): PatchFormat? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/** The patch doesn't fit this base ROM (BPS checks the base's CRC-32), or is damaged. */
class PatchException(message: String) : IOException(message)

/**
 * Applies a patch to a base ROM, writing the patched ROM to a file. The base is read through a
 * [ByteBuffer] (a memory-mapped file, so large ROMs don't need to fit in memory) and the result
 * is written through a memory-mapped or random-access file for the same reason.
 */
object Patcher {
    fun apply(format: PatchFormat, patch: ByteArray, source: ByteBuffer, out: File) = when (format) {
        PatchFormat.IPS -> Ips.apply(patch, source, out)
        PatchFormat.BPS -> Bps.apply(patch, source, out)
        PatchFormat.VCDIFF -> Vcdiff.apply(patch, source, out)
    }

    /** For BPS: the CRC-32 the base ROM must have, so candidates can be ruled out before patching. */
    fun requiredSourceCrc(format: PatchFormat, patch: ByteArray): Long? =
        if (format == PatchFormat.BPS && patch.size >= 12) le32(patch, patch.size - 12) else null

    fun crc32(buffer: ByteBuffer): Long = CRC32().apply { update(buffer.duplicate().apply { position(0) }) }.value

    internal fun le32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24))

    /** A read-write mapping of [file], [size] bytes long. */
    internal fun mapOutput(file: File, size: Long): Pair<RandomAccessFile, java.nio.MappedByteBuffer> {
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(size)
        return raf to raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
    }
}

/**
 * IPS: "PATCH", then records (3-byte offset, 2-byte length, data; a length of 0 means a run:
 * 2-byte count and one byte), then "EOF", optionally followed by a 3-byte size to truncate to.
 * Records may write past the end of the base, which extends the ROM.
 */
internal object Ips {
    fun apply(patch: ByteArray, source: ByteBuffer, out: File) {
        if (patch.size < 8 || String(patch, 0, 5, Charsets.ISO_8859_1) != "PATCH") throw PatchException("Not an IPS patch")
        // First pass: how big the result is.
        var size = source.capacity().toLong()
        var at = 5
        var truncate = -1L
        while (true) {
            if (at + 3 > patch.size) throw PatchException("IPS patch ends early")
            if (String(patch, at, 3, Charsets.ISO_8859_1) == "EOF") {
                if (at + 6 <= patch.size) truncate = be(patch, at + 3, 3)
                break
            }
            val offset = be(patch, at, 3)
            val length = be(patch, at + 3, 2).toInt()
            at += 5
            val n = if (length == 0) be(patch, at, 2).toInt().also { at += 3 } else length.also { at += length }
            size = maxOf(size, offset + n)
        }
        if (truncate >= 0) size = truncate
        val (raf, target) = Patcher.mapOutput(out, size)
        raf.use {
            val base = source.duplicate().apply { position(0); limit(minOf(source.capacity().toLong(), size).toInt()) }
            target.put(base)
            at = 5
            while (String(patch, at, 3, Charsets.ISO_8859_1) != "EOF") {
                val offset = be(patch, at, 3).toInt()
                val length = be(patch, at + 3, 2).toInt()
                at += 5
                if (length == 0) {
                    val count = be(patch, at, 2).toInt()
                    val value = patch[at + 2]
                    for (i in 0 until count) if (offset + i < size) target.put(offset + i, value)
                    at += 3
                } else {
                    for (i in 0 until length) if (offset + i < size) target.put(offset + i, patch[at + i])
                    at += length
                }
            }
            target.force()
        }
    }

    private fun be(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}

/**
 * BPS (by byuu): "BPS1", the source, target and metadata sizes, the metadata, then actions
 * (source read, target read, source copy, target copy), and a footer of three CRC-32s: the
 * source's, the target's and the patch's. The source CRC rules out the wrong base ROM.
 */
internal object Bps {
    private const val SOURCE_READ = 0
    private const val TARGET_READ = 1
    private const val SOURCE_COPY = 2
    private const val TARGET_COPY = 3

    fun apply(patch: ByteArray, source: ByteBuffer, out: File) {
        if (patch.size < 16 || String(patch, 0, 4, Charsets.ISO_8859_1) != "BPS1") throw PatchException("Not a BPS patch")
        val end = patch.size - 12
        var at = 4
        fun number(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                if (at >= end) throw PatchException("BPS patch ends early")
                val x = patch[at++].toInt() and 0xFF
                data += (x and 0x7F) * shift
                if (x and 0x80 != 0) break
                shift = shift shl 7
                data += shift
            }
            return data
        }
        val sourceSize = number()
        val targetSize = number()
        // Read the length first: number() moves [at], so `at += number()` would add to the old position.
        val metadataLength = number().toInt()
        at += metadataLength
        if (sourceSize != source.capacity().toLong()) throw PatchException("This base ROM is the wrong size for the patch")
        if (Patcher.crc32(source) != Patcher.le32(patch, end)) throw PatchException("This base ROM isn't the one the patch was made for")

        val (raf, target) = Patcher.mapOutput(out, targetSize)
        raf.use {
            var output = 0L
            var sourceOffset = 0L
            var targetOffset = 0L
            while (at < end) {
                val data = number()
                val command = (data and 3).toInt()
                var length = (data shr 2) + 1
                if (output + length > targetSize) throw PatchException("BPS patch writes past the end")
                when (command) {
                    SOURCE_READ -> while (length-- > 0) { target.put(output.toInt(), source.get(output.toInt())); output++ }
                    TARGET_READ -> while (length-- > 0) { target.put(output.toInt(), patch[at++]); output++ }
                    SOURCE_COPY -> {
                        val d = number()
                        sourceOffset += (if (d and 1L != 0L) -1 else 1) * (d shr 1)
                        while (length-- > 0) target.put((output++).toInt(), source.get((sourceOffset++).toInt()))
                    }
                    TARGET_COPY -> {
                        val d = number()
                        targetOffset += (if (d and 1L != 0L) -1 else 1) * (d shr 1)
                        // Byte by byte: the copy may overlap what it's writing (a repeating run).
                        while (length-- > 0) target.put((output++).toInt(), target.get((targetOffset++).toInt()))
                    }
                }
            }
            target.force()
            if (Patcher.crc32(target) != Patcher.le32(patch, end + 4)) throw PatchException("The patched ROM's checksum is wrong")
        }
    }
}

/**
 * VCDIFF (RFC 3284), as xdelta3 writes it: windows of ADD, RUN and COPY instructions over the
 * base ROM ("source") and the output so far, using the RFC's default code table and address
 * cache. xdelta3's optional per-window Adler-32 is skipped over; secondary compression and
 * custom code tables aren't supported (the patches RAPatches carries don't use them).
 */
internal object Vcdiff {
    private const val NOOP = 0
    private const val ADD = 1
    private const val RUN = 2
    private const val COPY = 3
    private const val NEAR = 4
    private const val SAME = 3

    /** The default code table: (type, size, mode) for each of the two instructions, per code. */
    private val TABLE: Array<IntArray> = buildTable()

    private fun buildTable(): Array<IntArray> {
        val t = ArrayList<IntArray>(256)
        t += intArrayOf(RUN, 0, 0, NOOP, 0, 0)
        for (size in 0..17) t += intArrayOf(ADD, size, 0, NOOP, 0, 0)
        for (mode in 0..8) {
            t += intArrayOf(COPY, 0, mode, NOOP, 0, 0)
            for (size in 4..18) t += intArrayOf(COPY, size, mode, NOOP, 0, 0)
        }
        for (mode in 0..5) for (add in 1..4) for (copy in 4..6) t += intArrayOf(ADD, add, 0, COPY, copy, mode)
        for (mode in 6..8) for (add in 1..4) t += intArrayOf(ADD, add, 0, COPY, 4, mode)
        for (mode in 0..8) t += intArrayOf(COPY, 4, mode, ADD, 1, 0)
        check(t.size == 256)
        return t.toTypedArray()
    }

    private class Reader(val b: ByteArray, var at: Int, val end: Int = b.size) {
        fun byte(): Int { if (at >= end) throw PatchException("xdelta patch ends early"); return b[at++].toInt() and 0xFF }
        fun number(): Long {
            var v = 0L
            while (true) {
                val x = byte()
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x and 0x80 == 0) return v
            }
        }
    }

    fun apply(patch: ByteArray, source: ByteBuffer, out: File) {
        if (patch.size < 5 || (patch[0].toInt() and 0xFF) != 0xD6 || (patch[1].toInt() and 0xFF) != 0xC3 || (patch[2].toInt() and 0xFF) != 0xC4) {
            throw PatchException("Not an xdelta patch")
        }
        val r = Reader(patch, 4)
        val header = r.byte()
        if (header and 0x01 != 0) throw PatchException("xdelta secondary compression isn't supported")
        if (header and 0x02 != 0) throw PatchException("Custom xdelta code tables aren't supported")
        if (header and 0x04 != 0) { val noteLength = r.number().toInt(); r.at += noteLength } // application header (a note)

        RandomAccessFile(out, "rw").use { target ->
            target.setLength(0)
            var targetLength = 0L
            while (r.at < patch.size) {
                val indicator = r.byte()
                var segmentLength = 0L
                var segmentPosition = 0L
                val fromSource = indicator and 0x01 != 0
                val fromTarget = indicator and 0x02 != 0
                if (fromSource || fromTarget) {
                    segmentLength = r.number()
                    segmentPosition = r.number()
                }
                r.number() // length of the delta encoding
                val windowSize = r.number().toInt()
                if (r.byte() != 0) throw PatchException("xdelta secondary compression isn't supported")
                val dataLength = r.number().toInt()
                val instLength = r.number().toInt()
                val addrLength = r.number().toInt()
                if (indicator and 0x04 != 0) r.at += 4 // xdelta3's Adler-32 of the window (a constant, so fine)
                val data = Reader(patch, r.at, r.at + dataLength)
                val inst = Reader(patch, data.end, data.end + instLength)
                val addr = Reader(patch, inst.end, inst.end + addrLength)
                r.at = addr.end

                // The segment copied from: the base ROM, or output already written.
                val segment: (Long) -> Byte = when {
                    fromSource -> { a -> source.get((segmentPosition + a).toInt()) }
                    fromTarget -> {
                        val bytes = ByteArray(segmentLength.toInt())
                        target.seek(segmentPosition); target.readFully(bytes)
                        val f: (Long) -> Byte = { a -> bytes[a.toInt()] }
                        f
                    }
                    else -> { _ -> throw PatchException("xdelta copy with no source") }
                }
                val window = ByteArray(windowSize)
                var here = 0
                val near = LongArray(NEAR)
                var nextNear = 0
                val same = LongArray(SAME * 256)

                fun copyAddress(mode: Int): Long {
                    val position = segmentLength + here
                    val a = when {
                        mode == 0 -> addr.number()
                        mode == 1 -> position - addr.number()
                        mode < 2 + NEAR -> near[mode - 2] + addr.number()
                        else -> same[(mode - 2 - NEAR) * 256 + addr.byte()]
                    }
                    near[nextNear] = a
                    nextNear = (nextNear + 1) % NEAR
                    same[(a % (SAME * 256)).toInt()] = a
                    return a
                }

                fun run(type: Int, sizeIn: Int, mode: Int) {
                    if (type == NOOP) return
                    val size = if (sizeIn == 0) inst.number().toInt() else sizeIn
                    if (here + size > windowSize) throw PatchException("xdelta window overflows")
                    when (type) {
                        ADD -> repeat(size) { window[here++] = data.byte().toByte() }
                        RUN -> { val v = data.byte().toByte(); repeat(size) { window[here++] = v } }
                        COPY -> {
                            var a = copyAddress(mode)
                            repeat(size) {
                                window[here++] = if (a < segmentLength) segment(a) else window[(a - segmentLength).toInt()]
                                a++
                            }
                        }
                    }
                }

                while (inst.at < inst.end) {
                    val code = TABLE[inst.byte()]
                    run(code[0], code[1], code[2])
                    run(code[3], code[4], code[5])
                }
                if (here != windowSize) throw PatchException("xdelta window is short")
                target.seek(targetLength)
                target.write(window)
                targetLength += windowSize
            }
        }
    }
}
