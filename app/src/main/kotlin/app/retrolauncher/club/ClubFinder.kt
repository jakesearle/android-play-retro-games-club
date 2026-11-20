package app.retrolauncher.club

import app.retrolauncher.library.Game
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLDecoder
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * RetroAchievements' list of every known ROM hash for a console
 * (`dorequest.php?r=hashlibrary&c=<console>`, no account needed), kept for three days.
 */
class HashLibrary(
    private val dir: File,
    private val download: (String) -> String = ::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val parsed = mutableMapOf<Int, Map<String, Int>>()

    /** The hashes RA knows for [gameId], or null when the list is unreachable and not cached. */
    @Synchronized
    fun hashesFor(consoleId: Int, gameId: Int): Set<String>? {
        val list = parsed[consoleId] ?: load(consoleId)?.also { parsed[consoleId] = it } ?: return null
        return list.filterValues { it == gameId }.keys
    }

    @Synchronized
    fun forget() = parsed.clear()

    private fun load(consoleId: Int): Map<String, Int>? {
        val file = File(dir, "$consoleId.json")
        if (!file.exists() || clock() - file.lastModified() > MAX_AGE_MS) {
            try {
                val body = download("https://retroachievements.org/dorequest.php?r=hashlibrary&c=$consoleId")
                if (body.contains("\"MD5List\"")) {
                    dir.mkdirs()
                    val tmp = File(dir, "$consoleId.json.tmp")
                    tmp.writeText(body)
                    tmp.renameTo(file)
                }
            } catch (_: IOException) {
                // Offline: an old list is better than none.
            }
        }
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            (root["MD5List"] as? JsonObject)?.entries
                ?.mapNotNull { (hash, id) -> id.jsonPrimitive.intOrNull?.let { hash.lowercase() to it } }
                ?.toMap()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

        fun httpGet(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "RetroLauncher")
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
                return c.inputStream.use { it.readBytes().decodeToString() }
            } finally {
                c.disconnect()
            }
        }
    }
}

/**
 * Hashes of local ROMs, by console, file key, size and modified time, so a ROM is only read
 * again when it changes. One tab-separated line per hash, appended; the last line wins.
 */
class HashCache(private val file: File) {
    private val entries = mutableMapOf<String, String>()
    private var loaded = false

    private fun id(consoleId: Int, game: Game) = "$consoleId\t${game.size}\t${game.lastModified}\t${game.key}"

    @Synchronized
    fun get(consoleId: Int, game: Game): String? {
        load()
        return entries[id(consoleId, game)]
    }

    @Synchronized
    fun put(consoleId: Int, game: Game, hash: String) {
        load()
        val key = id(consoleId, game)
        if (entries[key] == hash) return
        entries[key] = hash
        file.parentFile?.mkdirs()
        file.appendText("$consoleId\t${game.size}\t${game.lastModified}\t$hash\t${game.key.replace('\n', ' ')}\n")
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        file.forEachLine { line ->
            val parts = line.split('\t', limit = 5)
            if (parts.size == 5) entries["${parts[0]}\t${parts[1]}\t${parts[2]}\t${parts[4]}"] = parts[3]
        }
    }
}

/**
 * The ROM last played for each RA game. While the file is unchanged the finder goes straight
 * to it, with no hashing or hash list, so it works offline.
 */
class RomLinks(private val file: File) {
    @Serializable
    data class Link(
        val key: String,
        val platformId: String,
        val fileName: String,
        val path: String? = null,
        val uri: String? = null,
        val size: Long = 0,
        val lastModified: Long = 0,
        /** Picked by hand although RetroAchievements doesn't recognise it ("Use anyway"). */
        val unverified: Boolean = false,
    ) {
        fun toGame() = Game(key, platformId, fileName, app.retrolauncher.library.GameTitles.clean(fileName), path, uri, size, lastModified)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var links: MutableMap<String, Link>? = null

    @Synchronized
    private fun all(): MutableMap<String, Link> = links ?: try {
        json.decodeFromString<Map<String, Link>>(file.readText()).toMutableMap()
    } catch (_: Exception) {
        mutableMapOf()
    }.also { links = it }

    @Synchronized
    fun get(gameId: Int): Link? = all()[gameId.toString()]

    @Synchronized
    fun save(gameId: Int, game: Game, unverified: Boolean = false) {
        all()[gameId.toString()] = Link(game.key, game.platformId, game.fileName, game.path, game.uri, game.size, game.lastModified, unverified)
        write()
    }

    @Synchronized
    fun count(): Int = all().size

    /** Forgets every link, so each game is searched for again. Returns how many there were. */
    @Synchronized
    fun clear(): Int {
        val n = all().size
        all().clear()
        write()
        return n
    }

    private fun write() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(MapSerializer(String.serializer(), Link.serializer()), all()))
        tmp.renameTo(file)
    }
}

/** What a search for a club pick among the user's ROMs found. */
sealed interface FindResult {
    /** Every copy that is this game; usually one. The first is the best guess. */
    data class Found(val matches: List<Game>, val linked: Boolean) : FindResult
    /** The pick's title doesn't name a console we know, or it has no RA link. */
    data class UnknownGame(val label: String?) : FindResult
    /** No ROM folder holds games for this console. */
    data class NoGames(val console: RaConsoles.Console) : FindResult
    /** Searched every candidate; none is a version RA recognises. */
    data class NotFound(val console: RaConsoles.Console, val searched: Int) : FindResult
    /** RA's hash list is unreachable and was never saved. */
    data class NoHashList(val console: RaConsoles.Console) : FindResult
    /** Disc-based console: can't be matched by hash, so the user picks the file. */
    data class Unsupported(val console: RaConsoles.Console) : FindResult
}

/**
 * Finds a club pick among the user's ROMs, as the NextUI Pak does:
 * 1. A saved link for the game, if the file is still there unchanged.
 * 2. Otherwise RA's hash list for the console, and the hashes of every candidate ROM (cached),
 *    best-named first. A misnamed file still matches; a different dump of the right game doesn't.
 */
class ClubFinder(
    private val hashLibrary: HashLibrary,
    private val hashCache: HashCache,
    private val links: RomLinks,
    /** Opens a game's file for hashing. */
    private val open: (Game) -> RomFile?,
    /** Current size and modified time of a linked file outside the scanned folders, or null if gone. */
    private val stat: (Game) -> Pair<Long, Long>? = { null },
) {
    /**
     * @param candidates games in ROM folders for the pick's console.
     * @param cancelled checked between files.
     * @param progress told (files looked at, files in all) after each file that had to be read,
     *   so a slow first search can show how far it's got; files with a cached hash aren't reported.
     */
    fun find(
        pick: ClubPick,
        candidates: List<Game>,
        cancelled: () -> Boolean = { false },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FindResult {
        val console = pick.console ?: return FindResult.UnknownGame(RaConsoles.labelOf(pick.title))
        val gameId = pick.gameId
        if (gameId <= 0) return FindResult.UnknownGame(console.label)

        linked(gameId, candidates)?.let { return FindResult.Found(listOf(it), linked = true) }

        if (!RaHash.supports(console.id)) return FindResult.Unsupported(console)
        if (candidates.isEmpty()) return FindResult.NoGames(console)
        val wanted = hashLibrary.hashesFor(console.id, gameId) ?: return FindResult.NoHashList(console)

        val titleWords = words(pick.displayTitle)
        val ordered = discAware(candidates.filterNot { RaHash.isIgnored(it.fileName) })
            .sortedWith(compareBy<Game> { it.extension == "m3u" }.thenByDescending { score(titleWords, it.fileName) })
        val matches = mutableListOf<Game>()
        for ((index, game) in ordered.withIndex()) {
            if (cancelled()) break
            // A playlist only counts if none of the discs it lists matched on their own.
            if (game.extension == "m3u" && matches.isNotEmpty()) continue
            val hash = hashCache.get(console.id, game) ?: try {
                open(game)?.let { RaHash.hash(console.id, it) }?.also { hashCache.put(console.id, game, it) }
            } catch (_: IOException) {
                null
            }.also { progress(index + 1, ordered.size) }
            if (hash != null && hash in wanted) matches += game
        }
        return if (matches.isNotEmpty()) FindResult.Found(matches, linked = false) else FindResult.NotFound(console, ordered.size)
    }

    /** The linked ROM, if it's unchanged: from the scan, or (for a file picked by hand) by stat. */
    private fun linked(gameId: Int, candidates: List<Game>): Game? {
        val link = links.get(gameId) ?: return null
        candidates.firstOrNull { it.key == link.key }?.let { game ->
            return game.takeIf { it.size == link.size && it.lastModified == link.lastModified }
        }
        val game = link.toGame()
        val (size, modified) = stat(game) ?: return null
        return game.takeIf { size == link.size && modified == link.lastModified }
    }

    /** Whether a file the player picked by hand is this game. */
    sealed interface Verdict {
        data object Recognized : Verdict
        data object NotRecognized : Verdict
        /** RA's list is unreachable and was never saved, so the file can't be checked. */
        data object NoHashList : Verdict
        /** Disc-based console: there's no hash to check, so the player's choice is trusted. */
        data object NotCheckable : Verdict
        data class Unreadable(val reason: String?) : Verdict
    }

    companion object {
        private val Game.extension get() = fileName.substringAfterLast('.', "").lowercase()
        private fun Game.folder() = (path ?: URLDecoder.decode(uri ?: "", "UTF-8")).substringBeforeLast('/')

        /** BIN/IMG tracks in a folder with a CUE sheet are part of that disc, not separate games. */
        fun discAware(candidates: List<Game>): List<Game> {
            val cueFolders = candidates.filter { it.extension == "cue" }.map { it.folder() }.toSet()
            return candidates.filterNot { (it.extension == "bin" || it.extension == "img") && it.folder() in cueFolders }
        }

        /** Lowercase words, skipping anything in () or [] (region tags, console labels). */
        fun words(s: String): Set<String> =
            s.replace(Regex("""\.[A-Za-z0-9]{1,5}$"""), "") // a file extension, not "Dr. Mario"
                .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
                .lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() }
                .toSet()

        /** How many of the title's words the file name shares, so likely files are hashed first. */
        fun score(titleWords: Set<String>, fileName: String): Int = words(fileName).count { it in titleWords }

        fun verifyChosen(hashLibrary: HashLibrary, console: RaConsoles.Console, gameId: Int, file: RomFile): Verdict {
            if (!RaHash.supports(console.id)) return Verdict.NotCheckable
            val wanted = hashLibrary.hashesFor(console.id, gameId) ?: return Verdict.NoHashList
            val hash = try {
                RaHash.hash(console.id, file)
            } catch (e: IOException) {
                return Verdict.Unreadable(e.message)
            }
            return if (hash != null && hash in wanted) Verdict.Recognized else Verdict.NotRecognized
        }

        /**
         * Which copy to launch when several match: the one launched before, if it's among them;
         * otherwise null, meaning the player should be asked.
         */
        fun choose(result: FindResult.Found): Game? = when {
            result.matches.size == 1 -> result.matches.single()
            result.linked -> result.matches.first()
            else -> null
        }
    }
}
