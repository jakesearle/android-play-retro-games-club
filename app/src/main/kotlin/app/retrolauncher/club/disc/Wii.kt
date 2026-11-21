package app.retrolauncher.club.disc

import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest

/**
 * RetroAchievements' Wii disc hash, ported from rcheevos (`rc_hash_wii_disc` in
 * `src/hash/rc_hash_disc.c`, MIT): the disc header, the region code, and for each partition
 * except the update one, its title metadata and the first 1024 encrypted clusters (32 MiB),
 * hashed as stored on the disc. Reads a plain image (.iso) or an RVZ/WIA through [Rvz], which
 * re-encrypts the partition data as the original disc has it.
 */
object Wii {
    private const val MAIN_HEADER_SIZE = 0x80
    private const val REGION_CODE_ADDRESS = 0x4E000L
    private const val CLUSTER_SIZE = 0x7C00
    private const val MAX_CLUSTER_COUNT = 1024
    private const val MAGIC = 0x5D1C9EA3

    fun hash(channel: SeekableByteChannel): String? = hash { position, size -> GameCube.read(channel, position, size) }

    fun hash(rvz: Rvz): String? = hash(rvz::read)

    /** The hash of a disc read through [read] (position, size → bytes, shorter at the end). */
    fun hash(read: (Long, Int) -> ByteArray): String? {
        val magic = read(0x18, 4)
        if (magic.size < 4 || GameCube.u32(magic, 0) != MAGIC.toLong()) return null
        // A zero at 0x61 means the partitions are encrypted, as on a retail disc.
        val encrypted = read(0x61, 1).firstOrNull() == 0.toByte()
        val md = MessageDigest.getInstance("MD5")
        md.update(exactly(read, 0, MAIN_HEADER_SIZE))
        md.update(exactly(read, REGION_CODE_ADDRESS, 4))

        val info = exactly(read, 0x40000, 32)
        val counts = IntArray(4) { GameCube.u32(info, it * 8).toInt() }
        val total = counts.sumOf { it.toLong() }
        if (total == 0L || total > 16) return null
        val partitions = ArrayList<Pair<Long, Long>>() // (offset, type)
        for (t in 0 until 4) {
            val table = exactly(read, GameCube.u32(info, t * 8 + 4) shl 2, counts[t] * 8)
            for (i in 0 until counts[t]) partitions += (GameCube.u32(table, i * 8) shl 2) to GameCube.u32(table, i * 8 + 4)
        }

        for ((offset, type) in partitions) {
            if (type == 1L) continue // the update partition
            val tmdInfo = exactly(read, offset + 0x2A4, 8)
            val tmdSize = minOf(GameCube.u32(tmdInfo, 0), CLUSTER_SIZE.toLong()).toInt()
            val tmdOffset = GameCube.u32(tmdInfo, 4) shl 2
            md.update(exactly(read, offset + tmdOffset, tmdSize))

            val dataInfo = exactly(read, offset + 0x2B8, 8)
            val dataOffset = GameCube.u32(dataInfo, 0) shl 2
            val dataSize = GameCube.u32(dataInfo, 4) shl 2
            if (encrypted) {
                val clusters = minOf(dataSize / 0x8000, MAX_CLUSTER_COUNT.toLong()).toInt()
                for (c in 0 until clusters) md.update(exactly(read, dataOffset + c * 0x8000L + 0x400, CLUSTER_SIZE))
            } else if (!GameCube.partition(md, read, dataOffset and 0xFFFFFFFFL, 2)) {
                return null
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** [size] bytes from [position], zero-filled past the end of the image. */
    private fun exactly(read: (Long, Int) -> ByteArray, position: Long, size: Int): ByteArray {
        val got = read(position, size)
        return if (got.size == size) got else got.copyOf(size)
    }
}
