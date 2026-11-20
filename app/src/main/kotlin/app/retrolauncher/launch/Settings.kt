package app.retrolauncher.launch

import android.content.Context
import androidx.core.content.edit
import app.retrolauncher.library.Game
import app.retrolauncher.library.RomSource

/** ROM folders and emulator choices, kept in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var sources: List<RomSource>
        get() = prefs.getStringSet(SOURCES, emptySet()).orEmpty().mapNotNull(RomSource::fromKey).sortedBy { it.label }
        set(value) = prefs.edit { putStringSet(SOURCES, value.map { it.key }.toSet()) }

    fun choices(game: Game) = Choices(
        platformDefault = prefs.getString(platformKey(game.platformId), null),
        gameOverride = prefs.getString(gameKey(game), null),
    )

    fun platformDefault(platformId: String): String? = prefs.getString(platformKey(platformId), null)

    fun setPlatformDefault(platformId: String, playerId: String?) =
        prefs.edit { if (playerId == null) remove(platformKey(platformId)) else putString(platformKey(platformId), playerId) }

    fun setGameOverride(game: Game, playerId: String?) =
        prefs.edit { if (playerId == null) remove(gameKey(game)) else putString(gameKey(game), playerId) }

    private fun platformKey(id: String) = "platform/$id"
    private fun gameKey(game: Game) = "game/${game.key}"

    /** Whether the first-run setup has been finished or skipped. */
    var setupDone: Boolean
        get() = prefs.getBoolean(SETUP_DONE, false)
        set(value) = prefs.edit { putBoolean(SETUP_DONE, value) }

    /** Debug builds only: extra test picks, as "title|RetroAchievements URL" lines. */
    var debugPicks: List<String>
        get() = prefs.getString(DEBUG_PICKS, null)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit { putString(DEBUG_PICKS, value.joinToString("\n")) }

    private companion object {
        const val SOURCES = "sources"
        const val DEBUG_PICKS = "debug_picks"
        const val SETUP_DONE = "setup_done"
    }
}
