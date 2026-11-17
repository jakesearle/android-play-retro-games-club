package app.retrolauncher.catalogue

import app.retrolauncher.catalogue.am.AmStartArguments
import app.retrolauncher.catalogue.am.ExtraType
import app.retrolauncher.catalogue.am.LaunchFlag
import app.retrolauncher.catalogue.am.RawExtra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmStartArgumentsTest {

    @Test
    fun `RetroArch mgba - component, extras and flags`() {
        val cmd = AmStartArguments.parse(RealEntries.RETROARCH_MGBA)
        assertEquals("com.retroarch.aarch64", cmd.packageName)
        assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", cmd.className)
        assertNull(cmd.action)
        assertEquals(8, cmd.extras.size)
        assertEquals(RawExtra(ExtraType.STRING, "ROM", "{file.path}"), cmd.extras[0])
        assertEquals(
            RawExtra(ExtraType.STRING, "LIBRETRO", "/data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so"),
            cmd.extras[1],
        )
        // The value contains a '/' and a '.'; it must not be read as a component.
        assertEquals(RawExtra(ExtraType.STRING, "IME", "com.android.inputmethod.latin/.LatinIME"), cmd.extras[3])
        assertEquals(setOf(LaunchFlag.CLEAR_TASK, LaunchFlag.CLEAR_TOP), cmd.flags)
        assertTrue(cmd.needsFilePath)
        assertFalse(cmd.needsFileUri)
        assertEquals(emptyList<String>(), cmd.warnings)
    }

    @Test
    fun `Pizza Boy - relative activity and rom_uri extra`() {
        val cmd = AmStartArguments.parse(RealEntries.PIZZA_BOY_GBA)
        assertEquals("it.dbtecno.pizzaboygba", cmd.packageName)
        assertEquals("it.dbtecno.pizzaboygba.MainActivity", cmd.className)
        assertEquals(listOf(RawExtra(ExtraType.STRING, "rom_uri", "{file.uri}")), cmd.extras)
        assertTrue(cmd.needsFileUri)
        assertFalse(cmd.needsFilePath)
        assertEquals(emptyList<String>(), cmd.warnings)
    }

    @Test
    fun `My Boy - VIEW with path data`() {
        val cmd = AmStartArguments.parse(RealEntries.MY_BOY_FREE)
        assertEquals("com.fastemulator.gba.EmulatorActivity", cmd.className)
        assertEquals("android.intent.action.VIEW", cmd.action)
        assertEquals("{file.path}", cmd.data)
        assertEquals(emptySet<LaunchFlag>(), cmd.flags)
        assertTrue(cmd.needsFilePath)
    }

    @Test
    fun `Book Reader - category and mime type`() {
        val cmd = AmStartArguments.parse(RealEntries.BOOK_READER)
        assertEquals("com.github.axet.bookreader.activities.MainActivity", cmd.className)
        assertEquals(listOf("android.intent.category.DEFAULT"), cmd.categories)
        assertEquals("{file.uri}", cmd.data)
        assertEquals("{file.mime}", cmd.mimeType)
    }

    @Test
    fun `typed extras`() {
        assertEquals(
            RawExtra(ExtraType.BOOL, "resumeState", "false"),
            AmStartArguments.parse(RealEntries.DUCKSTATION).extras[1],
        )
        val gameNative = AmStartArguments.parse(RealEntries.GAMENATIVE)
        assertEquals(RawExtra(ExtraType.INT, "app_id", "{tags.app_id}"), gameNative.extras[0])
        assertEquals(setOf("app_id", "game_source"), gameNative.tagNames)
        assertEquals(
            RawExtra(ExtraType.STRING_ARRAY, "AppStartParameters", "-r,{tags.vita_game_id}"),
            AmStartArguments.parse(RealEntries.VITA3K).extras.single(),
        )
        assertEquals(
            RawExtra(ExtraType.URI, "android.intent.extra.STREAM", "{file.path}"),
            AmStartArguments.parse(RealEntries.ARMSX3).extras.single(),
        )
        val more = AmStartArguments.parse("-n a/b\n--el big 9000000000\n--eia ids 1,2,3")
        assertEquals(RawExtra(ExtraType.LONG, "big", "9000000000"), more.extras[0])
        assertEquals(RawExtra(ExtraType.INT_ARRAY, "ids", "1,2,3"), more.extras[1])
    }

    @Test
    fun `string extra value may contain spaces and may be empty`() {
        val cmd = AmStartArguments.parse("-n a/b\n-e ROM /storage/My Roms/Zelda  (USA) .gba\n-e CONFIG")
        assertEquals(RawExtra(ExtraType.STRING, "ROM", "/storage/My Roms/Zelda  (USA) .gba"), cmd.extras[0])
        assertEquals(RawExtra(ExtraType.STRING, "CONFIG", ""), cmd.extras[1])
        assertEquals(emptyList<String>(), cmd.warnings)
    }

    @Test
    fun `unknown and malformed switches are reported, not fatal`() {
        val cmd = AmStartArguments.parse(
            "-n com.example/.Main\n--user 0\n-e\n--activity-clear-top extra-junk\n-n nopackage\n-a VIEW"
        )
        assertEquals("com.example.Main", cmd.className)
        assertEquals("VIEW", cmd.action)
        assertEquals(setOf(LaunchFlag.CLEAR_TOP), cmd.flags)
        assertEquals(4, cmd.warnings.size)
        assertTrue(cmd.warnings[0], "unknown switch --user" in cmd.warnings[0])
        assertTrue(cmd.warnings[1], "-e needs a key" in cmd.warnings[1])
        assertTrue(cmd.warnings[2], "takes no value" in cmd.warnings[2])
        assertTrue(cmd.warnings[3], "package/activity" in cmd.warnings[3])
    }

    @Test
    fun `blank lines, indentation and CRLF are tolerated`() {
        val cmd = AmStartArguments.parse("  -n a.b/.C\r\n\r\n\t-e K v\r\n")
        assertEquals("a.b.C", cmd.className)
        assertEquals(listOf(RawExtra(ExtraType.STRING, "K", "v")), cmd.extras)
        assertEquals(emptyList<String>(), cmd.warnings)
    }

    @Test
    fun `raw -f flags in hex and decimal`() {
        val cmd = AmStartArguments.parse("-n a/b\n-f 0x10000000\n-f 32768")
        assertEquals(0x10000000 or 32768, cmd.rawFlags)
    }
}
