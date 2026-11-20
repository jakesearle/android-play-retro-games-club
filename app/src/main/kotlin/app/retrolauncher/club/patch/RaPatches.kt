package app.retrolauncher.club.patch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipFile

/**
 * A hack's patch archive in RetroAchievements' RAPatches repository, which files each one as
 * `<System>/<Type>/<Base game>/<RA game ID>-<Name>.zip` (or .7z).
 */
data class RaPatch(val gameId: Int, val path: String) {
    /** The base game's name, from its folder ("Super Mario World", "Pokemon Emerald"). */
    val baseTitle: String get() = path.split('/').let { if (it.size >= 2) it[it.size - 2] else "" }
    val fileName: String get() = path.substringAfterLast('/')
}

/** A patch file inside an archive. */
class PatchFile(val name: String, val format: PatchFormat, val bytes: ByteArray)

/**
 * RAPatches: its file list, fetched from GitHub's API at most once a day and kept, gives the
 * patch archive for a RetroAchievements game ID; archives are downloaded when a hack is made.
 * https://github.com/RetroAchievements/RAPatches
 */
class RaPatchesRepository(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (String) -> InputStream = ::openUrl,
) {
    private val listFile get() = File(dir, "rapatches-tree.json")
    @Volatile private var index: Map<Int, RaPatch>? = null

    /** The patch archive for [gameId], or null; from the cached list (fetched when a day old). */
    fun find(gameId: Int): RaPatch? = load()[gameId]

    /** Every hack with a patch, by RA game ID; empty if the list has never been fetched. */
    @Synchronized
    fun load(): Map<Int, RaPatch> {
        val stale = !listFile.exists() || clock() - listFile.lastModified() > MAX_AGE_MS
        if (stale) {
            try {
                val body = fetch(TREE_URL).use { it.readBytes() }.decodeToString()
                if (parse(body).isNotEmpty()) {
                    dir.mkdirs()
                    val tmp = File(dir, listFile.name + ".tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(listFile)) { listFile.delete(); tmp.renameTo(listFile) }
                    index = null
                }
            } catch (_: IOException) {
                // Offline: an older list is fine.
            }
        }
        index?.let { return it }
        return runCatching { parse(listFile.readText()) }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) index = it }
    }

    /** Downloads [patch]'s archive into the cache and returns its file (the caller deletes it). */
    fun download(patch: RaPatch): File {
        dir.mkdirs()
        val file = File(dir, "archive-" + patch.fileName)
        val tmp = File(dir, file.name + ".part")
        val url = RAW_URL + patch.path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        fetch(url).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        return file
    }

    companion object {
        const val TREE_URL = "https://api.github.com/repos/RetroAchievements/RAPatches/git/trees/main?recursive=1"
        const val RAW_URL = "https://github.com/RetroAchievements/RAPatches/raw/refs/heads/main/"
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private val ARCHIVE = Regex("""(?:^|/)(\d+)-[^/]+\.(?:zip|7z)$""", RegexOption.IGNORE_CASE)

        /** GitHub's recursive tree listing → patch archives by RA game ID. */
        fun parse(json: String): Map<Int, RaPatch> {
            val tree = (Json.parseToJsonElement(json) as? JsonObject)?.get("tree") as? JsonArray ?: return emptyMap()
            val out = HashMap<Int, RaPatch>()
            for (e in tree) {
                val o = e as? JsonObject ?: continue
                if ((o["type"] as? JsonPrimitive)?.content != "blob") continue
                val path = (o["path"] as? JsonPrimitive)?.content ?: continue
                val id = ARCHIVE.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                out.putIfAbsent(id, RaPatch(id, path))
            }
            return out
        }

        /**
         * The patch files in an archive, newest version first (by the version numbers in their
         * names), so the latest version RetroAchievements supports is the one kept.
         */
        fun patches(archive: File): List<PatchFile> {
            val found = mutableListOf<PatchFile>()
            if (archive.name.endsWith(".7z", ignoreCase = true)) {
                SevenZFile.builder().setFile(archive).get().use { z ->
                    for (entry in z.entries) {
                        if (entry.isDirectory) continue
                        val format = PatchFormat.of(entry.name) ?: continue
                        found += PatchFile(entry.name.substringAfterLast('/'), format, z.getInputStream(entry).use { it.readBytes() })
                    }
                }
            } else {
                ZipFile(archive).use { z ->
                    for (entry in z.entries()) {
                        if (entry.isDirectory) continue
                        val format = PatchFormat.of(entry.name) ?: continue
                        found += PatchFile(entry.name.substringAfterLast('/'), format, z.getInputStream(entry).use { it.readBytes() })
                    }
                }
            }
            return found.sortedWith(compareByDescending<PatchFile, String>(NaturalOrder) { it.name })
        }

        private fun openUrl(url: String): InputStream {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "PRGC-Launcher")
            if (c.responseCode !in 200..299) { c.disconnect(); throw IOException("HTTP ${c.responseCode} for $url") }
            return c.inputStream
        }
    }
}

/** Compares names with their numbers as numbers ("v4.1.0" after "v4.0.3", "v10" after "v9"). */
object NaturalOrder : Comparator<String> {
    private val PART = Regex("""\d+|\D+""")
    override fun compare(a: String, b: String): Int {
        val x = PART.findAll(a.lowercase()).map { it.value }.toList()
        val y = PART.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}

/**
 * Picks likely base ROMs from a console's games by the RAPatches folder name, which is the base
 * game's title, sometimes abbreviated ("LoZ - Ocarina of Time", "SMW").
 */
object BaseRoms {
    private val STOP = setOf("the", "of", "a", "an", "and", "version", "edition")
    private val EXPAND = mapOf(
        "loz" to listOf("legend", "zelda"), "smw" to listOf("super", "mario", "world"),
        "smb" to listOf("super", "mario", "bros"), "smb3" to listOf("super", "mario", "bros", "3"),
        "sor2" to listOf("streets", "rage", "2"), "fe" to listOf("fire", "emblem"),
        "bk" to listOf("banjo", "kazooie"), "aos" to listOf("aria", "sorrow"), "pokemon" to listOf("pokemon"),
    )

    private fun words(s: String): Set<String> =
        s.replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""\.[A-Za-z0-9]{1,5}$"""), "")
            .lowercase()
            .replace("é", "e")
            .split(Regex("[^a-z0-9]+"))
            .flatMap { EXPAND[it] ?: listOf(it) }
            // Single letters (the "s" of "Kirby's") say nothing; single digits ("2") do.
            .filter { it.isNotEmpty() && it !in STOP && (it.length > 1 || it[0].isDigit()) }
            .toSet()

    /** How well [fileName] matches [baseTitle]: the share of the title's words it has (0 to 1). */
    fun score(baseTitle: String, fileName: String): Double {
        val want = words(baseTitle)
        if (want.isEmpty()) return 0.0
        val have = words(fileName)
        return want.count { it in have }.toDouble() / want.size
    }

    /** [games] that look like [baseTitle]'s ROM, best first; on a tie, the name with fewer extra words. */
    fun <T> candidates(baseTitle: String, games: List<T>, name: (T) -> String): List<T> {
        val want = words(baseTitle)
        return games.map { Triple(it, score(baseTitle, name(it)), (words(name(it)) - want).size) }
            .filter { it.second >= 0.6 }
            .sortedWith(compareByDescending<Triple<T, Double, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }
}
