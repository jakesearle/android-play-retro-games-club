package app.retrolauncher.catalogue

import app.retrolauncher.catalogue.am.GameFile
import app.retrolauncher.catalogue.am.ResolveResult
import app.retrolauncher.catalogue.net.CatalogueRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Downloads the real catalogue and checks every entry. Opt-in, because it needs the network:
 *
 *     ./gradlew :catalogue:testDebugUnitTest -PcatalogueLive=true
 */
class LiveCatalogueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `every live entry parses, resolves and has a Java-compatible regex`() = runBlocking {
        assumeTrue("live catalogue test disabled", System.getProperty("catalogue.live") == "true")

        val loaded = CatalogueRepository(tmp.root).load()
        val catalogue = loaded.catalogue
        println("Live catalogue v${loaded.manifest.version}: ${catalogue.platforms.size} platforms, " +
            "${catalogue.platforms.sumOf { it.players.size }} players")
        assertTrue(catalogue.platforms.size >= 100)

        val problems = mutableListOf<String>()
        for (platform in catalogue.platforms) {
            platform.acceptedFilenameRegex?.let {
                if (Catalogue.compile(it) == null) problems += "${platform.id}: regex doesn't compile: $it"
            }
            for (player in platform.players) {
                val where = "${platform.id} / ${player.id}"
                player.acceptedFilenameRegex?.let {
                    if (Catalogue.compile(it) == null) problems += "$where: regex doesn't compile: $it"
                }
                val cmd = catalogue.command(player)
                cmd.warnings.forEach { problems += "$where: $it" }
                if (cmd.packageName == null) problems += "$where: no package"
                val game = GameFile(
                    path = "/storage/emulated/0/ROMs/x.bin",
                    uri = "content://a/b",
                    tags = cmd.tagNames.associateWith { "1" },
                )
                val result = cmd.resolve(game)
                if (result is ResolveResult.Failure) problems += "$where: ${result.problems}"
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)

        // Spot checks on the entries used by the other tests.
        val gba = catalogue.platform("gba")!!
        assertTrue(gba.players.any { it.id == "gba.it.dbtecno.pizzaboygba" && it.amStartArguments == RealEntries.PIZZA_BOY_GBA })
        assertTrue(gba.players.any { it.id == "gba.com.fastemulator.gbafree" && it.amStartArguments == RealEntries.MY_BOY_FREE })
        val raBuilds = catalogue.platforms.flatMap { it.players }.mapNotNull { catalogue.packageName(it) }
            .filter { it.startsWith("com.retroarch") }.toSet()
        println("RetroArch builds: $raBuilds")
        assertTrue("com.retroarch.aarch64" in raBuilds)
    }
}
