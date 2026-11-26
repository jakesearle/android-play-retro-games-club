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
        </div>
        </body></html>
    """.trimIndent()

    /** The games page's Past games list (November 2026), trimmed to a few rows. */
    val pastPage = """
        <html><body>
        <section class="flow" aria-labelledby="past-h">
        <div class="glist"><ul id="game-list" data-page-size="30">
        ${game("Alien Hominid", "GBA", 5068, "w", "Game of the Week", "Nov 15 to 21, 2026", "48", "6.8")}
        ${game("Michael Jackson&#39;s Moonwalker", "MD", 263, "w", "Game of the Week", "Sep 27 to Oct 3, 2026", "31", "6.0")}
        ${game("Super Ghouls &#39;n Ghosts", "SNES", 4817, "x", "Halloween Special", "2026", "24", "6.5")}
        ${game("Kingdom Hearts", "PS2", 32650, "s", "Game of the Season", "July to September 2026", "23", "8.1")}
        ${game("Pok&eacute;mon Renegade Platinum", "NDS", 22341, "r", "Romhack of the Month</span><span class=\"tag r\">Romhack of the Month", "March 2026 and 1 more", "0", "")}
        ${game("Castlevania: Symphony of the Night", "PS1", 11240, "s", "Game of the Season", "April to June 2026", "0", "")}
        </ul></div></section>
        </body></html>
    """.trimIndent()

    fun game(name: String, sys: String, raId: Int, tag: String, type: String, period: String, beaten: String, rating: String) = """
        <li data-name="$name $sys" data-tags="$tag" data-sys="$sys" data-year="2026"
            data-s-latest="2026-11-15T00:00:00Z" data-s-beaten="$beaten" data-s-rating="$rating" data-s-name="x">
          <a class="grow $tag" href="/games/x/">
            <span class="g-art"><img class="cover " src="/img/$raId.png" width="44" height="44" alt="$name box art" loading="lazy"></span>
            <span class="g-main">
              <strong>$name</strong>
              <span class="g-pick-inline"><span class="tag $tag">$type</span></span>
              <span class="g-sub">$sys<span class="g-when"> · $period</span></span>
            </span>
            <span class="g-pick"><span class="tag $tag">$type</span><span class="g-sub">$period</span></span>
            <span class="g-n"><b class="num">$beaten</b><span> beaten</span></span>
            <span class="g-n g-rate"><b class="num">$rating</b><span> rating</span></span>
          </a>
        </li>
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
        var pastBody = ClubFixture.pastPage
        var pastEtag = "\"p1\""
        var offline = false
        var pastOffline = false
        val requests = mutableListOf<String?>() // the ETag sent with each request for the club page
        val pastRequests = mutableListOf<String?>() // and for the games page

        override fun get(url: String, etag: String?): ClubHttp.Response {
            val past = url == ClubPage.PAST_URL
            (if (past) pastRequests else requests) += etag
            if (offline || (past && pastOffline)) throw IOException("offline")
            val (body, current) = if (past) pastBody to pastEtag else body to this.etag
            return if (etag != null && etag == current) ClubHttp.Response(true, "", etag) else ClubHttp.Response(false, body, current)
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

    @Test
    fun `past picks come from the games page, saved and revalidated beside the club page`() {
        assertEquals(5, (repo().load() as ClubResult.Ok).past.size)
        assertEquals(listOf<String?>(null), site.pastRequests)
        now = now.plusSeconds(3600)
        assertEquals(5, (repo().load() as ClubResult.Ok).past.size) // fresh: from disk
        assertEquals(1, site.pastRequests.size)
        repo().load(force = true)
        assertEquals(listOf(null, "\"p1\""), site.pastRequests)
        site.offline = true
        assertEquals(5, (repo().load(force = true) as ClubResult.Ok).past.size)
    }

    @Test
    fun `a games page that fails or changes keeps the saved past picks`() {
        repo().load()
        site.pastOffline = true
        val r = repo().load(force = true) as ClubResult.Ok
        assertEquals(2, r.picks.size)
        assertEquals(5, r.past.size)
        site.pastOffline = false
        site.pastBody = "<html>redesigned</html>"
        site.pastEtag = "\"p2\""
        assertEquals(5, (repo().load(force = true) as ClubResult.Ok).past.size)
        // With nothing saved, a missing games page means no past picks, not a failed load.
        site.pastOffline = true
        val fresh = ClubRepository(tmp.newFolder(), site) { now }.load() as ClubResult.Ok
        assertEquals(2, fresh.picks.size)
        assertEquals(emptyList<ClubPick>(), fresh.past)
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

/** Opt-in: parses real saved copies of the club page (`-PclubPage=...`) and games page (`-PclubGames=...`). */
class SavedClubPageTest {
    @Test
    fun `a real saved page parses`() {
        val path = System.getProperty("club.page").orEmpty()
        org.junit.Assume.assumeTrue("no saved page given", path.isNotEmpty())
        val html = java.io.File(path).readText()
        val picks = ClubPage.parse(html)
        for (p in picks) println("${p.event} | ${p.title} | console=${p.console?.name} game=${p.gameId} | ${p.stats} | ${p.imageUrl}")
        val pastPath = System.getProperty("club.games").orEmpty()
        val past = if (pastPath.isEmpty()) emptyList() else ClubPage.parsePast(java.io.File(pastPath).readText())
        println("past picks: ${past.size}; first: ${past.firstOrNull()?.let { it.event + " | " + it.title }}; unknown consoles: ${past.filter { it.console == null }.map { it.title }}")
        if (pastPath.isNotEmpty()) assertTrue(past.isNotEmpty() && past.all { it.console != null && it.gameId > 0 })
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

class PastPicksParseTest {
    @Test
    fun `past games parse newest first, specials left out`() {
        val past = ClubPage.parsePast(ClubFixture.pastPage)
        assertEquals(
            listOf(
                "Game of the Week November 15 - 21, 2026" to "Alien Hominid (GBA)",
                "Game of the Week September 27 - October 3, 2026" to "Michael Jackson's Moonwalker (MD)",
                "Game of the Season Q3 2026 (Jul 1 - Sep 30)" to "Kingdom Hearts (PS2)",
                "Romhack of the Month March 2026" to "Pokémon Renegade Platinum (NDS)",
                "Game of the Season Q2 2026 (Apr 1 - Jun 30)" to "Castlevania: Symphony of the Night (PS1)",
            ),
            past.map { it.event to it.title },
        )
        // Before the club counted, "0 beaten" means no data, so there's no Beaten stat.
        assertEquals(emptyList<ClubStat>(), past.last().stats)
        assertEquals(listOf(ClubStat("Rating", "8.1/10"), ClubStat("Beaten", "23")), past[2].stats)
        val alien = past[0]
        assertEquals(5068, alien.gameId)
        assertEquals("https://retroachievements.org/game/5068", alien.raUrl)
        assertEquals(RaConsoles.GAMEBOY_ADVANCE, alien.console?.id)
        assertEquals("Alien Hominid", alien.displayTitle)
        assertEquals("https://www.playretrogamesclub.com/img/5068.png", alien.imageUrl)
        assertEquals(RaConsoles.MEGA_DRIVE, past[1].console?.id)
        assertEquals("2026-09-27", ClubPage.periodStart(past[1].event.removePrefix("Game of the Week").trim()).toString())
    }

    @Test
    fun `every console the games page uses is known`() {
        for (sys in listOf("Arcade", "DC", "GB", "GBA", "GBC", "GC", "MD", "N64", "NDS", "NES", "PCE", "PS1", "PS2", "PSP", "SMS", "SNES", "Wii")) {
            assertTrue(sys, RaConsoles.fromTitle("Game ($sys)") != null)
        }
    }

    @Test
    fun `past periods read like the current picks`() {
        assertEquals("November 15 - 21, 2026", ClubPage.pastPeriod("Nov 15 to 21, 2026"))
        assertEquals("August 30 - September 5, 2026", ClubPage.pastPeriod("Aug 30 to Sep 5, 2026"))
        assertEquals("December 28 - January 3, 2027", ClubPage.pastPeriod("Dec 28 to Jan 3, 2027"))
        assertEquals("July 2024", ClubPage.pastPeriod("July 2024"))
        assertEquals("March 2026", ClubPage.pastPeriod("March 2026 and 1 more"))
        assertEquals("Q1 2027 (Jan 1 - Mar 31)", ClubPage.pastPeriod("January to March 2027"))
        assertEquals("Q4 2026 (Oct 1 - Dec 31)", ClubPage.pastPeriod("October to December 2026"))
        assertEquals("2026", ClubPage.pastPeriod("2026"))
    }

    @Test
    fun `a page without past winners has none`() {
        assertEquals(emptyList<ClubPick>(), ClubPage.parsePast("<html><body></body></html>"))
    }
}

class PeriodStartTest {
    @Test
    fun `weeks, months and seasons start on their first day`() {
        fun d(text: String) = ClubPage.periodStart(text)?.toString()
        assertEquals("2026-11-08", d("November 8 - 14, 2026"))
        assertEquals("2026-08-30", d("August 30 - September 5, 2026"))
        assertEquals("2026-12-28", d("December 28 - January 3, 2027"))
        assertEquals("2024-08-01", d("August 2024"))
        assertEquals("2026-10-01", d("Q4 2026 (Oct 1 - Dec 31)"))
        assertEquals("2025-04-01", d("Q2 2025 (Apr 1 - Jun 30)"))
        assertNull(d("Halloween Special"))
    }
}

class PastFilterTest {
    private fun row(event: String, title: String, state: app.retrolauncher.ui.PickState = app.retrolauncher.ui.PickState.CHECKING) =
        app.retrolauncher.ui.PickRow(ClubPick(event, "", title, "", "", "https://retroachievements.org/game/1", "", emptyList()), state)

    private val week = row("Game of the Week November 8 - 14, 2026", "A (GBA)", app.retrolauncher.ui.PickState.MISSING)
    private val month = row("Game of the Month October 2026", "B (SNES)", app.retrolauncher.ui.PickState.READY)
    private val romhack = row("Romhack of the Month October 2026", "C (SNES)")
    private val season = row("Game of the Season Q3 2026 (Jul 1 - Sep 30)", "D (N64)", app.retrolauncher.ui.PickState.UNKNOWN)
    private val oldWeek = row("Game of the Week October 4 - 10, 2026", "E (NES)", app.retrolauncher.ui.PickState.MISSING)
    private val all = listOf(season, oldWeek, romhack, week, month)

    private fun titles(filter: app.retrolauncher.ui.PastFilter) =
        app.retrolauncher.ui.applyPastFilter(all, filter).map { it.pick.title.first() }.joinToString("")

    @Test
    fun `merged newest first, oldest first reverses`() {
        assertEquals("AEBCD", titles(app.retrolauncher.ui.PastFilter()))
        assertEquals("DCBEA", titles(app.retrolauncher.ui.PastFilter(sort = app.retrolauncher.ui.PastSort.OLDEST)))
    }

    @Test
    fun `filters by type and by ROM`() {
        val weeks = app.retrolauncher.ui.PastFilter(types = setOf(app.retrolauncher.ui.PickType.WEEK))
        assertEquals("AE", titles(weeks))
        assertEquals("B", titles(app.retrolauncher.ui.PastFilter(rom = app.retrolauncher.ui.RomFilter.HAVE)))
        // Still checking (C) shows under neither; an unsupported console counts as missing.
        assertEquals("AED", titles(app.retrolauncher.ui.PastFilter(rom = app.retrolauncher.ui.RomFilter.MISSING)))
        // From Any, a type shows only that type; turning off the last one goes back to Any.
        assertEquals(weeks, app.retrolauncher.ui.PastFilter().toggle(app.retrolauncher.ui.PickType.WEEK))
        assertEquals(app.retrolauncher.ui.PastFilter(), weeks.toggle(app.retrolauncher.ui.PickType.WEEK))
    }
}

class PastSortMoreTest {
    private fun row(event: String, title: String, beaten: String?) = app.retrolauncher.ui.PickRow(
        ClubPick(event, "", title, "", "", "https://retroachievements.org/game/1", "", listOfNotNull(beaten?.let { ClubStat("Beaten", it) })),
    )
    private val a = row("Game of the Week November 8 - 14, 2026", "zelda (SNES)", "43")
    private val b = row("Game of the Week November 1 - 7, 2026", "Alien Hominid (GBA)", "51")
    private val c = row("Game of the Month October 2026", "Metroid (SNES)", null)
    private val all = listOf(c, a, b)

    private fun titles(filter: app.retrolauncher.ui.PastFilter) =
        app.retrolauncher.ui.applyPastFilter(all, filter).map { it.pick.displayTitle.first().uppercaseChar() }.joinToString("")

    @Test
    fun `most beaten, title and console`() {
        assertEquals("AZM", titles(app.retrolauncher.ui.PastFilter(sort = app.retrolauncher.ui.PastSort.MOST_BEATEN)))
        assertEquals("AMZ", titles(app.retrolauncher.ui.PastFilter(sort = app.retrolauncher.ui.PastSort.TITLE)))
        assertEquals("ZAM", titles(app.retrolauncher.ui.PastFilter(sort = app.retrolauncher.ui.PastSort.LEAST_BEATEN)))
        assertEquals("ZM", titles(app.retrolauncher.ui.PastFilter(console = RaConsoles.SUPER_NINTENDO)))
        assertEquals(
            listOf("Game Boy Advance" to 1, "SNES" to 2),
            app.retrolauncher.ui.pastConsoles(all).map { it.second to it.third },
        )
    }
}

class PastFilterSaveAndSurpriseTest {
    @Test
    fun `filters save and load, and bad text falls back to defaults`() {
        val filter = app.retrolauncher.ui.PastFilter(
            sort = app.retrolauncher.ui.PastSort.LEAST_BEATEN,
            types = setOf(app.retrolauncher.ui.PickType.MONTH, app.retrolauncher.ui.PickType.WEEK),
            rom = app.retrolauncher.ui.RomFilter.HAVE,
            console = RaConsoles.SUPER_NINTENDO,
        )
        assertEquals(filter, app.retrolauncher.ui.PastFilter.decode(filter.encode()))
        assertEquals(app.retrolauncher.ui.PastFilter(), app.retrolauncher.ui.PastFilter.decode(null))
        assertEquals(app.retrolauncher.ui.PastFilter(), app.retrolauncher.ui.PastFilter.decode("sort=WHATEVER;types=;junk"))
    }

    @Test
    fun `surprise prefers ready picks, then found ROMs`() {
        fun row(title: String, state: app.retrolauncher.ui.PickState) =
            app.retrolauncher.ui.PickRow(ClubPick("Game of the Week November 8 - 14, 2026", "", title, "", "", "", "", emptyList()), state)
        val ready = row("A (GBA)", app.retrolauncher.ui.PickState.READY)
        val noEmu = row("B (GBA)", app.retrolauncher.ui.PickState.NO_EMULATOR)
        val missing = row("C (GBA)", app.retrolauncher.ui.PickState.MISSING)
        repeat(20) {
            assertEquals(ready, app.retrolauncher.ui.surprisePick(listOf(missing, ready, noEmu)))
            assertEquals(noEmu, app.retrolauncher.ui.surprisePick(listOf(missing, noEmu)))
        }
        assertEquals(missing, app.retrolauncher.ui.surprisePick(listOf(missing)))
        assertNull(app.retrolauncher.ui.surprisePick(emptyList()))
    }
}

class RomLinksUnverifiedTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a file used anyway stays marked unverified after reloading`() {
        val file = java.io.File(tmp.root, "links.json")
        val game = app.retrolauncher.library.Game("k1", "snes", "Game (Europe).sfc", "Game", "/roms/Game (Europe).sfc", null, 10, 20)
        RomLinks(file).save(42, game, unverified = true)
        RomLinks(file).save(7, game.copy(key = "k2"))
        val reloaded = RomLinks(file)
        assertTrue(reloaded.get(42)!!.unverified)
        assertFalse(reloaded.get(7)!!.unverified)
    }
}

class PlainNoticesTest {
    @Test
    fun `notices read as plain text`() {
        val md = "# Third-party notices\n\n```\nMIT License\n```\n\n| Library | Licence |\n|---|---|\n| [jsoup](https://jsoup.org/) | MIT |\n\nUses `RaHash.kt`."
        assertEquals(
            "Third-party notices\n\nMIT License\n\nLibrary — Licence\njsoup (https://jsoup.org/) — MIT\n\nUses RaHash.kt.",
            app.retrolauncher.ui.plainNotices(md),
        )
    }
}

class TimeLeftTest {
    private fun pick(event: String, next: String = "") = ClubPick(event, next, "X (GBA)", "", "", "", "", emptyList())
    private fun utc(text: String) = Instant.parse(text)

    @Test
    fun `a pick ends at its next line's date, else its period's end`() {
        assertEquals(utc("2026-11-22T00:00:00Z"), ClubPage.endsAt(pick("Game of the Week November 15 - 21, 2026", "New week starts November 22, 2026")))
        assertEquals(utc("2026-11-22T00:00:00Z"), ClubPage.endsAt(pick("Game of the Week November 15 - 21, 2026")))
        assertEquals(utc("2026-12-01T00:00:00Z"), ClubPage.endsAt(pick("Romhack of the Month November 2026")))
        assertEquals(utc("2027-01-01T00:00:00Z"), ClubPage.endsAt(pick("Game of the Season Q4 2026 (Oct 1 - Dec 31)")))
        assertNull(ClubPage.endsAt(pick("Halloween Special")))
    }

    @Test
    fun `days left round up, with the final day called out`() {
        val end = utc("2026-11-22T00:00:00Z")
        assertEquals("3 days left", app.retrolauncher.ui.timeLeftLabel(end, utc("2026-11-19T00:00:00Z")))
        assertEquals("2 days left", app.retrolauncher.ui.timeLeftLabel(end, utc("2026-11-20T09:00:00Z")))
        assertEquals("Last day", app.retrolauncher.ui.timeLeftLabel(end, utc("2026-11-21T03:00:00Z")))
        assertNull(app.retrolauncher.ui.timeLeftLabel(end, utc("2026-11-22T00:00:01Z")))
        assertNull(app.retrolauncher.ui.timeLeftLabel(null, end))
    }
}
