package app.retrolauncher.club

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * Opt-in: hashes a real disc image (-PromFile=...) for a console (-PromConsole=RA console ID,
 * GameCube by default) and, with -PromHashes=... (a saved copy of RetroAchievements'
 * `hashlibrary&c=<console>`), says whether RA recognises it.
 */
class SavedDiscImageTest {
    @Test
    fun `hash a real disc image`() {
        val path = System.getProperty("rom.file").orEmpty()
        assumeTrue("set -PromFile to run", path.isNotEmpty())
        val console = System.getProperty("rom.console").orEmpty().toIntOrNull() ?: RaConsoles.GAMECUBE
        val file = File(path)
        val rom = object : RomFile {
            override val name = file.name
            override val parentName: String? = null
            override fun open(): InputStream = file.inputStream()
            override fun openChannel(): SeekableByteChannel = java.nio.file.Files.newByteChannel(file.toPath(), StandardOpenOption.READ)
        }
        val started = System.nanoTime()
        val hash = RaHash.hash(console, rom)
        println("Console $console hash of ${file.name}: $hash (${(System.nanoTime() - started) / 1_000_000} ms)")
        val library = System.getProperty("rom.hashes").orEmpty()
        if (library.isNotEmpty() && hash != null) {
            val match = Regex(""""${hash}"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(File(library).readText())
            println(if (match != null) "RetroAchievements recognises it: game ${match.groupValues[1]}" else "Not in RetroAchievements' list")
        }
    }
}
