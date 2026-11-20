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
    private val DAY_IN_TEXT = Regex("""(${MONTHS.joinToString("|")}) (\d{1,2}), (\d{4})""")

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

    /** The first "<Month> <day>, <year>" in [text], as midnight UTC (the club's countdown runs on UTC). */
    internal fun dayInText(text: String): Instant? {
        val m = DAY_IN_TEXT.find(text) ?: return null
        return try {
            LocalDate.of(m.groupValues[3].toInt(), MONTHS.indexOf(m.groupValues[1]) + 1, m.groupValues[2].toInt())
                .atStartOfDay().toInstant(ZoneOffset.UTC)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /** Whether a page fetched at [fetched] can be used at [now] without asking the site. */
    fun cacheIsFresh(now: Instant, fetched: Instant, nextChange: Instant?): Boolean {
        if (fetched.toEpochMilli() <= 0 || now.isBefore(fetched)) return false // clock went backwards
        if (now.toEpochMilli() - fetched.toEpochMilli() >= MAX_CACHE_AGE_MS) return false
        return nextChange == null || now.isBefore(nextChange)
    }
}
