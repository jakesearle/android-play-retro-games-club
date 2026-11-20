package app.retrolauncher.launch

import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.am.AmStartArguments
import app.retrolauncher.catalogue.model.Player

/**
 * Emulators the app knows about that the Cocoon catalogue doesn't list (yet), added to the
 * catalogue's platforms after it loads. If the catalogue later lists the same app (by package),
 * its entry wins and ours is dropped.
 *
 * Some emulators are also preferred on a platform ([PREFERRED], from Joey's Retro Handhelds'
 * recommendations): their players move to the front, so they're the default whenever installed ([PlayerChooser] picks the first usable player), while
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

    /** A preferred emulator: an app (any of [packages]), maybe a RetroArch [core], or a player by [name]. */
    data class Preference(val packages: Set<String> = emptySet(), val core: String? = null, val name: String? = null) {
        fun matches(player: Player): Boolean =
            (packages.isEmpty() || packageOf(player) in packages) &&
                (core == null || player.name.substringAfter(" - ", "").equals(core, ignoreCase = true)) &&
                (name == null || player.name == name)
    }

    private val RETROARCH = setOf("com.retroarch.aarch64", "com.retroarch.ra32", "com.retroarch")
    private fun app(vararg packages: String) = Preference(packages.toSet())
    private fun core(core: String) = Preference(RETROARCH, core = core)

    /**
     * Catalogue platform ID → emulators to put first, best first, making them the default when
     * installed. From Joey's Retro Handhelds' recommended Android emulators
     * (https://joeysretrohandhelds.com/guides/recommended-android-emulators/, read November 2026),
     * except N64, where Joey64 comes first and RetroArch next because the club plays for
     * RetroAchievements (Joey recommends M64Plus FZ Pro "UNLESS you need RetroAchievements").
     */
    private val PREFERRED: Map<String, List<Preference>> = mapOf(
        "n64" to listOf(app("com.joeysretro.joey64"), core("mupen64plus_next_gles3"), app("org.mupen64plusae.v3.fzurita.pro")),
        "nes" to listOf(core("fceumm")),
        "snes" to listOf(core("snes9x")),
        "gb" to listOf(core("gambatte")),
        "gbc" to listOf(core("gambatte")),
        "gba" to listOf(core("mgba")),
        "nds" to listOf(app("me.magnum.melondualds"), core("melonds ds")),
        "n3ds" to listOf(Preference(name = "Azahar")),
        "gc" to listOf(app("org.dolphinemu.dolphinemu")),
        "wii" to listOf(app("org.dolphinemu.dolphinemu")),
        "wiiu" to listOf(app("info.cemu.cemu")),
        "psx" to listOf(app("com.github.stenzek.duckstation"), core("swanstation")),
        "ps2" to listOf(app("com.armsx2")),
        "psp" to listOf(app("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold")),
        "dreamcast" to listOf(core("flycast"), app("com.flycast.emulator")),
        "saturn" to listOf(core("beetle saturn")),
        "genesis" to listOf(core("genesis_plus_gx")),
        "mastersystem" to listOf(core("genesis_plus_gx")),
        "gamegear" to listOf(core("genesis_plus_gx")),
        "segacd" to listOf(core("genesis_plus_gx")),
        "sega32x" to listOf(core("picodrive")),
        "atari2600" to listOf(core("stella")),
        "atari7800" to listOf(core("prosystem")),
        "atarilynx" to listOf(core("mednafen_lynx")),
        "fbneo" to listOf(core("fbneo")),
    )

    /** Packages preferred on [platformId], best first (also used to rank emulator suggestions). */
    fun preferredFor(platformId: String): List<String> =
        PREFERRED[platformId].orEmpty().flatMap { it.packages }.distinct()

    /** Where [player] ranks among [platformId]'s preferences: 0 is best; unpreferred players come last. */
    internal fun rank(platformId: String, player: Player): Int {
        val prefs = PREFERRED[platformId].orEmpty()
        return prefs.indexOfFirst { it.matches(player) }.let { if (it < 0) prefs.size else it }
    }

    /** [catalogue] with the extra players added to their platforms. */
    fun addTo(catalogue: Catalogue): Catalogue {
        val data = catalogue.data
        var changed = false
        val platforms = data.platforms.map { platform ->
            val listed = platform.players.mapNotNull { packageOf(it) }.toSet()
            val extra = BY_PLATFORM[platform.id].orEmpty().filter { packageOf(it) !in listed }
            val all = platform.players + extra
            // Stable: preferred emulators first (in PREFERRED order), everything else as it was.
            val ordered = all.sortedBy { rank(platform.id, it) }
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
