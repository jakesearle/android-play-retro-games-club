package app.retrolauncher.club

import app.retrolauncher.club.patch.PatchFormat
import app.retrolauncher.club.patch.Patcher
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile

/**
 * Opt-in: applies every patch in a RAPatches archive (-PpatchArchive, a .zip) to a real base
 * ROM (-PpatchBase) and, with -PromConsole and -PromHashes, says which results
 * RetroAchievements recognises.
 */
class SavedPatchTest {
    @Test
    fun `apply a real patch to a real base ROM`() {
        val archive = System.getProperty("patch.archive").orEmpty()
        val base = System.getProperty("patch.base").orEmpty()
        assumeTrue("set -PpatchArchive and -PpatchBase to run", archive.isNotEmpty() && base.isNotEmpty())
        val console = System.getProperty("rom.console").orEmpty().toInt()
        val hashes = File(System.getProperty("rom.hashes").orEmpty()).readText()
        val source = RandomAccessFile(base, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val baseExt = base.substringAfterLast('.')
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val format = PatchFormat.of(entry.name) ?: continue
                val patch = zip.getInputStream(entry).use { it.readBytes() }
                val out = File.createTempFile("patched", ".$baseExt")
                val label = entry.name.substringAfterLast('/')
                try {
                    val started = System.nanoTime()
                    Patcher.apply(format, patch, source, out)
                    val rom = object : RomFile {
                        override val name = "patched.$baseExt"
                        override val parentName: String? = null
                        override fun open(): InputStream = out.inputStream()
                        override fun openChannel(): SeekableByteChannel = java.nio.file.Files.newByteChannel(out.toPath(), StandardOpenOption.READ)
                    }
                    val hash = RaHash.hash(console, rom)
                    val game = Regex(""""${hash}"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(hashes)?.groupValues?.get(1)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    println("PATCH $label [$format, ${out.length()} bytes, $ms ms]: $hash -> ${game?.let { "RA game $it" } ?: "not recognised"}")
                } catch (e: Exception) {
                    println("PATCH $label [$format]: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    out.delete()
                }
            }
        }
    }
}
