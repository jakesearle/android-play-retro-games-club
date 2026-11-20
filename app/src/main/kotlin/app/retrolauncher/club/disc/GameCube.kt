package app.retrolauncher.club.disc

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest

/**
 * RetroAchievements' GameCube hash, ported from rcheevos (`rc_hash_gamecube` and
 * `rc_hash_nintendo_disc_partition` in `src/rhash/hash_disc.c`, MIT): the disc header and
 * apploader, then the boot program's (main.dol's) 18 code and data segments. Reads a plain
 * disc image (.iso, .gcm), or an RVZ/WIA image through [Rvz]; GCZ can't be read yet.
 */
object GameCube {
    private const val BASE_HEADER_SIZE = 0x2440
    private const val MAX_HEADER_SIZE = 1024 * 1024
    private const val MAGIC = 0xC2339F3D.toInt()

    fun hash(channel: SeekableByteChannel): String? = hash { position, size -> read(channel, position, size) }

    fun hash(rvz: Rvz): String? = hash(rvz::read)

    /** The hash of a disc read through [read] (position, size → bytes, shorter at the end). */
    fun hash(read: (Long, Int) -> ByteArray): String? {
        if (be32(read(0x1C, 4), 0) != MAGIC) return null
        val md = MessageDigest.getInstance("MD5")
        val sizes = read((BASE_HEADER_SIZE + 0x14).toLong(), 8)
        if (sizes.size < 8) return null
        val headerSize = minOf(BASE_HEADER_SIZE.toLong() + 0x20 + u32(sizes, 0) + u32(sizes, 4), MAX_HEADER_SIZE.toLong()).toInt()
        val header = read(0, headerSize)
        if (header.size < 0x424) return null
        md.update(header)

        val dolOffset = u32(header, 0x420)
        val table = read(dolOffset, 0xD8)
        if (table.size < 0xD8) return null
        for (i in 0 until 18) {
            val offset = u32(table, i * 4)
            var remaining = u32(table, 0x90 + i * 4)
            if (remaining == 0L) continue
            var at = offset
            while (remaining > 0) {
                val chunk = read(at, minOf(remaining, CHUNK.toLong()).toInt())
                if (chunk.isEmpty()) break
                md.update(chunk)
                at += chunk.size
                remaining -= chunk.size
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private const val CHUNK = 1024 * 1024

    private fun read(channel: SeekableByteChannel, position: Long, size: Int): ByteArray {
        if (size <= 0 || position < 0 || position >= channel.size()) return ByteArray(0)
        val buf = ByteBuffer.allocate(minOf(size.toLong(), channel.size() - position).toInt())
        channel.position(position)
        while (buf.hasRemaining()) if (channel.read(buf) < 0) break
        return buf.array().copyOf(buf.position())
    }

    private fun be32(b: ByteArray, i: Int) = if (b.size < i + 4) 0 else
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
    private fun u32(b: ByteArray, i: Int) = be32(b, i).toLong() and 0xFFFFFFFFL
}
