package app.retrolauncher.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueTest {

    private fun player(id: String, name: String, regex: String, args: String, extra: String = "") =
        """{"id":"$id","name":"$name","acceptedFilenameRegex":${jsonString(regex)},"amStartArguments":${jsonString(args)}$extra}"""

    private fun jsonString(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private val ra = { pkg: String ->
        "-n $pkg/com.retroarch.browser.retroactivity.RetroActivityFuture\n-e ROM {file.path}\n--activity-clear-task\n--activity-clear-top"
    }

    /** Trimmed from the real android.json; one field is renamed to check unknown keys are ignored. */
    private val json = """
    {"format":1,"platforms":[
      {"id":"gba","name":"Game Boy Advance","acceptedFilenameRegex":"^(?!(?:\\._|\\.).*).*$",
       "retroAchievementsConsoleIds":[5],"cocoon":{"icon":"gba","folder":"wisteria"},
       "scrape":{"screenscraper":12,"igdb":24,"launchbox":"Nintendo Game Boy Advance","libretro":"Nintendo - Game Boy Advance","thegamesdb":5},
       "someFutureField":{"x":1},
       "players":[
         ${player("gba.it.dbtecno.pizzaboygba", "Pizza Boy GBA Basic", "^(.*)\\.(?:bin|gba|zip|7z)$", RealEntries.PIZZA_BOY_GBA)},
         ${player("gba.com.fastemulator.gbafree", "My Boy! Free", "^(.*)\\.(?:agb|bin|cgb|dmg|gb|gba|gbc|gbz|sgb)$", RealEntries.MY_BOY_FREE)},
         ${player("gba.ra64.mgba", "RetroArch (64 bits) - mgba", "^(.*)\\.(?:gba|zip|7z)$", ra("com.retroarch.aarch64"))},
         ${player("gba.ra32.mgba", "RetroArch (32 bits) - mgba", "^(.*)\\.(?:gba|zip|7z)$", ra("com.retroarch.ra32"), extra = ",\"was\":[\"gba.retroarch32.mgba\"]")}
       ]},
      {"id":"windows","name":"Windows","acceptedFilenameRegex":"^(?!(?:\\._|\\.).*).*$",
       "extra":{"folderAliases":["pc","steam"],"pcStorefronts":{"sources":[]}},
       "cocoon":{"icon":"windows","folder":["#86A7FD","#3355AA"]},
       "players":[
         ${player("windows.app.gamenative", "GameNative", "^(.*)\\.(?:steam|steamappid)$", RealEntries.GAMENATIVE, extra = ",\"extra\":{\"cantResume\":true}")}
       ]},
      {"id":"megadrive","name":"Mega Drive","was":["genesis"],"acceptedFilenameRegex":"^(?!(?:\\._|\\.).*).*(?<!\\.txt|\\.jpeg)$",
       "cocoon":{},"players":[]}
    ]}
    """.trimIndent()

    private val catalogue = Catalogue.parse(json)

    @Test
    fun `parses platforms and fields`() {
        assertEquals(3, catalogue.platforms.size)
        val gba = catalogue.platform("gba")!!
        assertEquals(listOf(5), gba.retroAchievementsConsoleIds)
        assertEquals(12, gba.screenscraperId)
        assertEquals("Nintendo - Game Boy Advance", gba.libretroName)
        assertEquals("wisteria", gba.cocoon.folderColourName)
        assertEquals(listOf("#86A7FD", "#3355AA"), catalogue.platform("windows")!!.cocoon.folderGradient)
        assertTrue(catalogue.platform("windows")!!.players.single().cantResume)
        assertFalse(gba.players.first().cantResume)
    }

    @Test
    fun `folders map by id, alias and old id, ignoring case`() {
        assertEquals("gba", catalogue.platformForFolder("GBA")?.id)
        assertEquals("gba", catalogue.platformForFolder(" gba ")?.id)
        assertEquals("windows", catalogue.platformForFolder("Steam")?.id)
        assertEquals("windows", catalogue.platformForFolder("pc")?.id)
        assertEquals("megadrive", catalogue.platformForFolder("genesis")?.id)
        assertNull(catalogue.platformForFolder("snes"))
    }

    @Test
    fun `players are filtered by installed package`() {
        val gba = catalogue.platform("gba")!!
        val installed = setOf("com.retroarch.aarch64", "com.fastemulator.gbafree")
        assertEquals(
            listOf("gba.com.fastemulator.gbafree", "gba.ra64.mgba"),
            catalogue.installedPlayers(gba, installed::contains).map { it.id },
        )
        assertEquals("com.retroarch.ra32", catalogue.packageName(gba.players[3]))
    }

    @Test
    fun `players are filtered by file name`() {
        val gba = catalogue.platform("gba")!!
        val all = { _: String -> true }
        assertEquals(4, catalogue.playersFor(gba, "Golden Sun.GBA", all).size) // case-insensitive
        assertEquals(listOf("gba.com.fastemulator.gbafree"), catalogue.playersFor(gba, "Tetris.gb", all).map { it.id })
        assertEquals(listOf("gba.it.dbtecno.pizzaboygba", "gba.com.fastemulator.gbafree"), catalogue.playersFor(gba, "x.bin", all).map { it.id })
    }

    @Test
    fun `platform regex rejects dot-files and ignored extensions`() {
        val gba = catalogue.platform("gba")!!
        assertTrue(catalogue.platformAccepts(gba, "Metroid Fusion.gba"))
        assertFalse(catalogue.platformAccepts(gba, "._Metroid Fusion.gba"))
        assertFalse(catalogue.platformAccepts(gba, ".DS_Store"))
        val md = catalogue.platform("megadrive")!!
        assertTrue(catalogue.platformAccepts(md, "Sonic.md"))
        assertFalse(catalogue.platformAccepts(md, "readme.txt"))
        assertFalse(catalogue.platformAccepts(md, "cover.jpeg"))
    }

    @Test
    fun `players are found by old id`() {
        val gba = catalogue.platform("gba")!!
        assertEquals("gba.ra32.mgba", catalogue.player(gba, "gba.retroarch32.mgba")?.id)
        assertEquals("gba.ra64.mgba", catalogue.player(gba, "gba.ra64.mgba")?.id)
        assertNull(catalogue.player(gba, "nope"))
    }
}
