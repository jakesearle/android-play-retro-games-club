package app.retrolauncher.club.patch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class PatchSourceTest {
    private val big = Random(1).nextBytes(2_000_000) // stands in for docs and other versions
    private val v1 = Random(2).nextBytes(50_000)
    private val v2 = ByteArray(80_000) { (it % 7).toByte() } // compresses well

    private fun zip(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("Original Docs/Manual.pdf")); z.write(big); z.closeEntry()
            z.putNextEntry(ZipEntry("Game - Hack (v1.0).bps")); z.write(v1); z.closeEntry()
            // One entry stored, not deflated, to cover both methods.
            val stored = ZipEntry("Game - Hack (v2.0).bps").apply {
                method = ZipEntry.STORED; size = v2.size.toLong(); compressedSize = v2.size.toLong()
                crc = CRC32().apply { update(v2) }.value
            }
            z.putNextEntry(stored); z.write(v2); z.closeEntry()
            z.putNextEntry(ZipEntry("readme.txt")); z.write("hi".toByteArray()); z.closeEntry()
        }
    }.toByteArray()

    @Test
    fun `a remote zip lists its patches and downloads only the one asked for`() {
        val bytes = zip()
        var downloaded = 0L
        val remote = RemoteZip { start, end ->
            val s = if (start < 0) maxOf(0, bytes.size + start.toInt()) else start.toInt()
            val e = if (start < 0) bytes.size else minOf(end.toInt(), bytes.size)
            downloaded += e - s
            ByteArrayInputStream(bytes, s, e - s).readBytes()
        }
        assertEquals(listOf("Game - Hack (v1.0).bps", "Game - Hack (v2.0).bps"), remote.entries.map { it.name })
        assertArrayEquals(v2, remote.read(remote.entries[1]))
        assertArrayEquals(v1, remote.read(remote.entries[0]))
        // The 2 MB document was never downloaded.
        assertTrue("downloaded $downloaded of ${bytes.size}", downloaded < bytes.size / 5)
    }

    @Test
    fun `patches RetroAchievements names come first, then newest`() {
        val entries = listOf("Hack (v1.0).bps", "Hack (v1.1).bps", "Hack (v2.0).bps", "Hack (v2.1) (Hard Mode).bps")
            .map { PatchEntry(it, PatchFormat.BPS) }
        assertEquals(listOf("Hack (v2.1) (Hard Mode).bps", "Hack (v2.0).bps", "Hack (v1.1).bps", "Hack (v1.0).bps"),
            PatchOrder.order(entries, emptyList()).map { it.name })
        // RA supports v1.1 (its file name, with the ROM's extension), so that's tried first.
        assertEquals(listOf("Hack (v1.1).bps", "Hack (v2.1) (Hard Mode).bps", "Hack (v2.0).bps", "Hack (v1.0).bps"),
            PatchOrder.order(entries, listOf("Hack (v1.1).gba")).map { it.name })
        // Several supported, listed oldest first by RA: still the newest supported one first.
        assertEquals(listOf("Hack (v2.0).bps", "Hack (v1.0).bps", "Hack (v2.1) (Hard Mode).bps", "Hack (v1.1).bps"),
            PatchOrder.order(entries, listOf("Hack (v1.0).gba", "Hack (v2.0).gba")).map { it.name })
    }
}
