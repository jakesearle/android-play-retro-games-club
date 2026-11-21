package app.retrolauncher.ra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * Caches what the Web API says, so the app doesn't ask on every screen:
 * - your progress on every game, refreshed when it's a few minutes old (coming back from a game);
 * - each game's details (achievements, player count), kept [GAME_MAX_AGE_MS]; player counts
 *   change slowly, so they're used for up to [PLAYERS_MAX_AGE_MS] for sorting.
 * Offline, the last answers are used.
 */
class RaRepository(
    private val dir: File,
    private val api: RaApi = RaApi(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val progressFile get() = File(dir, "progress.json")
    private fun gameFile(id: Int) = File(dir, "game-$id.json")

    /** Cached progress, by game ID; empty if never fetched. */
    @Synchronized
    fun cachedProgress(): Map<Int, RaProgress> = try {
        val root = Json.parseToJsonElement(progressFile.readText()) as JsonObject
        root.mapNotNull { (id, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val gameId = id.toIntOrNull() ?: return@mapNotNull null
            gameId to RaProgress(gameId, o.int("a") ?: 0, o.int("h") ?: 0, o.int("t") ?: 0, RaAward.parse(o.str("k")))
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /** Whether the cached progress is older than [maxAgeMs] (or missing). */
    fun progressStale(maxAgeMs: Long): Boolean = !progressFile.exists() || clock() - progressFile.lastModified() > maxAgeMs

    /** Fetches your progress on every game and caches it. Throws on failure ([RaAuthException] for a bad key). */
    @Synchronized
    fun fetchProgress(account: RaAccount): Map<Int, RaProgress> {
        val progress = api.completion(account)
        write(progressFile, buildJsonObject {
            for ((id, p) in progress) put("$id", buildJsonObject {
                put("a", p.awarded); put("h", p.awardedHardcore); put("t", p.total); p.award?.let { put("k", it.apiName) }
            })
        }.toString())
        return progress
    }

    /** A game's details, from the cache if younger than [maxAgeMs], else fetched; the cache when offline. */
    fun game(account: RaAccount, id: Int, maxAgeMs: Long = GAME_MAX_AGE_MS): RaGame? {
        val file = gameFile(id)
        val cached = { runCatching { RaApi.parseGame(Json.parseToJsonElement(file.readText()) as JsonObject) }.getOrNull() }
        if (file.exists() && clock() - file.lastModified() <= maxAgeMs) cached()?.let { return it }
        return try {
            val game = api.game(account, id)
            write(file, gameJson(game))
            game
        } catch (e: RaAuthException) {
            throw e
        } catch (_: IOException) {
            cached()
        } catch (_: RuntimeException) {
            cached()
        }
    }

    /** A game's player count if it's cached and recent enough for sorting, without a request. */
    fun cachedPlayers(id: Int): Int? {
        val file = gameFile(id)
        if (!file.exists() || clock() - file.lastModified() > PLAYERS_MAX_AGE_MS) return null
        return runCatching { (Json.parseToJsonElement(file.readText()) as JsonObject).int("NumDistinctPlayers") }.getOrNull()
    }

    /** The files RetroAchievements supports for [gameId] (not cached: asked for when a hack is made). */
    fun supportedFiles(account: RaAccount, gameId: Int): List<RaSupportedFile> = api.supportedFiles(account, gameId)

    /** A console's games with achievements, as (ID, title); kept [GAME_LIST_MAX_AGE_MS], the cache offline. */
    fun gameList(account: RaAccount, consoleId: Int): List<Pair<Int, String>> {
        val file = File(dir, "games-$consoleId.json")
        val fresh = file.exists() && clock() - file.lastModified() <= GAME_LIST_MAX_AGE_MS
        if (!fresh) {
            try {
                val json = api.gameListJson(account, consoleId)
                if (RaApi.parseGameList(json).isNotEmpty()) write(file, json)
            } catch (e: RaAuthException) {
                throw e
            } catch (_: Exception) {
                // Offline: use the older list, if any.
            }
        }
        return runCatching { RaApi.parseGameList(file.readText()) }.getOrDefault(emptyList())
    }

    /** Forgets everything (signing out). */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun write(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** The game in the Web API's own shape, so the cache reads back with [RaApi.parseGame]. */
    private fun gameJson(g: RaGame): String = buildJsonObject {
        put("ID", g.id)
        put("NumDistinctPlayers", g.players)
        put("NumAchievements", g.progress.total)
        put("NumAwardedToUser", g.progress.awarded)
        put("NumAwardedToUserHardcore", g.progress.awardedHardcore)
        g.progress.award?.let { put("HighestAwardKind", it.apiName) }
        put("Achievements", buildJsonObject {
            for (a in g.achievements) put("${a.id}", buildJsonObject {
                put("ID", a.id); put("Title", a.title); put("Description", a.description); put("Points", a.points)
                a.type?.let { put("type", it) }
                put("DisplayOrder", a.displayOrder)
                if (a.earned) put("DateEarned", "1")
                if (a.earnedHardcore) put("DateEarnedHardcore", "1")
            })
        })
    }.toString()

    companion object {
        const val PROGRESS_MAX_AGE_MS = 3L * 60 * 1000
        const val GAME_MAX_AGE_MS = 10L * 60 * 1000
        const val PLAYERS_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val GAME_LIST_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
