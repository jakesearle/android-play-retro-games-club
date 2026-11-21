package app.retrolauncher.ra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A RetroAchievements account for the Web API: the username and the user's Web API key. */
data class RaAccount(val user: String, val key: String) {
    override fun toString() = "RaAccount($user)" // never the key, so it can't end up in a log
}

/** The highest award RetroAchievements gives for a game, as the Web API names it. */
enum class RaAward(val apiName: String, val label: String) {
    BEATEN_SOFTCORE("beaten-softcore", "Beaten (softcore)"),
    BEATEN_HARDCORE("beaten-hardcore", "Beaten"),
    COMPLETED("completed", "Completed (softcore)"),
    MASTERED("mastered", "Mastered");

    companion object {
        fun parse(name: String?): RaAward? = entries.firstOrNull { it.apiName == name }
    }
}

/**
 * Your progress on one game. Counts are hardcore, like the club's leaderboard; if you only have
 * softcore unlocks, those are shown instead, marked [softcore].
 */
data class RaProgress(
    val gameId: Int,
    val awarded: Int,
    val awardedHardcore: Int,
    /** The game's achievement count. */
    val total: Int,
    val award: RaAward? = null,
) {
    val softcore: Boolean get() = awardedHardcore == 0 && awarded > 0
    /** The count to show: hardcore, else softcore. */
    val shown: Int get() = if (softcore) awarded else awardedHardcore
    val started: Boolean get() = awarded > 0 || awardedHardcore > 0 || award != null
    val beaten: Boolean get() = award != null
    val mastered: Boolean get() = award == RaAward.MASTERED
}

/** One achievement, with whether you have it. */
data class RaAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    /** "progression", "win_condition", "missable", or null. */
    val type: String?,
    val displayOrder: Int,
    val earned: Boolean,
    val earnedHardcore: Boolean,
)

/** A game's achievements, how many people play it, and your progress. */
data class RaGame(
    val id: Int,
    val players: Int,
    val achievements: List<RaAchievement>,
    val progress: RaProgress,
) {
    /**
     * The next achievement towards beating the game: the first locked progression achievement
     * in the set's order, else the first locked win condition. "Locked" follows the progress
     * shown: hardcore, or softcore when that's all you have.
     */
    fun nextToBeat(): RaAchievement? {
        val softcore = progress.softcore
        val locked = achievements.filter { if (softcore) !it.earned else !it.earnedHardcore }.sortedBy { it.displayOrder }
        return locked.firstOrNull { it.type == "progression" } ?: locked.firstOrNull { it.type == "win_condition" }
    }
}

/** A file RetroAchievements supports for a game: its hash, its name, and its patch, if it's a hack. */
data class RaSupportedFile(val md5: String, val name: String, val patchUrl: String?)

/** The key or username was refused (HTTP 401 or 403). */
class RaAuthException : IOException("RetroAchievements refused the username or Web API key")

/**
 * RetroAchievements' Web API (https://api-docs.retroachievements.org). Every request carries
 * the username and Web API key; the key is a secret, so URLs are never logged or shown.
 */
class RaApi(private val get: (String) -> Pair<Int, String> = ::httpGet) {

    /**
     * Your progress on every game you've played (API_GetUserCompletionProgress), by RA game ID,
     * fetched 500 at a time. Games you've never played aren't in it.
     */
    fun completion(account: RaAccount): Map<Int, RaProgress> {
        val out = mutableMapOf<Int, RaProgress>()
        var offset = 0
        while (true) {
            val page = request("API_GetUserCompletionProgress", account, "u" to account.user, "c" to "$PAGE", "o" to "$offset")
            val results = page["Results"] as? JsonArray ?: break
            for (r in results) {
                val o = r as? JsonObject ?: continue
                val id = o.int("GameID") ?: continue
                out[id] = RaProgress(
                    gameId = id,
                    awarded = o.int("NumAwarded") ?: 0,
                    awardedHardcore = o.int("NumAwardedHardcore") ?: 0,
                    total = o.int("MaxPossible") ?: 0,
                    award = RaAward.parse(o.str("HighestAwardKind")),
                )
            }
            offset += results.size
            val total = page.int("Total") ?: offset
            if (results.isEmpty() || offset >= total) break
        }
        return out
    }

    /** One game's achievements, player count and your progress (API_GetGameInfoAndUserProgress). */
    fun game(account: RaAccount, gameId: Int): RaGame =
        parseGame(request("API_GetGameInfoAndUserProgress", account, "u" to account.user, "g" to "$gameId", "a" to "1"))

    /**
     * The files RetroAchievements supports for a game (API_GetGameHashes): for a romhack, each
     * patched ROM's name matches its patch's name in RAPatches, which says which patch to use.
     */
    fun supportedFiles(account: RaAccount, gameId: Int): List<RaSupportedFile> {
        val results = request("API_GetGameHashes", account, "i" to "$gameId")["Results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            RaSupportedFile(o.str("MD5") ?: return@mapNotNull null, o.str("Name").orEmpty(), o.str("PatchUrl"))
        }
    }

    /** A console's games that have achievements, as (RA game ID, title) (API_GetGameList with f=1), as raw JSON. */
    fun gameListJson(account: RaAccount, consoleId: Int): String {
        val query = listOf("i" to "$consoleId", "f" to "1", "y" to account.key).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val (status, body) = get("$BASE/API_GetGameList.php?$query")
        if (status == 401 || status == 403) throw RaAuthException()
        if (status !in 200..299) throw IOException("RetroAchievements answered $status")
        return body
    }

    private fun request(endpoint: String, account: RaAccount, vararg params: Pair<String, String>): JsonObject {
        val query = (params.toList() + ("y" to account.key)).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val (status, body) = get("$BASE/$endpoint.php?$query")
        if (status == 401 || status == 403) throw RaAuthException()
        if (status !in 200..299) throw IOException("RetroAchievements answered $status")
        return Json.parseToJsonElement(body) as? JsonObject ?: throw IOException("RetroAchievements sent something unexpected")
    }

    companion object {
        private const val BASE = "https://retroachievements.org/API"
        private const val PAGE = 500

        /** Where you find your Web API key (the Keys section of your account settings). */
        const val KEY_PAGE = "https://retroachievements.org/controlpanel.php"

        /** API_GetGameList's answer → (game ID, title) pairs. */
        fun parseGameList(json: String): List<Pair<Int, String>> =
            (Json.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                (o.int("ID") ?: return@mapNotNull null) to o.str("Title").orEmpty()
            }

        fun parseGame(o: JsonObject): RaGame {
            val id = o.int("ID") ?: throw IOException("No such game")
            val achievements = (o["Achievements"] as? JsonObject)?.values.orEmpty().mapNotNull { e ->
                val a = e as? JsonObject ?: return@mapNotNull null
                RaAchievement(
                    id = a.int("ID") ?: return@mapNotNull null,
                    title = a.str("Title").orEmpty(),
                    description = a.str("Description").orEmpty(),
                    points = a.int("Points") ?: 0,
                    type = a.str("type")?.ifEmpty { null },
                    displayOrder = a.int("DisplayOrder") ?: 0,
                    earned = !a.str("DateEarned").isNullOrEmpty(),
                    earnedHardcore = !a.str("DateEarnedHardcore").isNullOrEmpty(),
                )
            }
            return RaGame(
                id = id,
                players = o.int("NumDistinctPlayers") ?: 0,
                achievements = achievements,
                progress = RaProgress(
                    gameId = id,
                    awarded = o.int("NumAwardedToUser") ?: achievements.count { it.earned },
                    awardedHardcore = o.int("NumAwardedToUserHardcore") ?: achievements.count { it.earnedHardcore },
                    total = o.int("NumAchievements") ?: achievements.size,
                    award = RaAward.parse(o.str("HighestAwardKind")),
                ),
            )
        }

        fun httpGet(url: String): Pair<Int, String> {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "RetroLauncher")
                val status = c.responseCode
                val body = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                return status to body
            } finally {
                c.disconnect()
            }
        }
    }
}

private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
internal fun JsonObject.int(key: String): Int? = prim(key)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }
internal fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it !is JsonNull }?.content
