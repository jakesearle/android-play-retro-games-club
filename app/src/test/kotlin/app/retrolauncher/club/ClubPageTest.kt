package app.retrolauncher.club

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

/** Markup shaped like the live page (November 2026), trimmed to two cards. */
object ClubFixture {
    fun card(title: String, event: String, next: String, raId: Int, image: String = "014430") = """
        <div class="jrh-pick-card"><div class="jrh-pick-card__image" style="background-image:url('https://retroachievements.org/Images/$image.png')"></div>
        <div class="jrh-pick-card__body"><div class="jrh-pick-card__labels">
        <span class="jrh-pick-card__label">$event</span>
        <span class="jrh-pick-card__label jrh-pick-card__label--next">$next</span></div>
        <div class="jrh-pick-card__top-row"><h2 class="jrh-pick-card__title">$title</h2></div>
        <div class="jrh-pick-card__stat-row">
        <span class="jrh-pick-card__stat jrh-pick-card__stat--rating"><span class="jrh-pick-card__rating-star" aria-hidden="true">&#9733;</span>
        7.2/10                    </span>
        <span class="jrh-pick-card__stat"><span aria-hidden="true">&#127937;</span>
        24 beaten                    </span>
        <span class="jrh-pick-card__stat"><span aria-hidden="true">&#127942;</span> 3 mastered </span>
        <span class="jrh-pick-card__stat">
        ~2.2 hrs to beat                    </span></div>
        <p class="jrh-pick-card__description">The GBA Port of &quot;Alien Hominid&quot;</p>
        <div class="jrh-pick-card__bottom-row"><div class="jrh-pick-card__links">
        <a class="jrh-pick-card__link" href="https://retroachievements.org/game/$raId" target="_blank">
        RetroAchievements                        </a>
        <a class="jrh-pick-card__link" href="https://gamefaqs.gamespot.com/x/faqs" target="_blank">
        Guide                        </a></div></div></div></div>
    """.trimIndent()

    val page = """
        <html><body>
        <a class="jrh-countdown" href="#" data-target="2026-11-22T00:00:00+00:00"><span>Next: New Game of the Week!</span></a>
        <div class="jrh-picks-grid">
        ${card("Alien Hominid (GBA)", "Game of the Week November 15 - 21, 2026", "New week starts November 22, 2026", 5068)}
        ${card("Paper Mario (N64)", "Game of the Season Q4 2026 (Oct 1 - Dec 31)", "", 10056, image = "000001")}
        </div></body></html>
    """.trimIndent()
}

class ClubPageTest {
    @Test
    fun `parses cards`() {
        val picks = ClubPage.parse(ClubFixture.page)
        assertEquals(2, picks.size)
        val p = picks[0]
        assertEquals("Alien Hominid (GBA)", p.title)
        assertEquals("Alien Hominid", p.displayTitle)
        assertEquals("Game of the Week November 15 - 21, 2026", p.event)
        assertEquals("New week starts November 22, 2026", p.next)
        assertEquals("The GBA Port of \"Alien Hominid\"", p.description)
        assertEquals("https://retroachievements.org/Images/014430.png", p.imageUrl)
        assertEquals("https://retroachievements.org/game/5068", p.raUrl)
        assertEquals("https://gamefaqs.gamespot.com/x/faqs", p.guideUrl)
        assertEquals(5068, p.gameId)
        assertEquals(RaConsoles.GAMEBOY_ADVANCE, p.console?.id)
        assertEquals(
            listOf(
                ClubStat("Rating", "7.2/10"),
                ClubStat("Beaten", "24"),
                ClubStat("Mastered", "3"),
                ClubStat("Time to beat", "~2.2 hrs"),
            ),
            p.stats,
        )
        assertEquals("", picks[1].next)
        assertEquals(RaConsoles.NINTENDO_64, picks[1].console?.id)
    }

    @Test
    fun `a changed page finds nothing`() {
        assertEquals(emptyList<ClubPick>(), ClubPage.parse("<html><div class='picks'>x</div></html>"))
        // Cards outside the grid don't count either.
        assertEquals(emptyList<ClubPick>(), ClubPage.parse(ClubFixture.card("X (GBA)", "e", "", 1)))
    }

    @Test
    fun `next change is the earliest announced time after the fetch`() {
        val fetched = Instant.parse("2026-11-16T12:00:00Z")
        // The countdown and the "next" line both say Nov 22; the month end is later.
        assertEquals(Instant.parse("2026-11-22T00:00:00Z"), ClubPage.nextChange(ClubFixture.page, fetched))
        // After that, only the 1st of next month is left.
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), ClubPage.nextChange(ClubFixture.page, Instant.parse("2026-11-23T00:00:00Z")))
        assertEquals(Instant.parse("2026-11-28T00:00:00Z"), ClubPage.dayInText("Next month's voting starts November 28, 2026"))
        assertNull(ClubPage.dayInText("Q4 2026"))
    }

    @Test
    fun `cache freshness`() {
        val fetched = Instant.parse("2026-11-16T12:00:00Z")
        val next = Instant.parse("2026-11-16T20:00:00Z")
        assertTrue(ClubPage.cacheIsFresh(fetched.plusSeconds(3600), fetched, next))
        assertFalse(ClubPage.cacheIsFresh(fetched.plusSeconds(6 * 3600), fetched, null)) // 6-hour cap
        assertFalse(ClubPage.cacheIsFresh(Instant.parse("2026-11-16T15:00:00Z"), fetched, Instant.parse("2026-11-16T14:00:00Z")))
        assertFalse(ClubPage.cacheIsFresh(fetched.minusSeconds(60), fetched, null)) // clock went backwards
    }
}

class ClubRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSite : ClubHttp {
        var body = ClubFixture.page
        var etag = "\"v1\""
        var offline = false
        val requests = mutableListOf<String?>() // the ETag sent with each request

        override fun get(url: String, etag: String?): ClubHttp.Response {
            requests += etag
            if (offline) throw IOException("offline")
            return if (etag != null && etag == this.etag) ClubHttp.Response(true, "", etag) else ClubHttp.Response(false, body, this.etag)
        }
    }

    private val site = FakeSite()
    private var now = Instant.parse("2026-11-16T12:00:00Z")
    private fun repo() = ClubRepository(tmp.root, site) { now }

    @Test
    fun `fresh page is used without the network`() {
        assertEquals(2, (repo().load() as ClubResult.Ok).picks.size)
        assertEquals(listOf<String?>(null), site.requests)
        now = now.plusSeconds(3600)
        assertEquals(2, (repo().load() as ClubResult.Ok).picks.size)
        assertEquals(1, site.requests.size)
    }

    @Test
    fun `stale or forced loads revalidate with the etag`() {
        repo().load()
        now = now.plusSeconds(7 * 3600)
        val r = repo().load() as ClubResult.Ok
        assertEquals(listOf(null, "\"v1\""), site.requests)
        assertFalse(r.fromCache)
        assertEquals(now, r.fetchedAt)
        repo().load(force = true)
        assertEquals(3, site.requests.size)
    }

    @Test
    fun `offline falls back to the saved page`() {
        repo().load()
        site.offline = true
        val r = repo().load(force = true) as ClubResult.Ok
        assertTrue(r.fromCache)
        assertTrue(ClubRepository(tmp.newFolder(), site).load() is ClubResult.NetworkError)
    }

    @Test
    fun `a page that doesn't parse isn't saved`() {
        repo().load()
        site.body = "<html>redesigned</html>"
        site.etag = "\"v2\""
        assertEquals(ClubResult.ParseError, repo().load(force = true))
        site.offline = true
        assertEquals(2, (repo().load(force = true) as ClubResult.Ok).picks.size) // old page still there
    }
}

class RaConsolesTest {
    @Test
    fun `labels map to consoles`() {
        assertEquals(RaConsoles.NINTENDO_64, RaConsoles.fromTitle("Paper Mario (N64)")?.id)
        assertEquals(RaConsoles.SUPER_NINTENDO, RaConsoles.fromTitle("Donkey Kong Country (SNES)")?.id)
        assertEquals(RaConsoles.ARCADE, RaConsoles.fromTitle("Neo Turf Masters (Arcade)")?.id)
        assertEquals(RaConsoles.SUPER_NINTENDO, RaConsoles.fromTitle("Something (sfc)")?.id)
        assertEquals(RaConsoles.PLAYSTATION, RaConsoles.fromTitle("Spyro (PS1)")?.id)
        assertNull(RaConsoles.fromTitle("Mystery (Zork Machine)"))
        assertNull(RaConsoles.fromTitle("No label"))
        assertEquals("Zork Machine", RaConsoles.labelOf("Mystery (Zork Machine)"))
        assertEquals("Super Metroid: GBA Edition", RaConsoles.titleWithoutLabel("Super Metroid: GBA Edition (GBA)"))
        assertEquals(10056, RaConsoles.gameIdFromUrl("https://retroachievements.org/game/10056"))
        assertEquals(0, RaConsoles.gameIdFromUrl(""))
    }
}

/** Opt-in: parses a real saved copy of the club page (`-PclubPage=...`). */
class SavedClubPageTest {
    @Test
    fun `a real saved page parses`() {
        val path = System.getProperty("club.page").orEmpty()
        org.junit.Assume.assumeTrue("no saved page given", path.isNotEmpty())
        val html = java.io.File(path).readText()
        val picks = ClubPage.parse(html)
        for (p in picks) println("${p.event} | ${p.title} | console=${p.console?.name} game=${p.gameId} | ${p.stats} | ${p.imageUrl}")
        println("next change after now: ${ClubPage.nextChange(html, Instant.now())}")
        assertTrue(picks.isNotEmpty())
        assertTrue(picks.all { it.console != null && it.gameId > 0 && it.raUrl.isNotEmpty() })
    }
}

class PlatformConsolesTest {
    private val catalogue = app.retrolauncher.catalogue.Catalogue.parse(
        """{"format":1,"platforms":[
          {"id":"nes","name":"NES","retroAchievementsConsoleIds":[7]},
          {"id":"fds","name":"Famicom Disk System","retroAchievementsConsoleIds":[7]},
          {"id":"wii","name":"Wii","retroAchievementsConsoleIds":[19,20]},
          {"id":"wiiu","name":"Wii U","retroAchievementsConsoleIds":[20,19]}
        ]}"""
    )

    private fun holding(consoleId: Int) =
        catalogue.platforms.filter { PlatformConsoles.holds(it, consoleId) }.map { it.id }

    @Test
    fun `catalogue mistags are corrected`() {
        assertEquals(listOf("fds"), holding(RaConsoles.FAMICOM_DISK_SYSTEM)) // the catalogue says 7
        assertEquals(listOf("nes"), holding(RaConsoles.NINTENDO)) // fds no longer searched for NES picks
        assertEquals(listOf("wii"), holding(RaConsoles.WII))
        assertEquals(listOf("wiiu"), holding(RaConsoles.WII_U))
    }
}

class HomeCardsTest {
    private fun row(event: String, title: String) =
        app.retrolauncher.ui.PickRow(ClubPick(event, "", title, "", "", "https://retroachievements.org/game/1", "", emptyList()))

    @Test
    fun `four types in order, then specials, then test picks`() {
        val week = row("Game of the Week November 15 - 21, 2026", "A (GBA)")
        val season = row("Game of the Season Q4 2026 (Oct 1 - Dec 31)", "B (N64)")
        val special = row("Halloween Special October 2026", "C (PS1)")
        val secondMonth = row("Game of the Month November 2026", "D (SNES)")
        val month = row("Game of the Month November 2026", "E (SNES)")
        val test = row(app.retrolauncher.ui.DEBUG_EVENT, "F (PS1)")
        val cards = app.retrolauncher.ui.homeCards(listOf(test, special, month, week, season, secondMonth))
        assertEquals(
            listOf(
                "Game of the Week" to "A (GBA)",
                "Game of the Month" to "E (SNES)",
                "Romhack of the Month" to null, // no current romhack
                "Game of the Season" to "B (N64)",
                "Halloween Special October 2026" to "C (PS1)",
                "Game of the Month November 2026" to "D (SNES)", // a second pick of a type is kept
                "Test pick" to "F (PS1)",
            ),
            cards.map { it.label to it.row?.pick?.title },
        )
    }
}

class StatsOrderTest {
    @Test
    fun `stats read rating, beaten, completed, mastered, time to beat`() {
        val pick = ClubPick("e", "", "X (GBA)", "", "", "", "", listOf(
            ClubStat("Time to beat", "~2 hrs"), ClubStat("Mastered", "3"), ClubStat("Players", "40"),
            ClubStat("Beaten", "24"), ClubStat("Completed", "5"), ClubStat("Rating", "7.2/10"),
        ))
        assertEquals(listOf("Rating", "Beaten", "Completed", "Mastered", "Time to beat", "Players"), pick.statsInOrder.map { it.key })
    }
}
