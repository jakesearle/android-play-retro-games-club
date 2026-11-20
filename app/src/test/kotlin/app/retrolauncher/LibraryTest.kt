package app.retrolauncher

import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.launch.Choices
import app.retrolauncher.launch.PlayerChooser
import app.retrolauncher.library.Game
import app.retrolauncher.library.GameTitles
import app.retrolauncher.library.RomSource
import app.retrolauncher.library.SafPaths
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafPathsTest {
    @Test
    fun `document ids map to real paths`() {
        assertEquals("/storage/emulated/0/ROMs/gba/x.gba", SafPaths.documentIdToPath("primary:ROMs/gba/x.gba"))
        assertEquals("/storage/emulated/0", SafPaths.documentIdToPath("primary:"))
        assertEquals("/storage/1A2B-3C4D/gba", SafPaths.documentIdToPath("1A2B-3C4D:gba"))
        assertNull(SafPaths.documentIdToPath("home:Documents"))
        assertNull(SafPaths.documentIdToPath("raw:/storage/x"))
        assertNull(SafPaths.documentIdToPath("nocolon"))
    }

    @Test
    fun `tree uris map to real paths`() {
        assertEquals(
            "/storage/1A2B-3C4D/ROMs",
            SafPaths.treeUriToPath("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AROMs"),
        )
        assertNull(SafPaths.treeUriToPath("content://com.android.providers.downloads.documents/tree/downloads"))
    }

    @Test
    fun `sources round-trip through their keys`() {
        val saf = RomSource.Saf("content://com.android.externalstorage.documents/tree/primary%3AROMs")
        assertEquals(saf, RomSource.fromKey(saf.key))
        assertEquals("/storage/emulated/0/ROMs", saf.label)
        val raw = RomSource.RawPath("/storage/1A2B-3C4D/ROMs")
        assertEquals(raw, RomSource.fromKey(raw.key))
    }
}

class GameTitlesTest {
    @Test
    fun `tags and extension are removed`() {
        assertEquals("Pokemon - Emerald Version", GameTitles.clean("Pokemon - Emerald Version (USA, Europe) [!].gba"))
        assertEquals("Metroid Fusion", GameTitles.clean("Metroid Fusion.gba"))
        assertEquals("(Hack)", GameTitles.clean("(Hack).gba")) // never empty
        assertEquals("Final Fantasy VII", GameTitles.clean("Final Fantasy VII (USA) (Disc 1).m3u"))
    }
}

class PlayerChooserTest {
    private fun s(v: String) = JsonPrimitive(v).toString()

    private val pizza = "-n it.dbtecno.pizzaboygba/.MainActivity\n-e rom_uri {file.uri}"
    private val myBoy = "-n com.fastemulator.gbafree/com.fastemulator.gba.EmulatorActivity\n-a android.intent.action.VIEW\n-d {file.path}"
    private val ra = "-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}"
    private val tagged = "-n app.gamenative/.MainActivity\n--ei app_id {tags.app_id}"

    private val catalogue = Catalogue.parse(
        """{"format":1,"platforms":[{"id":"gba","name":"Game Boy Advance","players":[
          {"id":"gba.pizza","name":"Pizza Boy","acceptedFilenameRegex":${s("^(.*)\\.(?:gba)$")},"amStartArguments":${s(pizza)}},
          {"id":"gba.myboy","name":"My Boy!","acceptedFilenameRegex":${s("^(.*)\\.(?:gba|gb)$")},"amStartArguments":${s(myBoy)}},
          {"id":"gba.ra64.mgba","name":"RetroArch mgba","was":["gba.retroarch.mgba"],"acceptedFilenameRegex":${s("^(.*)\\.(?:gba)$")},"amStartArguments":${s(ra)}},
          {"id":"gba.tagged","name":"Tagged","amStartArguments":${s(tagged)}}
        ]}]}"""
    )
    private val gba = catalogue.platform("gba")!!
    private val all = { _: String -> true }

    private val safWithPath = Game("k", "gba", "Golden Sun.gba", "Golden Sun", "/storage/emulated/0/ROMs/gba/Golden Sun.gba", "content://x/doc")
    private val safOnly = safWithPath.copy(path = null)
    private val rawOnly = safWithPath.copy(uri = null)

    private fun pick(game: Game, choices: Choices = Choices(null, null), installed: (String) -> Boolean = all) =
        PlayerChooser.pick(catalogue, gba, game, choices, installed)?.id

    @Test
    fun `first usable player in catalogue order by default`() {
        assertEquals("gba.pizza", pick(safWithPath))
        // Only a URI is known, so path players aren't usable.
        assertEquals("gba.pizza", pick(safOnly))
        assertEquals("gba.pizza", pick(rawOnly)) // gets a FileProvider URI at launch
    }

    @Test
    fun `game override beats platform default beats catalogue order`() {
        assertEquals("gba.myboy", pick(safWithPath, Choices(platformDefault = "gba.myboy", gameOverride = null)))
        assertEquals("gba.ra64.mgba", pick(safWithPath, Choices(platformDefault = "gba.myboy", gameOverride = "gba.ra64.mgba")))
        // Old IDs still work.
        assertEquals("gba.ra64.mgba", pick(safWithPath, Choices(platformDefault = "gba.retroarch.mgba", gameOverride = null)))
    }

    @Test
    fun `saved choices that can't launch are skipped`() {
        // RetroArch needs a path, which a picker-only folder without a mappable path doesn't have.
        assertEquals("gba.pizza", pick(safOnly, Choices(platformDefault = "gba.ra64.mgba", gameOverride = null)))
        // Uninstalled.
        assertEquals("gba.pizza", pick(safWithPath, Choices("gba.myboy", null), installed = { it != "com.fastemulator.gbafree" }))
    }

    @Test
    fun `options list installed players for the file, with problems`() {
        val options = PlayerChooser.options(catalogue, gba, safOnly, all)
        assertEquals(listOf("gba.pizza", "gba.myboy", "gba.ra64.mgba", "gba.tagged"), options.map { it.player.id })
        assertEquals(listOf(true, false, false, false), options.map { it.usable })
        assertEquals(listOf("gba.myboy", "gba.tagged"), PlayerChooser.options(catalogue, gba, safOnly.copy(fileName = "t.gb"), all).map { it.player.id })
        assertNull(pick(safWithPath, installed = { false }))
    }
}

class FolderAliasesTest {
    private fun s(v: String) = JsonPrimitive(v).toString()
    private val catalogue = Catalogue.parse(
        """{"format":1,"platforms":[
          {"id":"snes","name":"Super Nintendo"},
          {"id":"genesis","name":"Genesis"},
          {"id":"windows","name":"Windows","extra":{"folderAliases":["pc"]}}
        ]}"""
    )
    private fun id(folder: String) = app.retrolauncher.library.FolderAliases.platformFor(catalogue, folder)?.id

    @Test
    fun `catalogue names, frontend names and NextUI tags`() {
        assertEquals("snes", id("snes"))
        assertEquals("windows", id("PC"))
        assertEquals("snes", id("snesna")) // ES-DE
        assertEquals("snes", id("SFC"))
        assertEquals("genesis", id("megadrivejp"))
        assertEquals("snes", id("Super Nintendo Entertainment System (SFC)")) // NextUI
        assertEquals("genesis", id("Sega Genesis (MD)"))
        assertNull(id("androidapps"))
        assertNull(id("Something (XYZ)"))
    }
}

class EsDeFoldersTest {
    private val ids = listOf("snes", "genesis", "mame", "fbneo", "msx", "saturn", "n64", "amiga", "naomi", "gameandwatch", "mastersystem")
    private val catalogue = Catalogue.parse(
        """{"format":1,"platforms":[${ids.joinToString(",") { """{"id":"$it","name":"$it"}""" }}]}"""
    )
    private fun id(folder: String) = app.retrolauncher.library.FolderAliases.platformFor(catalogue, folder)?.id

    @Test
    fun `ES-DE folder names map to catalogue platforms`() {
        assertEquals("mame", id("arcade"))
        assertEquals("fbneo", id("cps"))
        assertEquals("fbneo", id("fba"))
        assertEquals("msx", id("msx2"))
        assertEquals("saturn", id("saturnjp"))
        assertEquals("n64", id("n64dd"))
        assertEquals("amiga", id("amiga1200"))
        assertEquals("naomi", id("naomigd"))
        assertEquals("gameandwatch", id("lcdgames"))
        assertEquals("mastersystem", id("mark3"))
        assertEquals("snes", id("sufami"))
        // Systems with no catalogue platform stay unmapped.
        assertNull(id("atari800"))
        assertNull(id("openbor"))
    }

    @Test
    fun `ES-DE and other tables don't disagree`() {
        val esde = app.retrolauncher.library.FolderAliases.ES_DE
        val other = app.retrolauncher.library.FolderAliases.OTHER
        assertEquals(emptySet<String>(), esde.keys.intersect(other.keys))
    }
}

class ExtraPlayersTest {
    private fun s(v: String) = JsonPrimitive(v).toString()
    private val retroarch = "-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}"

    private fun catalogue(extraN64: String = "") = Catalogue.parse(
        """{"format":1,"platforms":[
          {"id":"n64","name":"Nintendo 64","players":[
            {"id":"n64.ra64.mupen","name":"RetroArch mupen","acceptedFilenameRegex":${s("^(.*)\\.(?:z64|n64)$")},"amStartArguments":${s(retroarch)}}
            $extraN64
          ]},
          {"id":"gba","name":"Game Boy Advance","players":[]}
        ]}"""
    )

    @Test
    fun `Joey64 is added to N64, first, so it's the default`() {
        val c = app.retrolauncher.launch.ExtraPlayers.addTo(catalogue())
        val n64 = c.platform("n64")!!
        assertEquals(listOf("n64.com.joeysretro.joey64", "n64.ra64.mupen"), n64.players.map { it.id })
        assertEquals(0, c.platform("gba")!!.players.size)
        assertEquals("com.joeysretro.joey64", c.packageName(n64.players[0]))
        // Its file types, including 64DD disks; matching ignores case.
        val joey = n64.players[0]
        assertEquals(true, c.playerAccepts(joey, "Super Mario 64 (USA).Z64"))
        assertEquals(true, c.playerAccepts(joey, "F-Zero X Expansion Kit (Japan).ndd"))
        assertEquals(false, c.playerAccepts(joey, "Golden Sun.gba"))
    }

    @Test
    fun `Joey64 launches with a content URI and a read grant`() {
        val cmd = app.retrolauncher.catalogue.am.AmStartArguments.parse(app.retrolauncher.launch.ExtraPlayers.JOEY64.amStartArguments)
        assertEquals(emptyList<String>(), cmd.warnings)
        assertEquals("com.joeysretro.joey64.MainActivity", cmd.className)
        val uri = "content://com.android.externalstorage.documents/tree/x/document/primary%3AROMs%2Fn64%2FSM64.z64"
        val spec = (cmd.resolve(app.retrolauncher.catalogue.am.GameFile(path = "/storage/emulated/0/ROMs/n64/SM64.z64", uri = uri))
            as app.retrolauncher.catalogue.am.ResolveResult.Success).spec
        assertEquals(uri, spec.data)
        assertEquals("application/octet-stream", spec.mimeType)
        assertEquals(listOf(uri), spec.grantUris)
    }

    @Test
    fun `the catalogue's own Joey64 entry wins, and is still put first`() {
        val own = """,{"id":"n64.joey64","name":"Joey64 (catalogue)","amStartArguments":${s("-n com.joeysretro.joey64/.MainActivity\n-d {file.uri}")}}"""
        val c = app.retrolauncher.launch.ExtraPlayers.addTo(catalogue(own))
        assertEquals(listOf("n64.joey64", "n64.ra64.mupen"), c.platform("n64")!!.players.map { it.id })
    }

    @Test
    fun `Joey64 is the default only when installed, and a saved choice still wins`() {
        val c = app.retrolauncher.launch.ExtraPlayers.addTo(catalogue())
        val n64 = c.platform("n64")!!
        val game = app.retrolauncher.library.Game("k", "n64", "SM64.z64", "SM64", "/roms/n64/SM64.z64", "content://x/doc")
        fun pick(choices: app.retrolauncher.launch.Choices, installed: (String) -> Boolean) =
            app.retrolauncher.launch.PlayerChooser.pick(c, n64, game, choices, installed)?.id
        val none = app.retrolauncher.launch.Choices(null, null)
        assertEquals("n64.com.joeysretro.joey64", pick(none) { true })
        assertEquals("n64.ra64.mupen", pick(none) { it != "com.joeysretro.joey64" })
        assertEquals("n64.ra64.mupen", pick(app.retrolauncher.launch.Choices("n64.ra64.mupen", null)) { true })
    }
}
