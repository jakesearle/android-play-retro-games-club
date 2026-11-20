package app.retrolauncher.club

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant

/** HTTP GET with an optional ETag, so tests can swap in a fake. */
fun interface ClubHttp {
    data class Response(val notModified: Boolean, val body: String, val etag: String?)

    @Throws(IOException::class)
    fun get(url: String, etag: String?): Response
}

class UrlConnectionClubHttp : ClubHttp {
    override fun get(url: String, etag: String?): ClubHttp.Response {
        // Wi-Fi is often still waking when the launcher opens: retry a timeout once.
        return try {
            request(url, etag)
        } catch (_: SocketTimeoutException) {
            request(url, etag)
        }
    }

    private fun request(url: String, etag: String?): ClubHttp.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RetroLauncher")
            if (!etag.isNullOrEmpty()) c.setRequestProperty("If-None-Match", etag)
            return when (val code = c.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> ClubHttp.Response(true, "", c.getHeaderField("ETag") ?: etag)
                in 200..299 -> ClubHttp.Response(false, c.inputStream.use { it.readBytes().decodeToString() }, c.getHeaderField("ETag"))
                else -> throw IOException("HTTP $code for $url")
            }
        } finally {
            c.disconnect()
        }
    }
}

sealed interface ClubResult {
    /**
     * @param fromCache the site couldn't be reached and this is the saved page.
     * @param fetchedAt when the site last confirmed the page.
     * @param past the club's earlier picks, from the page's Past Winners ([ClubPage.parsePast]).
     */
    data class Ok(
        val picks: List<ClubPick>,
        val fromCache: Boolean,
        val fetchedAt: Instant,
        val past: List<ClubPick> = emptyList(),
    ) : ClubResult
    data class NetworkError(val error: Throwable) : ClubResult
    /** Downloaded, but no picks were found: the page's markup changed. */
    data object ParseError : ClubResult
}

/**
 * Loads the club's picks like the NextUI Pak: from the saved page while it is fresh (under 6
 * hours old and before the club's next announced change), otherwise from the site, sending the
 * saved page's ETag so an unchanged page costs a tiny "not modified". Falls back to the saved
 * page when offline. Only pages that parse are saved.
 */
class ClubRepository(
    private val dir: File,
    private val http: ClubHttp = UrlConnectionClubHttp(),
    private val clock: () -> Instant = Instant::now,
) {
    private val htmlFile get() = File(dir, "club.html")
    private val metaFile get() = File(dir, "club.meta")

    private data class Meta(val fetched: Instant, val etag: String?)

    /** Blocking; call from a background thread. [force] always asks the site (Refresh). */
    fun load(force: Boolean = false): ClubResult {
        val now = clock()
        val meta = readMeta()
        val saved = readSaved()

        if (!force && meta != null && saved != null && saved.second.isNotEmpty()) {
            val (html, picks) = saved
            if (ClubPage.cacheIsFresh(now, meta.fetched, ClubPage.nextChange(html, meta.fetched))) {
                return ClubResult.Ok(picks, fromCache = false, fetchedAt = meta.fetched, past = ClubPage.parsePast(html))
            }
        }

        val canRevalidate = saved != null && saved.second.isNotEmpty()
        return try {
            var response = http.get(ClubPage.URL, if (canRevalidate) meta?.etag else null)
            if (response.notModified) {
                if (canRevalidate) {
                    writeMeta(Meta(now, response.etag ?: meta?.etag))
                    return ClubResult.Ok(saved!!.second, fromCache = false, fetchedAt = now, past = ClubPage.parsePast(saved.first))
                }
                response = http.get(ClubPage.URL, null)
            }
            val picks = ClubPage.parse(response.body)
            if (picks.isEmpty()) {
                ClubResult.ParseError
            } else {
                dir.mkdirs()
                atomicWrite(htmlFile, response.body)
                writeMeta(Meta(now, response.etag))
                ClubResult.Ok(picks, fromCache = false, fetchedAt = now, past = ClubPage.parsePast(response.body))
            }
        } catch (e: IOException) {
            if (saved != null && saved.second.isNotEmpty()) {
                ClubResult.Ok(
                    saved.second,
                    fromCache = true,
                    fetchedAt = meta?.fetched ?: Instant.ofEpochMilli(htmlFile.lastModified()),
                    past = ClubPage.parsePast(saved.first),
                )
            } else {
                ClubResult.NetworkError(e)
            }
        }
    }

    private fun readSaved(): Pair<String, List<ClubPick>>? = try {
        htmlFile.takeIf { it.exists() }?.readText()?.let { it to ClubPage.parse(it) }
    } catch (_: Exception) {
        null
    }

    private fun readMeta(): Meta? = try {
        val lines = metaFile.readLines().associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
        val fetched = lines["fetched"]?.toLongOrNull() ?: return null
        Meta(Instant.ofEpochMilli(fetched), lines["etag"]?.takeIf { it.isNotEmpty() })
    } catch (_: Exception) {
        null
    }

    private fun writeMeta(meta: Meta) {
        dir.mkdirs()
        atomicWrite(metaFile, "fetched ${meta.fetched.toEpochMilli()}\netag ${meta.etag.orEmpty()}\n")
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
