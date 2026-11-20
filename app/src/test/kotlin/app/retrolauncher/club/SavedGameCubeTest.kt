package app.retrolauncher.club

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * Opt-in: hashes a real GameCube image (-PgcFile=..., RVZ or ISO) and, with -PgcHashes=... (a
 * saved copy of RetroAchievements' `hashlibrary&c=16`), says whether RA recognises it.
 */
class SavedGameCubeTest {
    @Test
    fun `hash a real GameCube image`() {
        val path = System.getProperty("gc.file").orEmpty()
        assumeTrue("set -PgcFile to run", path.isNotEmpty())
        val file = File(path)
        val rom = object : RomFile {
            override val name = file.name
            override val parentName: String? = null
            override fun open(): InputStream = file.inputStream()
            override fun openChannel(): SeekableByteChannel = java.nio.file.Files.newByteChannel(file.toPath(), StandardOpenOption.READ)
        }
        val started = System.nanoTime()
        val hash = RaHash.hash(RaConsoles.GAMECUBE, rom)
        println("GameCube hash of ${file.name}: $hash (${(System.nanoTime() - started) / 1_000_000} ms)")
        val library = System.getProperty("gc.hashes").orEmpty()
        if (library.isNotEmpty() && hash != null) {
            val match = Regex(""""${hash}"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(File(library).readText())
            println(if (match != null) "RetroAchievements recognises it: game ${match.groupValues[1]}" else "Not in RetroAchievements' list")
        }
    }
}
