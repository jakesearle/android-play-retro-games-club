package app.retrolauncher.club

/**
 * RetroAchievements console IDs (rcheevos `rc_consoles.h`) and the labels the club page puts
 * after a title, "Paper Mario (N64)". Ported from the NextUI Pak's `src/roms.c`.
 */
object RaConsoles {
    data class Console(val id: Int, val label: String, val name: String)

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

    /** Labels collected from the club's history, with the name used in messages. */
    private val LABELS = listOf(
        Console(ARCADE, "Arcade", "Arcade"),
        Console(NINTENDO, "NES", "NES"),
        Console(FAMICOM_DISK_SYSTEM, "FDS", "Famicom Disk System"),
        Console(SUPER_NINTENDO, "SNES", "SNES"),
        Console(NINTENDO_64, "N64", "Nintendo 64"),
        Console(GAMECUBE, "GC", "GameCube"),
        Console(WII, "Wii", "Wii"),
        Console(GAMEBOY, "GB", "Game Boy"),
        Console(GAMEBOY_COLOR, "GBC", "Game Boy Color"),
        Console(GAMEBOY_ADVANCE, "GBA", "Game Boy Advance"),
        Console(NINTENDO_DS, "NDS", "Nintendo DS"),
        Console(VIRTUAL_BOY, "VB", "Virtual Boy"),
        Console(MEGA_DRIVE, "MD", "Genesis"),
        Console(MEGA_DRIVE, "Genesis", "Genesis"),
        Console(MASTER_SYSTEM, "SMS", "Master System"),
        Console(GAME_GEAR, "GG", "Game Gear"),
        Console(SEGA_CD, "SCD", "Sega CD"),
        Console(SEGA_32X, "32X", "32X"),
        Console(SATURN, "Saturn", "Saturn"),
        Console(DREAMCAST, "DC", "Dreamcast"),
        Console(PLAYSTATION, "PS1", "PlayStation"),
        Console(PLAYSTATION, "PSX", "PlayStation"),
        Console(PLAYSTATION_2, "PS2", "PlayStation 2"),
        Console(PSP, "PSP", "PSP"),
        Console(PC_ENGINE, "PCE", "PC Engine"),
        Console(PC_ENGINE, "TG16", "TurboGrafx-16"),
        Console(NEOGEO_POCKET, "NGP", "Neo Geo Pocket"),
        Console(NEOGEO_POCKET, "NGPC", "Neo Geo Pocket Color"),
        Console(ATARI_LYNX, "Lynx", "Lynx"),
        Console(ATARI_2600, "2600", "Atari 2600"),
        Console(ATARI_7800, "7800", "Atari 7800"),
    )

    /** Other short forms a title might use. */
    private val ALIASES = mapOf(
        "FC" to NINTENDO, "Famicom" to NINTENDO, "SFC" to SUPER_NINTENDO, "Super Famicom" to SUPER_NINTENDO,
        "Mega Drive" to MEGA_DRIVE, "GEN" to MEGA_DRIVE, "Sega CD" to SEGA_CD, "Mega CD" to SEGA_CD,
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
        LABELS.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { return it }
        val id = ALIASES.entries.firstOrNull { it.key.equals(label, ignoreCase = true) }?.value ?: return null
        return Console(id, label, name(id) ?: label)
    }

    /** The trailing label, even when it names no known console ("Paper Mario (N64)" → "N64"). */
    fun labelOf(title: String): String? = TRAILING_LABEL.find(title)?.groupValues?.get(1)?.trim()

    fun titleWithoutLabel(title: String): String = title.replace(TRAILING_LABEL, "").ifEmpty { title }

    fun name(id: Int): String? = LABELS.firstOrNull { it.id == id }?.name

    /** The game ID in a `retroachievements.org/game/<id>` link, or 0. */
    fun gameIdFromUrl(url: String): Int =
        Regex("""/game/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
