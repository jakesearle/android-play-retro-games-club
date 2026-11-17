package app.retrolauncher.catalogue

import app.retrolauncher.catalogue.am.AmStartArguments
import app.retrolauncher.catalogue.am.Extra
import app.retrolauncher.catalogue.am.GameFile
import app.retrolauncher.catalogue.am.LaunchFlag
import app.retrolauncher.catalogue.am.LaunchSpec
import app.retrolauncher.catalogue.am.ResolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResolveTest {

    private val safUri = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fgba%2FGame.gba"
    private val path = "/storage/emulated/0/ROMs/gba/Pokémon Emerald (USA) #1.gba"

    private fun resolve(args: String, game: GameFile): LaunchSpec =
        when (val r = AmStartArguments.parse(args).resolve(game)) {
            is ResolveResult.Success -> r.spec
            is ResolveResult.Failure -> fail("Expected success, got ${r.problems}") as Nothing
        }

    private fun problems(args: String, game: GameFile): List<String> =
        when (val r = AmStartArguments.parse(args).resolve(game)) {
            is ResolveResult.Success -> fail("Expected failure, got ${r.spec}") as Nothing
            is ResolveResult.Failure -> r.problems
        }

    @Test
    fun `Pizza Boy gets the content URI as an extra plus a grant`() {
        val spec = resolve(RealEntries.PIZZA_BOY_GBA, GameFile(uri = safUri))
        assertEquals(listOf(Extra.StringValue("rom_uri", safUri)), spec.extras)
        assertEquals(listOf(safUri), spec.grantUris)
        assertEquals(setOf(LaunchFlag.CLEAR_TASK, LaunchFlag.CLEAR_TOP, LaunchFlag.NEW_TASK), spec.flags)
    }

    @Test
    fun `Pizza Boy can't launch with only a path`() {
        assertEquals(listOf("needs a content:// URI"), problems(RealEntries.PIZZA_BOY_GBA, GameFile(path = path)))
    }

    @Test
    fun `RetroArch gets the raw path, spaces and all, and no grant`() {
        val spec = resolve(RealEntries.RETROARCH_MGBA, GameFile(path = path, uri = safUri))
        assertEquals(Extra.StringValue("ROM", path), spec.extras[0])
        assertEquals(emptyList<String>(), spec.grantUris)
        assertEquals("com.retroarch.aarch64", spec.packageName)
        assertTrue(LaunchFlag.NEW_TASK in spec.flags)
    }

    @Test
    fun `RetroArch can't launch from a picker-only folder`() {
        assertEquals(listOf("needs a file path"), problems(RealEntries.RETROARCH_MGBA, GameFile(uri = safUri)))
    }

    @Test
    fun `My Boy gets the path as data`() {
        val spec = resolve(RealEntries.MY_BOY_FREE, GameFile(path = path))
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals(path, spec.data)
        assertEquals(setOf(LaunchFlag.NEW_TASK), spec.flags)
    }

    @Test
    fun `Azahar gets the URI as data plus a grant`() {
        val spec = resolve(RealEntries.AZAHAR, GameFile(uri = safUri))
        assertEquals(safUri, spec.data)
        assertEquals(listOf(safUri), spec.grantUris)
    }

    @Test
    fun `file mime is filled in, with a fallback`() {
        assertEquals("application/epub+zip", resolve(RealEntries.BOOK_READER, GameFile(uri = safUri, mime = "application/epub+zip")).mimeType)
        assertEquals("application/octet-stream", resolve(RealEntries.BOOK_READER, GameFile(uri = safUri)).mimeType)
    }

    @Test
    fun `typed extras are converted after substitution`() {
        assertEquals(Extra.BoolValue("resumeState", false), resolve(RealEntries.DUCKSTATION, GameFile(uri = safUri)).extras[1])

        val gameNative = resolve(RealEntries.GAMENATIVE, GameFile(tags = mapOf("app_id" to "1145360", "game_source" to "STEAM")))
        assertEquals(listOf(Extra.IntValue("app_id", 1145360), Extra.StringValue("game_source", "STEAM")), gameNative.extras)

        val vita = resolve(RealEntries.VITA3K, GameFile(tags = mapOf("vita_game_id" to "PCSE00000")))
        assertEquals(Extra.StringArrayValue("AppStartParameters", listOf("-r", "PCSE00000")), vita.extras.single())

        assertEquals(Extra.UriValue("android.intent.extra.STREAM", path), resolve(RealEntries.ARMSX3, GameFile(path = path)).extras.single())

        val arrays = resolve("-n a/b\n--eia ids 1, 2,3\n--esa s a\\,b,c\n--ez on 1", GameFile())
        assertEquals(
            listOf(
                Extra.IntArrayValue("ids", listOf(1, 2, 3)),
                Extra.StringArrayValue("s", listOf("a,b", "c")),
                Extra.BoolValue("on", true),
            ),
            arrays.extras,
        )
    }

    @Test
    fun `missing tags and bad numbers are reported`() {
        assertEquals(
            listOf("needs game tag \"app_id\"", "needs game tag \"game_source\""),
            problems(RealEntries.GAMENATIVE, GameFile()),
        )
        assertEquals(
            listOf("app_id: \"abc\" isn't an int"),
            problems(RealEntries.GAMENATIVE, GameFile(tags = mapOf("app_id" to "abc", "game_source" to "STEAM"))),
        )
    }

    @Test
    fun `file URIs are not granted`() {
        val spec = resolve(RealEntries.AZAHAR, GameFile(uri = "file:///sdcard/a.3ds"))
        assertEquals(emptyList<String>(), spec.grantUris)
    }
}
