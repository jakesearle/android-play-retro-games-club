package app.retrolauncher.club

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * RetroAchievements console IDs (rcheevos `rc_consoles.h`) and the labels the club page puts
 * after a title, "Paper Mario (N64)". Ported from the NextUI Pak's `src/roms.c`.
 */
object RaConsoles {
    /** A console a title's label names. [name] is the console's name in the current [region]. */
    data class Console(val id: Int, val label: String) {
        val name: String get() = name(id) ?: label
    }

    /** Where a console's name comes from: some consoles were sold under different names. */
    enum class Region(val title: String) {
        NORTH_AMERICA("North America"),
        EUROPE("Europe"),
        JAPAN("Japan");

        companion object {
            /** The region for a device country code ("US", "CA", "GB"). Anywhere else uses Europe's names. */
            fun ofCountry(country: String): Region = when (country.uppercase()) {
                "US", "CA", "MX", "PR" -> NORTH_AMERICA
                "JP" -> JAPAN
                else -> EUROPE
            }
        }
    }

    /**
     * The region whose names [name] gives. Compose snapshot state, so a screen showing a
     * console name redraws when the setting changes.
     */
    var region: Region by mutableStateOf(Region.NORTH_AMERICA)

    const val MEGA_DRIVE = 1
    const val NINTENDO_64 = 2
    const val SUPER_NINTENDO = 3
    const val GAMEBOY = 4
    const val GAMEBOY_ADVANCE = 5
    const val GAMEBOY_COLOR = 6
    const val NINTENDO = 7
    const val PC_ENGINE = 8
    const val SEGA_CD = 9
    const val SEGA_32X = 10
    const val MASTER_SYSTEM = 11
    const val PLAYSTATION = 12
    const val ATARI_LYNX = 13
    const val NEOGEO_POCKET = 14
    const val GAME_GEAR = 15
    const val GAMECUBE = 16
    const val ATARI_JAGUAR = 17
    const val NINTENDO_DS = 18
    const val WII = 19
    const val WII_U = 20
    const val PLAYSTATION_2 = 21
    const val XBOX = 22
    const val MAGNAVOX_ODYSSEY2 = 23
    const val POKEMON_MINI = 24
    const val ATARI_2600 = 25
    const val MS_DOS = 26
    const val ARCADE = 27
    const val VIRTUAL_BOY = 28
    const val MSX = 29
    const val COMMODORE_64 = 30
    const val ORIC = 32
    const val SG1000 = 33
    const val VIC20 = 34
    const val AMIGA = 35
    const val ATARI_ST = 36
    const val AMSTRAD_PC = 37
    const val APPLE_II = 38
    const val SATURN = 39
    const val DREAMCAST = 40
    const val PSP = 41
    const val CDI = 42
    const val THREEDO = 43
    const val COLECOVISION = 44
    const val INTELLIVISION = 45
    const val VECTREX = 46
    const val PC8800 = 47
    const val PCFX = 49
    const val ATARI_5200 = 50
    const val ATARI_7800 = 51
    const val WONDERSWAN = 53
    const val SUPER_CASSETTEVISION = 55
    const val NEO_GEO_CD = 56
    const val FAIRCHILD_CHANNEL_F = 57
    const val ZX_SPECTRUM = 59
    const val GAME_AND_WATCH = 60
    const val NOKIA_NGAGE = 61
    const val NINTENDO_3DS = 62
    const val SUPERVISION = 63
    const val TIC80 = 65
    const val PICO = 68
    const val MEGADUCK = 69
    const val ARDUBOY = 71
    const val WASM4 = 72
    const val ARCADIA_2001 = 73
    const val INTERTON_VC_4000 = 74
    const val ELEKTOR_TV_GAMES_COMPUTER = 75
    const val PC_ENGINE_CD = 76
    const val ATARI_JAGUAR_CD = 77
    const val NINTENDO_DSI = 78
    const val TI83 = 79
    const val UZEBOX = 80
    const val FAMICOM_DISK_SYSTEM = 81

    /** Labels collected from the club's history. */
    private val LABELS = mapOf(
        "Arcade" to ARCADE, "NES" to NINTENDO, "FDS" to FAMICOM_DISK_SYSTEM, "SNES" to SUPER_NINTENDO,
        "N64" to NINTENDO_64, "GC" to GAMECUBE, "Wii" to WII, "GB" to GAMEBOY, "GBC" to GAMEBOY_COLOR,
        "GBA" to GAMEBOY_ADVANCE, "NDS" to NINTENDO_DS, "VB" to VIRTUAL_BOY, "MD" to MEGA_DRIVE,
        "Genesis" to MEGA_DRIVE, "SMS" to MASTER_SYSTEM, "GG" to GAME_GEAR, "SCD" to SEGA_CD,
        "32X" to SEGA_32X, "Saturn" to SATURN, "DC" to DREAMCAST, "PS1" to PLAYSTATION, "PSX" to PLAYSTATION,
        "PS2" to PLAYSTATION_2, "PSP" to PSP, "PCE" to PC_ENGINE, "TG16" to PC_ENGINE,
        "NGP" to NEOGEO_POCKET, "NGPC" to NEOGEO_POCKET, "Lynx" to ATARI_LYNX, "2600" to ATARI_2600,
        "7800" to ATARI_7800,
    )

    /** The name used in messages, where every region uses the same one. */
    private val NAMES = mapOf(
        ARCADE to "Arcade", NINTENDO to "NES", FAMICOM_DISK_SYSTEM to "Famicom Disk System",
        SUPER_NINTENDO to "SNES", NINTENDO_64 to "Nintendo 64", GAMECUBE to "GameCube", WII to "Wii",
        GAMEBOY to "Game Boy", GAMEBOY_COLOR to "Game Boy Color", GAMEBOY_ADVANCE to "Game Boy Advance",
        NINTENDO_DS to "Nintendo DS", VIRTUAL_BOY to "Virtual Boy", MASTER_SYSTEM to "Master System",
        GAME_GEAR to "Game Gear", SEGA_32X to "32X", SATURN to "Saturn", DREAMCAST to "Dreamcast",
        PLAYSTATION to "PlayStation", PLAYSTATION_2 to "PlayStation 2", PSP to "PSP",
        NEOGEO_POCKET to "Neo Geo Pocket", ATARI_LYNX to "Lynx", ATARI_2600 to "Atari 2600",
        ATARI_7800 to "Atari 7800",
    )

    /**
     * Consoles sold under different names. Canada had the US names. Europe never had an
     * official TurboGrafx launch, so it knows the imported PC Engine.
     */
    private val REGIONAL_NAMES = mapOf(
        Region.NORTH_AMERICA to mapOf(
            MEGA_DRIVE to "Genesis", SEGA_CD to "Sega CD",
            PC_ENGINE to "TurboGrafx-16", PC_ENGINE_CD to "TurboGrafx-CD",
        ),
        Region.EUROPE to mapOf(
            MEGA_DRIVE to "Mega Drive", SEGA_CD to "Mega-CD",
            PC_ENGINE to "PC Engine", PC_ENGINE_CD to "PC Engine CD",
        ),
        Region.JAPAN to mapOf(
            MEGA_DRIVE to "Mega Drive", SEGA_CD to "Mega-CD",
            PC_ENGINE to "PC Engine", PC_ENGINE_CD to "PC Engine CD-ROM²",
            NINTENDO to "Famicom", SUPER_NINTENDO to "Super Famicom",
        ),
    )

    /** Other short forms a title might use. */
    private val ALIASES = mapOf(
        "FC" to NINTENDO, "Famicom" to NINTENDO, "SFC" to SUPER_NINTENDO, "Super Famicom" to SUPER_NINTENDO,
        "Mega Drive" to MEGA_DRIVE, "GEN" to MEGA_DRIVE, "Sega CD" to SEGA_CD, "Mega CD" to SEGA_CD, "Mega-CD" to SEGA_CD,
        "PC Engine" to PC_ENGINE, "TurboGrafx-16" to PC_ENGINE, "TurboGrafx" to PC_ENGINE,
        "SAT" to SATURN, "PS" to PLAYSTATION, "PCECD" to PC_ENGINE_CD, "PCE-CD" to PC_ENGINE_CD,
        "TGCD" to PC_ENGINE_CD, "TG-16" to PC_ENGINE, "PCFX" to PCFX, "SG1000" to SG1000,
        "Wii U" to WII_U, "WiiU" to WII_U, "3DS" to NINTENDO_3DS, "DSi" to NINTENDO_DSI,
        "Xbox" to XBOX, "JAG" to ATARI_JAGUAR, "Jaguar" to ATARI_JAGUAR, "Jaguar CD" to ATARI_JAGUAR_CD,
        "5200" to ATARI_5200, "A2600" to ATARI_2600, "A5200" to ATARI_5200, "A7800" to ATARI_7800,
        "ST" to ATARI_ST, "WS" to WONDERSWAN, "WSC" to WONDERSWAN, "WonderSwan Color" to WONDERSWAN,
        "NGCD" to NEO_GEO_CD, "INTV" to INTELLIVISION, "CV" to COLECOVISION, "Coleco" to COLECOVISION,
        "O2" to MAGNAVOX_ODYSSEY2, "Odyssey 2" to MAGNAVOX_ODYSSEY2, "DOS" to MS_DOS, "C64" to COMMODORE_64,
        "CPC" to AMSTRAD_PC, "A2" to APPLE_II, "Apple 2" to APPLE_II, "CDi" to CDI,
        "PKM" to POKEMON_MINI, "Pokemon Mini" to POKEMON_MINI, "G&W" to GAME_AND_WATCH,
        "N-Gage" to NOKIA_NGAGE, "Pico" to PICO, "Channel F" to FAIRCHILD_CHANNEL_F,
        // RA's own names, for labels written out in full.
        "Sega Saturn" to SATURN, "Nintendo 64" to NINTENDO_64, "Game Boy Advance" to GAMEBOY_ADVANCE,
        "PlayStation" to PLAYSTATION, "PlayStation 2" to PLAYSTATION_2, "Dreamcast" to DREAMCAST,
    )

    private val TRAILING_LABEL = Regex("""\s*\(([^()]+)\)\s*$""")

    /** The console a title's trailing label names, or null. */
    fun fromTitle(title: String): Console? {
        val label = TRAILING_LABEL.find(title)?.groupValues?.get(1)?.trim() ?: return null
        val match = (LABELS.entries + ALIASES.entries).firstOrNull { it.key.equals(label, ignoreCase = true) }
            ?: return null
        return Console(match.value, match.key)
    }

    /** The trailing label, even when it names no known console ("Paper Mario (N64)" → "N64"). */
    fun labelOf(title: String): String? = TRAILING_LABEL.find(title)?.groupValues?.get(1)?.trim()

    fun titleWithoutLabel(title: String): String = title.replace(TRAILING_LABEL, "").ifEmpty { title }

    fun name(id: Int, region: Region = this.region): String? = REGIONAL_NAMES[region]?.get(id) ?: NAMES[id]

    /** The game ID in a `retroachievements.org/game/<id>` link, or 0. */
    fun gameIdFromUrl(url: String): Int =
        Regex("""/game/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
