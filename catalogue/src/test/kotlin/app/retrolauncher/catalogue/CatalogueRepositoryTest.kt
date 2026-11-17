package app.retrolauncher.catalogue

import app.retrolauncher.catalogue.net.CatalogueException
import app.retrolauncher.catalogue.net.CatalogueRepository
import app.retrolauncher.catalogue.net.HttpFetcher
import app.retrolauncher.catalogue.net.LoadedCatalogue.Source
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CatalogueRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://example.test/catalogue"

    /** A fake server whose files can be changed and which can be switched off. */
    private class FakeServer : HttpFetcher {
        val files = mutableMapOf<String, ByteArray>()
        val requests = mutableListOf<String>()
        var offline = false

        override fun get(url: String): ByteArray {
            requests += url.substringAfterLast('/')
            if (offline) throw IOException("offline")
            return files[url.substringAfterLast('/')] ?: throw IOException("404 $url")
        }

        fun publish(version: Int, platformName: String, format: Int = 1, corruptSha: Boolean = false) {
            val data = """{"format":1,"platforms":[{"id":"gba","name":"$platformName","players":[]}]}""".encodeToByteArray()
            val sha = if (corruptSha) "0".repeat(64) else CatalogueRepository.sha256(data)
            files["android.json"] = data
            files["manifest.json"] =
                """{"format":$format,"version":$version,"file":"android.json","size":${data.size},"sha256":"$sha"}""".encodeToByteArray()
        }
    }

    private val server = FakeServer()
    private var now = 1_000_000L
    private val hour = 60 * 60 * 1000L

    private fun TestScope.repo(dir: File = File(tmp.root, "cache")) = CatalogueRepository(
        cacheDir = dir,
        fetcher = server,
        baseUrl = base,
        minCheckIntervalMs = 6 * hour,
        clock = { now },
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `first load downloads, then cache is used within the interval`() = runTest {
        server.publish(1, "GBA v1")
        val first = repo().load()
        assertEquals(Source.NETWORK, first.source)
        assertEquals("GBA v1", first.catalogue.platform("gba")!!.name)
        assertEquals(listOf("manifest.json", "android.json"), server.requests)

        // A new repository instance reads from disk without touching the network.
        server.requests.clear()
        now += hour
        val second = repo().load()
        assertEquals(Source.CACHE, second.source)
        assertEquals("GBA v1", second.catalogue.platform("gba")!!.name)
        assertEquals(emptyList<String>(), server.requests)
    }

    @Test
    fun `after the interval only the manifest is fetched when nothing changed`() = runTest {
        server.publish(1, "GBA v1")
        repo().load()
        server.requests.clear()
        now += 7 * hour
        val again = repo().load()
        assertEquals(Source.CACHE, again.source)
        assertEquals(listOf("manifest.json"), server.requests)
    }

    @Test
    fun `a new sha256 downloads again`() = runTest {
        server.publish(1, "GBA v1")
        val r = repo()
        r.load()
        server.publish(2, "GBA v2")
        val updated = r.load(forceRefresh = true)
        assertEquals(Source.NETWORK, updated.source)
        assertEquals(2, updated.manifest.version)
        assertEquals("GBA v2", updated.catalogue.platform("gba")!!.name)
    }

    @Test
    fun `bad sha256 keeps the old cache and reports the error`() = runTest {
        server.publish(1, "GBA v1")
        repo().load()
        server.publish(2, "GBA v2", corruptSha = true)
        val result = repo().load(forceRefresh = true)
        assertEquals("GBA v1", result.catalogue.platform("gba")!!.name)
        assertTrue(result.refreshError is CatalogueException)
    }

    @Test
    fun `unsupported format keeps the old cache`() = runTest {
        server.publish(1, "GBA v1")
        repo().load()
        server.publish(2, "GBA v2", format = 2)
        val result = repo().load(forceRefresh = true)
        assertEquals(1, result.manifest.version)
        assertNotNull(result.refreshError)
    }

    @Test
    fun `offline falls back to cache, or throws without one`() = runTest {
        server.offline = true
        try {
            repo().load()
            fail("expected an exception")
        } catch (_: CatalogueException) {
        }

        server.offline = false
        server.publish(1, "GBA v1")
        repo().load()
        server.offline = true
        val result = repo().load(forceRefresh = true)
        assertEquals(Source.CACHE, result.source)
        assertNotNull(result.refreshError)
    }

    @Test
    fun `a corrupted cache file is ignored`() = runTest {
        server.publish(1, "GBA v1")
        val dir = File(tmp.root, "cache")
        repo(dir).load()
        File(dir, "catalogue.json").writeText("{garbage")
        assertNull(repo(dir).loadCached())
        server.requests.clear()
        assertEquals(Source.NETWORK, repo(dir).load().source)
    }
}
