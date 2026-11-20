package app.retrolauncher.launch

import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.model.Player

/**
 * Which RetroArch core a player needs. The app can tell RetroArch is installed, but not which
 * cores it has: they live in RetroArch's private storage, which no other app can read. So the
 * game view names the core and where to get it, instead of failing silently when it's missing.
 */
object RetroArchCores {
    const val HOW_TO_GET = "RetroArch › Main Menu › Online Updater › Core Downloader"

    /**
     * The core's name for [player] ("beetle saturn"), or null if it isn't a RetroArch player.
     * Taken from the catalogue's player name ("RetroArch (64 bits) - beetle saturn"), else from
     * the core file in its `LIBRETRO` extra (`mednafen_saturn_libretro_android.so`).
     */
    fun coreName(catalogue: Catalogue, player: Player): String? {
        if (catalogue.packageName(player)?.startsWith("com.retroarch") != true) return null
        val file = catalogue.command(player).extras.firstOrNull { it.key == "LIBRETRO" }?.value ?: return null
        return player.name.substringAfter(" - ", "").trim().ifEmpty {
            file.substringAfterLast('/').removeSuffix("_libretro_android.so").ifEmpty { null }
        }
    }
}
