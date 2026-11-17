package app.retrolauncher.club

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ClubRecheckTest {
    private fun pick(title: String) = ClubPick("Game of the Week", "", title, "", "", "https://retroachievements.org/game/1", "", emptyList())

    private val week1 = listOf(pick("Paper Mario (N64)"), pick("Metroid (NES)"))
    private val week2 = listOf(pick("Kirby's Adventure (NES)"), pick("Metroid (NES)"))
    private val fetched = Instant.parse("2026-11-16T06:00:00Z")

    @Test
    fun `same picks change nothing but the header`() {
        assertEquals(ClubRecheck.SamePicks(null), ClubRecheck.of(week1, ClubResult.Ok(week1, fromCache = false, fetchedAt = fetched)))
    }

    @Test
    fun `same picks from the saved page while offline say since when`() {
        assertEquals(ClubRecheck.SamePicks(fetched), ClubRecheck.of(week1, ClubResult.Ok(week1, fromCache = true, fetchedAt = fetched)))
    }

    @Test
    fun `new picks replace the shown ones`() {
        assertEquals(
            ClubRecheck.NewPicks(week2, null),
            ClubRecheck.of(week1, ClubResult.Ok(week2, fromCache = false, fetchedAt = fetched)),
        )
    }

    @Test
    fun `picks arrive when none were shown`() {
        assertEquals(
            ClubRecheck.NewPicks(week1, fetched),
            ClubRecheck.of(emptyList(), ClubResult.Ok(week1, fromCache = true, fetchedAt = fetched)),
        )
    }

    @Test
    fun `errors never replace the shown picks`() {
        assertEquals(ClubRecheck.Nothing, ClubRecheck.of(week1, ClubResult.NetworkError(IOException("offline"))))
        assertEquals(ClubRecheck.Nothing, ClubRecheck.of(week1, ClubResult.ParseError))
    }
}
