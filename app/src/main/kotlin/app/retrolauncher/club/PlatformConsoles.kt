package app.retrolauncher.club

import app.retrolauncher.catalogue.model.Platform

/**
 * The RetroAchievements consoles a catalogue platform's games belong to. Mostly the
 * catalogue's own `retroAchievementsConsoleIds`, corrected where it's wrong (catalogue
 * version 3, November 2026; see docs/EMULATORS.md):
 * - `fds` is tagged NES (7) instead of Famicom Disk System (81), so an FDS pick would never
 *   search the `fds` folder, and an NES pick would wrongly search it.
 * - `wii` and `wiiu` each carry both Wii (19) and Wii U (20).
 */
object PlatformConsoles {
    private val OVERRIDES = mapOf(
        "fds" to listOf(RaConsoles.FAMICOM_DISK_SYSTEM),
        "wii" to listOf(RaConsoles.WII),
        "wiiware" to listOf(RaConsoles.WII),
        "wiiu" to listOf(RaConsoles.WII_U),
    )

    fun of(platform: Platform): List<Int> = OVERRIDES[platform.id] ?: platform.retroAchievementsConsoleIds

    fun holds(platform: Platform, consoleId: Int): Boolean = consoleId in of(platform)
}
