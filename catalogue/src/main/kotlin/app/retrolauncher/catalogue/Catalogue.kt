package app.retrolauncher.catalogue

import app.retrolauncher.catalogue.am.AmCommand
import app.retrolauncher.catalogue.am.AmStartArguments
import app.retrolauncher.catalogue.model.CatalogueData
import app.retrolauncher.catalogue.model.CatalogueJson
import app.retrolauncher.catalogue.model.Platform
import app.retrolauncher.catalogue.model.Player
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** A loaded catalogue with lookups by ID, ROM folder name and installed package. */
class Catalogue(val data: CatalogueData) {

    val platforms: List<Platform> get() = data.platforms

    private val byId: Map<String, Platform> = buildMap {
        // Old IDs first, so a current ID always wins over an old one.
        for (p in data.platforms) for (old in p.was) putIfAbsent(old.lowercase(), p)
        for (p in data.platforms) put(p.id.lowercase(), p)
    }

    private val byFolder: Map<String, Platform> = buildMap {
        for (p in data.platforms) for (old in p.was) putIfAbsent(normalizeFolder(old), p)
        for (p in data.platforms) for (alias in p.folderAliases) put(normalizeFolder(alias), p)
        for (p in data.platforms) put(normalizeFolder(p.id), p)
    }

    private val commands = ConcurrentHashMap<String, AmCommand>()
    private val patterns = ConcurrentHashMap<String, Pattern>()

    fun platform(id: String): Platform? = byId[id.lowercase()]

    /**
     * Matches a ROM folder name to a platform by ID, `folderAliases` or old ID, ignoring case
     * and surrounding whitespace. For example `GBA`, `gba` and ` gba ` all map to Game Boy Advance.
     */
    fun platformForFolder(folderName: String): Platform? = byFolder[normalizeFolder(folderName)]

    /** Finds a player by current or old ID. */
    fun player(platform: Platform, playerId: String): Player? =
        platform.players.firstOrNull { it.id == playerId }
            ?: platform.players.firstOrNull { playerId in it.was }

    /** The parsed `am start` command for [player], cached. */
    fun command(player: Player): AmCommand =
        commands.getOrPut(player.amStartArguments) { AmStartArguments.parse(player.amStartArguments) }

    /** The app package a player launches, from its `-n` switch. */
    fun packageName(player: Player): String? = command(player).packageName

    /**
     * The players whose app is installed. Different RetroArch builds (`com.retroarch.aarch64`,
     * `com.retroarch.ra32`, `com.retroarch`) are separate players, so only the installed ones remain.
     */
    fun installedPlayers(platform: Platform, isInstalled: (String) -> Boolean): List<Player> =
        platform.players.filter { player -> packageName(player)?.let(isInstalled) == true }

    /** True if [fileName] belongs to [platform]. The default regex excludes dot-files such as `._Game.gba`. */
    fun platformAccepts(platform: Platform, fileName: String): Boolean =
        matches(platform.acceptedFilenameRegex, fileName)

    /** True if [player] can open [fileName]. Players mostly filter by extension. */
    fun playerAccepts(player: Player, fileName: String): Boolean =
        matches(player.acceptedFilenameRegex, fileName)

    /** Installed players that accept [fileName], in catalogue order. The first is a sensible default. */
    fun playersFor(platform: Platform, fileName: String, isInstalled: (String) -> Boolean): List<Player> =
        installedPlayers(platform, isInstalled).filter { playerAccepts(it, fileName) }

    /**
     * Matching ignores case, so `Game.GBA` matches `\.gba`. A regex Java can't compile (upstream
     * writes them for Python) accepts everything instead of hiding the platform.
     */
    private fun matches(regex: String?, fileName: String): Boolean {
        if (regex == null) return true
        val pattern = patterns[regex] ?: compile(regex)?.also { patterns[regex] = it } ?: return true
        return pattern.matcher(fileName).matches()
    }

    companion object {
        fun parse(json: String): Catalogue = Catalogue(CatalogueJson.decodeFromString(json))

        fun compile(regex: String): Pattern? = try {
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
        } catch (_: PatternSyntaxException) {
            null
        }

        private fun normalizeFolder(name: String) = name.trim().lowercase()
    }
}
