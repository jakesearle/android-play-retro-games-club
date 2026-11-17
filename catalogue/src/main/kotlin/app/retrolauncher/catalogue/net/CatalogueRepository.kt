package app.retrolauncher.catalogue.net

import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.model.CatalogueJson
import app.retrolauncher.catalogue.model.CatalogueManifest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Minimal HTTP abstraction, so tests can swap in a fake. */
fun interface HttpFetcher {
    @Throws(IOException::class)
    fun get(url: String): ByteArray
}

class UrlConnectionFetcher(private val timeoutMs: Int = 15_000) : HttpFetcher {
    override fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "RetroLauncher")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

class CatalogueException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class LoadedCatalogue(
    val catalogue: Catalogue,
    val manifest: CatalogueManifest,
    val source: Source,
    /** Set when a network refresh failed and the cached copy was used instead. */
    val refreshError: Throwable? = null,
) {
    enum class Source { NETWORK, CACHE }
}

/**
 * Downloads the Cocoon catalogue at runtime and caches it in [cacheDir]. The catalogue isn't
 * bundled because the upstream repo has no license.
 *
 * 1. If the last check was less than [minCheckIntervalMs] ago, use the cache.
 * 2. Otherwise fetch `manifest.json`. If its sha256 matches the cached file, keep the cache.
 * 3. Otherwise download the data file, check its size and sha256, and replace the cache
 *    atomically.
 * Network or validation errors fall back to the cache, and only throw if there is no cache.
 */
class CatalogueRepository(
    private val cacheDir: File,
    private val fetcher: HttpFetcher = UrlConnectionFetcher(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val minCheckIntervalMs: Long = 6 * 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val manifestFile get() = File(cacheDir, "manifest.json")
    private val dataFile get() = File(cacheDir, "catalogue.json")
    private val lastCheckFile get() = File(cacheDir, "last-check")

    private val mutex = Mutex()
    private var memory: LoadedCatalogue? = null

    /** Returns the catalogue, refreshing from the network when due (or always if [forceRefresh]). */
    suspend fun load(forceRefresh: Boolean = false): LoadedCatalogue = mutex.withLock {
        withContext(ioDispatcher) {
            val cached = memory ?: readCache()
            val due = forceRefresh || cached == null || clock() - lastCheck() >= minCheckIntervalMs
            val result = if (!due) cached!! else refresh(cached)
            memory = result
            result
        }
    }

    /** The cached catalogue without any network access, or null. Useful for a fast first frame. */
    suspend fun loadCached(): LoadedCatalogue? = mutex.withLock {
        withContext(ioDispatcher) { (memory ?: readCache())?.also { memory = it } }
    }

    private fun refresh(cached: LoadedCatalogue?): LoadedCatalogue {
        return try {
            val manifest = CatalogueJson.decodeFromString<CatalogueManifest>(
                fetcher.get("$baseUrl/manifest.json").decodeToString()
            )
            if (manifest.format != SUPPORTED_FORMAT) {
                throw CatalogueException("Unsupported catalogue format ${manifest.format}")
            }
            if (cached != null && cached.manifest.sha256.equals(manifest.sha256, ignoreCase = true)) {
                markChecked()
                return cached.copy(source = LoadedCatalogue.Source.CACHE, refreshError = null)
            }
            require(manifest.file.matches(Regex("""[A-Za-z0-9._-]+""")) && ".." !in manifest.file) {
                "Bad catalogue file name ${manifest.file}"
            }
            val bytes = fetcher.get("$baseUrl/${manifest.file}")
            if (bytes.size.toLong() != manifest.size) {
                throw CatalogueException("Size mismatch: got ${bytes.size}, expected ${manifest.size}")
            }
            val sha = sha256(bytes)
            if (!sha.equals(manifest.sha256, ignoreCase = true)) {
                throw CatalogueException("sha256 mismatch: got $sha, expected ${manifest.sha256}")
            }
            val catalogue = Catalogue.parse(bytes.decodeToString())
            if (catalogue.data.format != SUPPORTED_FORMAT) {
                throw CatalogueException("Unsupported catalogue format ${catalogue.data.format}")
            }
            writeCache(manifest, bytes)
            markChecked()
            LoadedCatalogue(catalogue, manifest, LoadedCatalogue.Source.NETWORK)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            cached?.copy(source = LoadedCatalogue.Source.CACHE, refreshError = e)
                ?: throw (e as? CatalogueException ?: CatalogueException("Couldn't load the catalogue", e))
        }
    }

    private fun readCache(): LoadedCatalogue? = try {
        if (!manifestFile.exists() || !dataFile.exists()) {
            null
        } else {
            val manifest = CatalogueJson.decodeFromString<CatalogueManifest>(manifestFile.readText())
            val bytes = dataFile.readBytes()
            if (!sha256(bytes).equals(manifest.sha256, ignoreCase = true)) {
                null // Corrupt or partial cache: download again.
            } else {
                LoadedCatalogue(Catalogue.parse(bytes.decodeToString()), manifest, LoadedCatalogue.Source.CACHE)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun writeCache(manifest: CatalogueManifest, bytes: ByteArray) {
        cacheDir.mkdirs()
        // Write the data before the manifest, each through a temp file and rename, so a crash
        // never leaves a manifest pointing at the wrong data. readCache() also checks the sha256.
        atomicWrite(dataFile, bytes)
        atomicWrite(manifestFile, CatalogueJson.encodeToString(CatalogueManifest.serializer(), manifest).encodeToByteArray())
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Couldn't write $target")
        }
    }

    private fun lastCheck(): Long = try {
        lastCheckFile.readText().trim().toLong()
    } catch (_: Exception) {
        0L
    }

    private fun markChecked() {
        cacheDir.mkdirs()
        lastCheckFile.writeText(clock().toString())
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://raw.githubusercontent.com/KansoMirai/CocoonFE/catalogue"
        const val SUPPORTED_FORMAT = 1

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
