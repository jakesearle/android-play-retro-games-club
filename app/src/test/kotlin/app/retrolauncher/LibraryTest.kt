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
    fun `Joey's recommended emulator goes first, then the rest as listed`() {
        val ra = "-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}"
        val c = app.retrolauncher.launch.ExtraPlayers.addTo(Catalogue.parse(
            """{"format":1,"platforms":[{"id":"gba","name":"Game Boy Advance","players":[
              {"id":"gba.pizza","name":"Pizza Boy A Pro","amStartArguments":${s("-n it.dbtecno.pizzaboygbapro/.Main")}},
              {"id":"gba.ra.vba","name":"RetroArch (64 bits) - vba_next","amStartArguments":${s(ra)}},
              {"id":"gba.ra.mgba","name":"RetroArch (64 bits) - mgba","amStartArguments":${s(ra)}}
            ]}]}"""
        ))
        assertEquals(listOf("gba.ra.mgba", "gba.pizza", "gba.ra.vba"), c.platform("gba")!!.players.map { it.id })
        assertEquals(listOf("com.retroarch.aarch64", "com.retroarch.ra32", "com.retroarch"), app.retrolauncher.launch.ExtraPlayers.preferredFor("gba"))
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

class SetupTest {
    @Test
    fun `setup shows on first run until done, and never once there are ROM folders`() {
        assertEquals(true, app.retrolauncher.ui.shouldShowSetup(setupDone = false, hasSources = false))
        assertEquals(false, app.retrolauncher.ui.shouldShowSetup(setupDone = true, hasSources = false)) // skipped
        assertEquals(false, app.retrolauncher.ui.shouldShowSetup(setupDone = false, hasSources = true)) // set up before setup existed
    }
}

class PathToDocumentIdTest {
    @Test
    fun `paths map back to storage document IDs`() {
        assertEquals("primary:ROMs", SafPaths.pathToDocumentId("/storage/emulated/0/ROMs"))
        assertEquals("primary:ROMs", SafPaths.pathToDocumentId("/sdcard/ROMs/"))
        assertEquals("primary:", SafPaths.pathToDocumentId("/storage/emulated/0"))
        assertEquals("6FEF-7578:ROMs/psx", SafPaths.pathToDocumentId("/storage/6FEF-7578/ROMs/psx"))
        assertEquals("6FEF-7578:", SafPaths.pathToDocumentId("/storage/6FEF-7578"))
        assertNull(SafPaths.pathToDocumentId("/data/data/app"))
        // And back again.
        assertEquals("/storage/6FEF-7578/ROMs", SafPaths.documentIdToPath(SafPaths.pathToDocumentId("/storage/6FEF-7578/ROMs")!!))
    }
}

class EmulatorPackTest {
    private fun s(v: String) = JsonPrimitive(v).toString()
    private fun app(id: String, name: String, url: String, about: String, category: String = "Emulator") =
        """{"id":${s(id)},"url":${s(url)},"author":"x","name":${s(name)},"preferredApkIndex":0,""" +
            """"additionalSettings":${s("{\"about\":\"$about\",\"trackOnly\":false}")},"categories":["$category"],"allowIdChange":false,"overrideSource":null}"""

    private val packJson = """{"apps":[
        ${app("com.armsx2", "ARMSX2", "https://github.com/ARMSX2/ARMSX2", "PlayStation 2 emulator")},
        ${app("com.nanodata.armsx", "ARMSX1", "https://github.com/ARMSX2/ARMSX1", "PlayStation 1 emulator")},
        ${app("com.github.stenzek.duckstation", "DuckStation", "https://duckstation-mirror.rmacias.workers.dev", "PlayStation 1 emulator")},
        ${app("487343354", "RetroArch (AArch64)", "https://buildbot.libretro.com/stable", "Multi-system emulator (stable builds)")},
        ${app("com.nendo.argosy", "Argosy", "https://github.com/rommapp/argosy-launcher", "Frontend", category = "Frontend")},
        ${app("com.github.stenzek.duckstation", "DuckStation (copy)", "https://example.com", "PlayStation 1 emulator")}
    ],"settings":{}}"""

    private val pack = app.retrolauncher.launch.EmulatorPack.parse(packJson)

    private val catalogue = app.retrolauncher.launch.ExtraPlayers.addTo(
        Catalogue.parse(
            """{"format":1,"platforms":[
              {"id":"psx","name":"PlayStation","players":[
                {"id":"psx.armsx1","name":"ARMSX1","amStartArguments":${s("-n com.nanodata.armsx/.Main\n-d {file.uri}")}},
                {"id":"psx.duck","name":"DuckStation","amStartArguments":${s("-n com.github.stenzek.duckstation/.EmulationActivity\n-e bootPath {file.uri}")}},
                {"id":"psx.ra64","name":"RetroArch","amStartArguments":${s("-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}")}}
              ]},
              {"id":"n64","name":"Nintendo 64","players":[
                {"id":"n64.ra64","name":"RetroArch","amStartArguments":${s("-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}")}}
              ]},
              {"id":"ps2","name":"PlayStation 2","retroAchievementsConsoleIds":[21],"players":[
                {"id":"ps2.ra64","name":"RetroArch","amStartArguments":${s("-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}")}},
                {"id":"ps2.armsx2","name":"ARMSX2","amStartArguments":${s("-n com.armsx2/.MainActivity\n-d {file.uri}")}}
              ]},
              {"id":"vb","name":"Virtual Boy","players":[]}
            ]}"""
        )
    )

    @Test
    fun `the pack parses, with descriptions and RetroArch's package`() {
        assertEquals(listOf("ARMSX2", "ARMSX1", "DuckStation", "RetroArch (AArch64)", "Argosy", "DuckStation (copy)"), pack.map { it.name })
        assertEquals("PlayStation 1 emulator", pack[2].about)
        assertEquals("com.retroarch.aarch64", pack[3].packageName) // numeric Obtainium ID
    }

    @Test
    fun `suggestions put RetroAchievements emulators first, one per app, no frontends`() {
        val psx = app.retrolauncher.launch.Recommendations.forPlatform(catalogue, catalogue.platform("psx")!!, pack)
        assertEquals(listOf("DuckStation", "RetroArch (AArch64)", "ARMSX1"), psx.map { it.app.name })
        assertEquals(listOf(true, true, false), psx.map { it.achievements })
        assertEquals(emptyList<String>(), app.retrolauncher.launch.Recommendations.forPlatform(catalogue, catalogue.platform("vb")!!, pack))
    }

    @Test
    fun `RetroArch doesn't count for achievements on PS2`() {
        val ps2 = app.retrolauncher.launch.Recommendations.forPlatform(catalogue, catalogue.platform("ps2")!!, pack)
        assertEquals(listOf("ARMSX2" to true, "RetroArch (AArch64)" to false), ps2.map { it.app.name to it.achievements })
    }

    @Test
    fun `Joey64 is suggested first for N64`() {
        val n64 = app.retrolauncher.launch.Recommendations.forPlatform(catalogue, catalogue.platform("n64")!!, pack)
        assertEquals(listOf("Joey64", "RetroArch (AArch64)"), n64.map { it.app.name })
    }

    @Test
    fun `the Obtainium link carries the app's config`() {
        val link = pack[2].obtainiumLink
        assertEquals(true, link.startsWith("obtainium://app/"))
        val decoded = java.net.URLDecoder.decode(link.removePrefix("obtainium://app/"), "UTF-8")
        assertEquals(pack[2].config, kotlinx.serialization.json.Json.parseToJsonElement(decoded))
        assertEquals(false, link.contains(" ") || link.contains("+"))
    }

    @Test
    fun `the pack is kept a week and an old copy is used offline`() {
        val dir = java.nio.file.Files.createTempDirectory("pack").toFile()
        var downloads = 0
        var online = true
        var now = 1_000_000_000L
        val repo = { app.retrolauncher.launch.EmulatorPackRepository(dir, { downloads++; if (!online) throw java.io.IOException("offline"); packJson }, { now }) }
        assertEquals(6, repo().load()!!.size)
        java.io.File(dir, "emulator-pack.json").setLastModified(now)
        assertEquals(6, repo().load()!!.size)
        assertEquals(1, downloads) // fresh: no second download
        now += 8L * 24 * 60 * 60 * 1000
        online = false
        assertEquals(6, repo().load()!!.size) // stale and offline: the old copy
        assertEquals(2, downloads)
        assertNull(app.retrolauncher.launch.EmulatorPackRepository(java.nio.file.Files.createTempDirectory("none").toFile(), { throw java.io.IOException() }).load())
    }
}

class RetroArchCoresTest {
    private fun s(v: String) = JsonPrimitive(v).toString()
    private val catalogue = Catalogue.parse(
        """{"format":1,"platforms":[{"id":"saturn","name":"Saturn","players":[
          {"id":"sat.yaba","name":"YabaSanshiro","amStartArguments":${s("-n org.uoyabause.android/.Main\n-d {file.uri}")}},
          {"id":"sat.ra64.beetle","name":"RetroArch (64 bits) - beetle saturn","amStartArguments":${s("-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}\n-e LIBRETRO /data/data/com.retroarch.aarch64/cores/mednafen_saturn_libretro_android.so")}},
          {"id":"sat.ra64.plain","name":"RetroArch (64 bits)","amStartArguments":${s("-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}\n-e LIBRETRO /data/data/com.retroarch.aarch64/cores/yabause_libretro_android.so")}}
        ]}]}"""
    )
    private val players = catalogue.platform("saturn")!!.players

    @Test
    fun `RetroArch players name their core, others don't`() {
        assertNull(app.retrolauncher.launch.RetroArchCores.coreName(catalogue, players[0]))
        assertEquals("beetle saturn", app.retrolauncher.launch.RetroArchCores.coreName(catalogue, players[1]))
        assertEquals("yabause", app.retrolauncher.launch.RetroArchCores.coreName(catalogue, players[2])) // from the core file
    }
}
