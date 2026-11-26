package app.retrolauncher.club

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

data class ClubStat(val key: String, val value: String)

/** One card from the club's current picks grid. */
data class ClubPick(
    /** "Game of the Week November 15 - 21, 2026" */
    val event: String,
    /** "New week starts November 22, 2026", may be empty. */
    val next: String,
    /** "Paper Mario (N64)", including the console label. */
    val title: String,
    val description: String,
    val imageUrl: String,
    val raUrl: String,
    val guideUrl: String,
    val stats: List<ClubStat>,
) {
    val gameId: Int get() = RaConsoles.gameIdFromUrl(raUrl)
    val console: RaConsoles.Console? get() = RaConsoles.fromTitle(title)
    val displayTitle: String get() = RaConsoles.titleWithoutLabel(title)

    /**
     * The stats in reading order: rating, then the awards from easiest to hardest (beaten, then
     * completed in softcore, then mastered in hardcore), then time to beat. Stats this list
     * doesn't know keep the page's order, after the known ones.
     */
    val statsInOrder: List<ClubStat>
        get() = stats.sortedBy { stat ->
            STAT_ORDER.indexOfFirst { it.equals(stat.key, ignoreCase = true) }.let { if (it < 0) STAT_ORDER.size else it }
        }
}

/**
 * The Play Retro Games Club page, scraped as the NextUI Pak does (`src/club.c`). There is no
 * API: the page is server-rendered WordPress, and its `jrh-*` class names are the contract. If
 * they change, parsing finds no picks and the caller reports it instead of showing half-empty
 * screens.
 */
private val STAT_ORDER = listOf("Rating", "Beaten", "Completed", "Mastered", "Time to beat")

object ClubPage {
    const val URL = "https://www.joeysretrohandhelds.com/play-retro-games-club/"
    private const val MAX_PICKS = 12
    private const val MAX_STATS = 8

    /** Saved pages are trusted at most this long, so member counts stay current. */
    const val MAX_CACHE_AGE_MS = 6 * 60 * 60 * 1000L

    fun parse(html: String): List<ClubPick> {
        val doc = Jsoup.parse(html, URL)
        val grid = doc.selectFirst(".jrh-picks-grid") ?: return emptyList()
        return grid.select(".jrh-pick-card")
            .map(::parseCard)
            .filter { it.title.isNotEmpty() }
            .take(MAX_PICKS)
    }

    /** Where the club lists its earlier picks, since it moved them off [URL] (November 2026). */
    const val PAST_URL = "https://www.playretrogamesclub.com/games/"

    /** The pick types the past list keeps, by their tag's text. Specials have no past-picks filter. */
    private val PAST_TYPES = listOf("Game of the Week", "Game of the Month", "Romhack of the Month", "Game of the Season")

    /**
     * The first day of a pick's period, for sorting: "November 8 - 14, 2026" (a week, which may
     * run into the next month or year), "November 2026" (a month) or "Q4 2026 (Oct 1 - Dec 31)"
     * (a season). Null if the text isn't one of those.
     */
    fun periodStart(period: String): LocalDate? {
        val p = period.trim()
        fun month(name: String) = MONTHS.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it >= 0 }?.plus(1)
        return runCatching {
            QUARTER.find(p)?.let { m ->
                val (q, year) = m.destructured
                return@runCatching LocalDate.of(year.toInt(), (q.toInt() - 1) * 3 + 1, 1)
            }
            WEEK.find(p)?.let { m ->
                val (startMonth, day, endMonth, _, year) = m.destructured
                val start = month(startMonth) ?: return@runCatching null
                val end = if (endMonth.isEmpty()) start else month(endMonth) ?: return@runCatching null
                // The year belongs to the last day: "December 28 - January 3, 2027" starts in 2026.
                return@runCatching LocalDate.of(year.toInt() - if (end < start) 1 else 0, start, day.toInt())
            }
            MONTH.find(p)?.let { m ->
                val (name, year) = m.destructured
                return@runCatching LocalDate.of(year.toInt(), month(name) ?: return@runCatching null, 1)
            }
            null
        }.getOrNull()
    }

    private val QUARTER = Regex("""^Q([1-4])\s+(\d{4})""")
    private val WEEK = Regex("""^([A-Za-z]+)\s+(\d{1,2})\s*-\s*(?:([A-Za-z]+)\s+)?(\d{1,2}),\s*(\d{4})""")
    private val MONTH = Regex("""^([A-Za-z]+)\s+(\d{4})$""")

    /**
     * The club's earlier picks, from the Past games list on [PAST_URL] (`#game-list`, one `li` per
     * game, newest first): every row is on the page, the site pages them in the browser. Rows
     * have no description or guide, and two stats: how many members beat it, and its rating.
     *
     * The list has no RetroAchievements links; the box art is named by the RA game ID
     * (`/img/5068.png`), so that is where the ID comes from. The console is the row's `data-sys`,
     * written after the title as the current picks do ("Alien Hominid (GBA)"). Periods are
     * rewritten into the current picks' wording ([pastPeriod]). A game picked more than once
     * ("March 2026 and 1 more") is one row, for its latest pick.
     */
    fun parsePast(html: String): List<ClubPick> {
        val doc = Jsoup.parse(html, PAST_URL)
        return doc.select("#game-list > li").mapNotNull { row ->
            val pick = row.selectFirst(".g-pick") ?: return@mapNotNull null
            val type = pick.selectFirst(".tag")?.text()?.trim()?.takeIf { it in PAST_TYPES } ?: return@mapNotNull null
            val name = row.selectFirst(".g-main strong")?.text()?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            val sys = row.attr("data-sys").trim()
            val period = pastPeriod(pick.selectFirst(".g-sub")?.text().orEmpty())
            val image = row.selectFirst(".g-art img")?.absUrl("src").orEmpty()
            val gameId = RA_IMAGE_ID.find(image)?.groupValues?.get(1) ?: return@mapNotNull null
            val beaten = row.attr("data-s-beaten").trim().takeIf { it.isNotEmpty() }
                // Before the club counted, the page says 0 beaten; that's no data, not nobody.
                ?.takeUnless { it == "0" && periodStart(period)?.isBefore(BEATEN_COUNTED_FROM) == true }
            val rating = row.attr("data-s-rating").trim().takeIf { it.isNotEmpty() }
            ClubPick(
                event = if (period.isEmpty()) type else "$type $period",
                next = "",
                title = if (sys.isEmpty()) name else "$name ($sys)",
                description = "",
                imageUrl = image,
                raUrl = "https://retroachievements.org/game/$gameId",
                guideUrl = "",
                stats = listOfNotNull(rating?.let { ClubStat("Rating", "$it/10") }, beaten?.let { ClubStat("Beaten", it) }),
            )
        }
    }

    private val RA_IMAGE_ID = Regex("""/img/(\d+)\.[A-Za-z]+$""")

    /**
     * A past game's period in the current picks' wording, so it reads, sorts and ends the same:
     * "Nov 15 to 21, 2026" → "November 15 - 21, 2026", "Sep 27 to Oct 3, 2026" →
     * "September 27 - October 3, 2026", "October to December 2026" → "Q4 2026 (Oct 1 - Dec 31)".
     * Months ("July 2024") are already the same. A trailing "and 1 more" is dropped.
     */
    internal fun pastPeriod(text: String): String {
        val p = text.trim().replace(Regex("""\s+and\s+\d+\s+more$"""), "")
        fun month(abbr: String) = MONTHS.firstOrNull { it.startsWith(abbr.take(3), ignoreCase = true) }
        PAST_WEEK.find(p)?.let { m ->
            val (startMonth, startDay, endMonth, endDay, year) = m.destructured
            val start = month(startMonth) ?: return p
            val end = if (endMonth.isEmpty()) "" else (month(endMonth) ?: return p) + " "
            return "$start $startDay - $end$endDay, $year"
        }
        PAST_SEASON.find(p)?.let { m ->
            val (first, year) = m.destructured
            val q = listOf("January", "April", "July", "October").indexOfFirst { it.equals(first, ignoreCase = true) }
            return "Q${q + 1} $year (${QUARTER_DAYS[q]})"
        }
        return p
    }

    private val PAST_WEEK = Regex("""^([A-Za-z]+)\s+(\d{1,2})\s+to\s+(?:([A-Za-z]+)\s+)?(\d{1,2}),\s*(\d{4})$""")
    private val PAST_SEASON = Regex("""^(January|April|July|October)\s+to\s+(?:March|June|September|December)\s+(\d{4})$""", RegexOption.IGNORE_CASE)
    private val QUARTER_DAYS = listOf("Jan 1 - Mar 31", "Apr 1 - Jun 30", "Jul 1 - Sep 30", "Oct 1 - Dec 31")

    /**
     * The club started counting who beat each pick around here: the first week with a count is
     * June 28 – July 4, 2026, and the months and seasons before July 2026 all say 0. Picks that
     * started earlier show no Beaten stat rather than a misleading 0.
     */
    val BEATEN_COUNTED_FROM: LocalDate = LocalDate.of(2026, 6, 28)

    private fun parseCard(card: Element): ClubPick {
        fun text(selector: String) = card.selectFirst(selector)?.let(::visibleText).orEmpty()

        val stats = card.select(".jrh-pick-card__stat")
            .mapNotNull(::parseStat)
            .take(MAX_STATS)

        var raUrl = ""
        var guideUrl = ""
        for (link in card.select(".jrh-pick-card__link")) {
            when (visibleText(link)) {
                "RetroAchievements" -> raUrl = link.absUrl("href")
                "Guide" -> guideUrl = link.absUrl("href")
            }
        }

        val style = card.selectFirst(".jrh-pick-card__image")?.attr("style").orEmpty()
        val imageUrl = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""").find(style)?.groupValues?.get(1).orEmpty()

        return ClubPick(
            // The event label comes first; the "next" label shares its class and adds a modifier.
            event = text(".jrh-pick-card__label"),
            next = text(".jrh-pick-card__label--next"),
            title = text(".jrh-pick-card__title"),
            description = text(".jrh-pick-card__description"),
            imageUrl = imageUrl,
            raUrl = raUrl,
            guideUrl = guideUrl,
            stats = stats,
        )
    }

    /** Chips read "7.2/10" (rating), "24 beaten", "3 mastered" or "~2.2 hrs to beat". */
    internal fun parseStat(chip: Element): ClubStat? {
        val text = visibleText(chip)
        if (text.isEmpty()) return null
        if (chip.hasClass("jrh-pick-card__stat--rating")) return ClubStat("Rating", text)
        if (text.endsWith(" to beat") && text.length > " to beat".length) {
            return ClubStat("Time to beat", text.removeSuffix(" to beat"))
        }
        val space = text.indexOf(' ')
        if (space < 0) return ClubStat("Stat", text)
        return ClubStat(text.substring(space + 1).replaceFirstChar { it.uppercase() }, text.substring(0, space))
    }

    /** Text without the decorative `aria-hidden` emoji and stars, whitespace collapsed. */
    private fun visibleText(element: Element): String {
        val copy = element.clone()
        copy.select("[aria-hidden=true]").remove()
        return copy.text().trim()
    }

    // ── When the page changes ────────────────────────────────────────────────

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December",
    )
    private val DAY_IN_TEXT = MONTHS.joinToString("|").let { months ->
        Regex("""($months) (\d{1,2})(?:\s*[-–]\s*(?:($months) )?\d{1,2})?, (\d{4})""")
    }

    /**
     * When the page next changes, as it announces: the earliest of the countdown's
     * `data-target`, any date in a "next" line, and the 1st of the month after [fetched]
     * (monthly picks change then whether or not anything says so). Only times after [fetched]
     * count.
     */
    fun nextChange(html: String, fetched: Instant): Instant? {
        val doc = Jsoup.parse(html)
        val candidates = mutableListOf<Instant>()

        doc.selectFirst(".jrh-countdown")?.attr("data-target")?.takeIf { it.isNotEmpty() }?.let { target ->
            try {
                candidates += OffsetDateTime.parse(target).toInstant()
            } catch (_: DateTimeParseException) {
            }
        }
        for (label in doc.select(".jrh-pick-card__label--next")) {
            dayInText(label.text())?.let { candidates += it }
        }
        val today = fetched.atZone(ZoneOffset.UTC).toLocalDate()
        candidates += today.withDayOfMonth(1).plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC)

        return candidates.filter { it.isAfter(fetched) }.minOrNull()
    }

    /**
     * The first "<Month> <day>, <year>" in [text], as midnight UTC (the club's countdown runs on
     * UTC). For a range ("New week starts November 29 - December 5, 2026") it's the range's
     * start, a year earlier when the range crosses New Year ("December 27 - January 2, 2027").
     */
    internal fun dayInText(text: String): Instant? {
        val m = DAY_IN_TEXT.find(text) ?: return null
        val (startMonth, day, endMonth, yearText) = m.destructured
        val month = MONTHS.indexOf(startMonth) + 1
        val crossesYear = endMonth.isNotEmpty() && MONTHS.indexOf(endMonth) + 1 < month
        return try {
            LocalDate.of(yearText.toInt() - if (crossesYear) 1 else 0, month, day.toInt())
                .atStartOfDay().toInstant(ZoneOffset.UTC)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /**
     * When a current pick ends: the date in its "next" line ("New week starts November 22, 2026"),
     * else the end of its period (a week, month or quarter after it started). Midnight UTC, as
     * the club's countdown runs. Null for a special with neither.
     */
    fun endsAt(pick: ClubPick): Instant? {
        dayInText(pick.next)?.let { return it }
        val event = pick.event
        val (type, length) = PERIODS.entries.firstOrNull { event.startsWith(it.key) }?.let { it.key to it.value } ?: return null
        val start = periodStart(event.removePrefix(type).trim()) ?: return null
        return length(start).atStartOfDay().toInstant(ZoneOffset.UTC)
    }

    private val PERIODS: Map<String, (LocalDate) -> LocalDate> = mapOf(
        "Game of the Week" to { d -> d.plusWeeks(1) },
        "Game of the Month" to { d -> d.plusMonths(1) },
        "Romhack of the Month" to { d -> d.plusMonths(1) },
        "Game of the Season" to { d -> d.plusMonths(3) },
    )

    /** Whether a page fetched at [fetched] can be used at [now] without asking the site. */
    fun cacheIsFresh(now: Instant, fetched: Instant, nextChange: Instant?): Boolean {
        if (fetched.toEpochMilli() <= 0 || now.isBefore(fetched)) return false // clock went backwards
        if (now.toEpochMilli() - fetched.toEpochMilli() >= MAX_CACHE_AGE_MS) return false
        return nextChange == null || now.isBefore(nextChange)
    }
}
