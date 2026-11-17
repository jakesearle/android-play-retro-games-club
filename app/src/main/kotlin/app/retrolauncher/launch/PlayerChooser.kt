package app.retrolauncher.launch

import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.model.Platform
import app.retrolauncher.catalogue.model.Player
import app.retrolauncher.library.Game

/** An installed emulator that accepts a game's file type, and whether it can launch this copy. */
data class PlayerOption(val player: Player, val problem: String?) {
    val usable: Boolean get() = problem == null
}

/** Saved emulator choices, as player IDs (current or old). */
data class Choices(val platformDefault: String?, val gameOverride: String?)

/**
 * Chooses the emulator for a game. Order: per-game override, then platform default, then the
 * first usable player in catalogue order. A saved choice that is no longer installed or usable
 * is skipped, not treated as an error.
 */
object PlayerChooser {

    fun options(catalogue: Catalogue, platform: Platform, game: Game, isInstalled: (String) -> Boolean): List<PlayerOption> =
        catalogue.playersFor(platform, game.fileName, isInstalled).map { PlayerOption(it, problem(catalogue, it, game)) }

    fun pick(
        catalogue: Catalogue,
        platform: Platform,
        game: Game,
        choices: Choices,
        isInstalled: (String) -> Boolean,
    ): Player? {
        val usable = options(catalogue, platform, game, isInstalled).filter { it.usable }.map { it.player }
        fun saved(id: String?) = id?.let { catalogue.player(platform, it) }?.takeIf { p -> usable.any { it.id == p.id } }
        return saved(choices.gameOverride) ?: saved(choices.platformDefault) ?: usable.firstOrNull()
    }

    /** Why [player] can't launch [game], or null if it can. */
    fun problem(catalogue: Catalogue, player: Player, game: Game): String? {
        val cmd = catalogue.command(player)
        return when {
            cmd.needsFilePath && game.path == null -> "Needs a real file path. Add this folder as a raw path instead."
            // A raw-path game can still get a content:// URI from the FileProvider.
            cmd.needsFileUri && game.uri == null && game.path == null -> "Needs a content:// URI"
            cmd.tagNames.isNotEmpty() -> "Needs game tags: ${cmd.tagNames.joinToString()}"
            else -> null
        }
    }
}
