package app.retrolauncher.launch

import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.am.AmStartArguments
import app.retrolauncher.catalogue.model.Player

/**
 * Emulators the app knows about that the Cocoon catalogue doesn't list (yet), added to the
 * catalogue's platforms after it loads. If the catalogue later lists the same app (by package),
 * its entry wins and ours is dropped.
 *
 * Some apps are also preferred on a platform ([PREFERRED]): their players move to the front, so
 * they're the default whenever installed ([PlayerChooser] picks the first usable player), while
 * a choice the player saves for a game or platform still comes first.
 */
object ExtraPlayers {
    /**
     * [Joey64](https://github.com/JoeysRetroHandhelds/joey64), a controller-first N64 emulator
     * on its own Mupen64Plus-Next fork. Its exported game screen takes the ROM as a VIEW intent's
     * data and opens it through the content resolver (copying it to its cache), so it needs a
     * `content://` URI with a read grant, which [GameLauncher] provides. Android 10+, arm64.
     * Accepts plain and zipped/7z ROMs, and 64DD disks.
     */
    val JOEY64 = Player(
        id = "n64.com.joeysretro.joey64",
        name = "Joey64",
        acceptedFilenameRegex = """^(.*)\.(?:z64|n64|v64|zip|7z|ndd|d64)$""",
        amStartArguments = listOf(
            "-n com.joeysretro.joey64/.MainActivity",
            "-a android.intent.action.VIEW",
            "-d {file.uri}",
            "-t application/octet-stream",
        ).joinToString("\n"),
    )

    /** Catalogue platform ID → players to add. */
    private val BY_PLATFORM = mapOf("n64" to listOf(JOEY64))

    /** Catalogue platform ID → packages to put first, making them the default when installed. */
    private val PREFERRED = mapOf("n64" to listOf("com.joeysretro.joey64"))

    /** Packages preferred on [platformId], best first (also used to rank emulator suggestions). */
    fun preferredFor(platformId: String): List<String> = PREFERRED[platformId].orEmpty()

    /** [catalogue] with the extra players added to their platforms. */
    fun addTo(catalogue: Catalogue): Catalogue {
        val data = catalogue.data
        var changed = false
        val platforms = data.platforms.map { platform ->
            val listed = platform.players.mapNotNull { packageOf(it) }.toSet()
            val extra = BY_PLATFORM[platform.id].orEmpty().filter { packageOf(it) !in listed }
            val preferred = PREFERRED[platform.id].orEmpty()
            val all = platform.players + extra
            // Stable: preferred apps first (in PREFERRED order), everything else as it was.
            val ordered = all.sortedBy { p -> preferred.indexOf(packageOf(p)).let { if (it < 0) preferred.size else it } }
            if (ordered == platform.players) {
                platform
            } else {
                changed = true
                platform.copy(players = ordered)
            }
        }
        return if (changed) Catalogue(data.copy(platforms = platforms)) else catalogue
    }

    private fun packageOf(player: Player): String? = AmStartArguments.parse(player.amStartArguments).packageName
}
