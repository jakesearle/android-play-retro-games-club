package app.retrolauncher.library

import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.model.Platform

/**
 * Maps a ROM folder name to a catalogue platform. The catalogue knows each platform's ID and a
 * few aliases. Other frontends name folders differently, so lookups go in this order:
 * 1. The catalogue's own IDs, aliases and old IDs.
 * 2. ES-DE's folder names ([ES_DE]), the default layout for many Android frontends.
 * 3. Other common short names ([OTHER]): Batocera, MinUI/NextUI tags and similar.
 * 4. A NextUI/MinUI folder's tag in parentheses: `Super Nintendo Entertainment System (SFC)`
 *    → `SFC` → snes.
 */
object FolderAliases {
    /**
     * ES-DE folder names the catalogue doesn't list, mapped to the closest catalogue platform.
     * From ES-DE's `resources/systems/android/es_systems.xml` (commit 886945e1, October 28,
     * 2026): of its 195 systems, 98 match catalogue names, these 36 are mapped here, and 61 have
     * no catalogue platform, so nothing could launch them: computers such as Atari 800, Oric
     * and TI-99, game engines (OpenBOR, Solarus, M.U.G.E.N), Android apps, pinball, LaserDisc,
     * PS4 and Xbox One. See docs/EMULATORS.md.
     */
    internal val ES_DE = mapOf(
        // Regional and model variants of catalogue platforms.
        "snesna" to "snes", "sfc" to "snes", "sufami" to "snes",
        "megadrive" to "genesis", "megadrivejp" to "genesis",
        "sega32xjp" to "sega32x", "sega32xna" to "sega32x",
        "megacd" to "segacd", "megacdjp" to "segacd",
        "saturnjp" to "saturn", "neogeocdjp" to "neogeocd",
        "mark3" to "mastersystem", "multivision" to "sg-1000",
        "pcengine" to "tg16", "pcenginecd" to "tg-cd",
        "famicom" to "nes", "n64dd" to "n64",
        "msx1" to "msx", "msx2" to "msx", "msxturbor" to "msx",
        "amiga600" to "amiga", "amiga1200" to "amiga", "amigacd32" to "amiga", "cdtv" to "amiga",
        "gx4000" to "amstradcpc",
        // Arcade.
        "arcade" to "mame", "mame-advmame" to "mame", "stv" to "mame",
        "fba" to "fbneo", "cps" to "fbneo",
        "naomi2" to "naomi", "naomigd" to "naomi",
        // Other systems under a different name.
        "lcdgames" to "gameandwatch", "flash" to "adobeflash", "symbian" to "ngage", "easyrpg" to "rpgmaker",
    )

    /** Other short names: Batocera, MinUI/NextUI folder tags and similar. */
    internal val OTHER = mapOf(
        "snesjp" to "snes", "superfamicom" to "snes", "supa" to "snes",
        "md" to "genesis", "32x" to "sega32x", "segacdjp" to "segacd",
        "pce" to "tg16", "pcecd" to "tg-cd", "tgcd" to "tg-cd", "fc" to "nes",
        "sms" to "mastersystem", "gg" to "gamegear", "sg1000" to "sg-1000",
        "ps1" to "psx", "ps" to "psx", "n64jp" to "n64",
        "vb" to "virtualboy", "ngc" to "gc", "gamecube" to "gc",
        "ngpc" to "ngp", "wsc" to "wonderswan", "lynx" to "atarilynx",
        "a2600" to "atari2600", "a5200" to "atari5200", "a7800" to "atari7800",
        "coleco" to "colecovision", "pkm" to "pokemini", "mgba" to "gba", "sgb" to "gb", "fbn" to "fbneo",
    )

    private val TAG = Regex("""\(([^()]+)\)\s*$""")

    fun platformFor(catalogue: Catalogue, folderName: String): Platform? {
        val name = folderName.trim()
        lookup(catalogue, name)?.let { return it }
        val tag = TAG.find(name)?.groupValues?.get(1) ?: return null
        return lookup(catalogue, tag)
    }

    private fun lookup(catalogue: Catalogue, name: String): Platform? {
        catalogue.platformForFolder(name)?.let { return it }
        val key = name.lowercase()
        return (ES_DE[key] ?: OTHER[key])?.let(catalogue::platform)
    }
}
