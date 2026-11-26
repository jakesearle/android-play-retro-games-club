package app.retrolauncher

import app.retrolauncher.launch.AppUpdater
import app.retrolauncher.launch.UpdateCheck
import app.retrolauncher.ui.plainNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParseTest {
    /** GitHub's latest-release answer (v0.2.0), trimmed to the fields used and one unused one. */
    private val json = """
        {"tag_name":"v0.2.0","html_url":"https://github.com/jakesearle/android-play-retro-games-club/releases/tag/v0.2.0",
         "draft":false,
         "body":"**Install:** download `prgc-launcher-0.2.0.apk` below.\r\n\r\n### New\r\n- Make a romhack",
         "assets":[
           {"name":"checksums.txt","size":120,"browser_download_url":"https://example.invalid/checksums.txt"},
           {"name":"prgc-launcher-0.2.0.apk","size":2761950,"content_type":"application/vnd.android.package-archive",
            "browser_download_url":"https://github.com/jakesearle/android-play-retro-games-club/releases/download/v0.2.0/prgc-launcher-0.2.0.apk"}
         ]}
    """.trimIndent()

    @Test
    fun `the release's APK, size and notes are kept`() {
        val r = UpdateCheck.parseRelease(json)!!
        assertEquals("v0.2.0", r.tag)
        assertEquals("https://github.com/jakesearle/android-play-retro-games-club/releases/tag/v0.2.0", r.url)
        assertEquals("https://github.com/jakesearle/android-play-retro-games-club/releases/download/v0.2.0/prgc-launcher-0.2.0.apk", r.apkUrl)
        assertEquals(2761950L, r.apkSize)
        assertTrue(r.notes.startsWith("**Install:**"))
    }

    @Test
    fun `a release without an APK still counts, without a tag it doesn't`() {
        val r = UpdateCheck.parseRelease("""{"tag_name":"v0.3.0","html_url":"https://x","assets":[]}""")!!
        assertNull(r.apkUrl)
        assertEquals(0L, r.apkSize)
        assertEquals("", r.notes)
        assertNull(UpdateCheck.parseRelease("""{"message":"API rate limit exceeded"}"""))
        assertNull(UpdateCheck.parseRelease("""[]"""))
    }
}

class UpdateApkCheckTest {
    private val key = setOf("ab12")

    private fun problem(pkg: String? = "garden.searle.prgc", version: Long = 3, signers: Set<String>? = key, ours: Set<String>? = key) =
        AppUpdater.problem(pkg, "garden.searle.prgc", version, 2, signers, ours)

    @Test
    fun `only a newer build of this app with the same key installs`() {
        assertNull(problem())
        assertTrue(problem(pkg = "com.example.other")!!.contains("different app"))
        assertTrue(problem(version = 2)!!.contains("isn't newer"))
        assertTrue(problem(version = 1)!!.contains("isn't newer"))
        assertTrue(problem(signers = setOf("cd34"))!!.contains("different key"))
    }

    @Test
    fun `unknown signers are left to Android's installer`() {
        assertNull(problem(signers = null))
        assertNull(problem(ours = emptySet()))
    }
}

class ReleaseNotesTest {
    @Test
    fun `markdown reads as plain text`() {
        val notes = "**Install:** download `x.apk` or use [Obtainium](https://o.example).\r\n\r\n\r\n### New\r\n- **Make a romhack.** Done\r\n* Second"
        assertEquals("Install: download x.apk or use Obtainium.\n\nNew\n• Make a romhack. Done\n• Second", plainNotes(notes))
    }

    @Test
    fun `long notes are cut at a word`() {
        val cut = plainNotes("word ".repeat(300), max = 50)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= 51)
        assertTrue(cut.removeSuffix("…").split(' ').all { it == "word" })
    }
}
