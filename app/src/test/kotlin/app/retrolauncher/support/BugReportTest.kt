package app.retrolauncher.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BugReportTest {
    @Test
    fun `keys and tokens are removed, everything else kept`() {
        val log = "GET https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php?u=player&g=1&y=AbC123secret\n" +
            "dorequest r=login&t=tok3n&u=player\napiKey: sk-live-123 password=hunter2\nLaunching Game.sfc with retroarch"
        val out = BugReport.redact(log)
        for (secret in listOf("AbC123secret", "tok3n", "sk-live-123", "hunter2")) assertFalse(secret in out)
        assertEquals(true, "u=player&g=1&y=[removed]" in out)
        assertEquals(true, "Launching Game.sfc with retroarch" in out)
    }
}
