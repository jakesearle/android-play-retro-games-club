package app.retrolauncher.ui

import app.retrolauncher.club.ClubPage
import app.retrolauncher.club.ClubPick
import app.retrolauncher.ra.RaProgress

enum class PastSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    /** Most club members beat it first: the club's favourites. */
    MOST_BEATEN("Most beaten"),
    /** The fewest members beat it first: the club's hidden gems. */
    LEAST_BEATEN("Least beaten"),
    /** Most RetroAchievements players first (signed in only). */
    MOST_POPULAR("Most popular"),
    TITLE("Title A–Z"),
}

enum class RomFilter(val label: String) { ANY("Any"), HAVE("Have ROM"), MISSING("Missing ROM") }

/** Where you are with a pick ([completionOf]: RetroAchievements plus your own marks). */
enum class CompletionFilter(val label: String) {
    ANY("Any"),
    /** Not started or in progress: what's left to play. DNF games are left out. */
    UNBEATEN("Unbeaten"),
    NOT_STARTED("Not started"),
    IN_PROGRESS("In progress"),
    /** Beaten (on RetroAchievements or marked as completed elsewhere), but not mastered. */
    BEATEN("Beaten"),
    MASTERED("Mastered"),
    DNF("DNF");

    fun matches(c: Completion): Boolean = when (this) {
        ANY -> true
        UNBEATEN -> c == Completion.NOT_STARTED || c == Completion.IN_PROGRESS
        NOT_STARTED -> c == Completion.NOT_STARTED
        IN_PROGRESS -> c == Completion.IN_PROGRESS
        BEATEN -> c == Completion.BEATEN
        MASTERED -> c == Completion.MASTERED
        DNF -> c == Completion.DNF
    }
}

/** How the past-picks page sorts and filters. The default shows everything, newest first. */
data class PastFilter(
    val sort: PastSort = PastSort.NEWEST,
    val types: Set<PickType> = PickType.entries.toSet(),
    val rom: RomFilter = RomFilter.ANY,
    /** Only this console's picks (an RA console ID), or null for all of them. */
    val console: Int? = null,
    val completion: CompletionFilter = CompletionFilter.ANY,
) {
    val filtering: Boolean get() =
        types.size < PickType.entries.size || rom != RomFilter.ANY || console != null || completion != CompletionFilter.ANY

    /**
     * What applies given what's available: Most popular needs RetroAchievements, and the
     * completion filter needs RetroAchievements or at least one mark of your own.
     */
    fun available(signedIn: Boolean, hasMarks: Boolean): PastFilter = copy(
        sort = if (sort == PastSort.MOST_POPULAR && !signedIn) PastSort.NEWEST else sort,
        completion = if (signedIn || hasMarks) completion else CompletionFilter.ANY,
    )

    /** For saving: "sort=NEWEST;types=WEEK,MONTH;rom=ANY;console=3". */
    fun encode(): String = listOfNotNull(
        "sort=${sort.name}",
        "types=${types.sortedBy { it.ordinal }.joinToString(",") { it.name }}",
        "rom=${rom.name}",
        console?.let { "console=$it" },
        "completion=${completion.name}",
    ).joinToString(";")

    companion object {
        /** Reads [encode]'s text; anything missing or unknown keeps its default. */
        fun decode(text: String?): PastFilter {
            val fields = text.orEmpty().split(';').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            val default = PastFilter()
            return PastFilter(
                sort = PastSort.entries.firstOrNull { it.name == fields["sort"] } ?: default.sort,
                types = fields["types"]?.split(',')?.mapNotNull { n -> PickType.entries.firstOrNull { it.name == n } }
                    ?.toSet()?.ifEmpty { null } ?: default.types,
                rom = RomFilter.entries.firstOrNull { it.name == fields["rom"] } ?: default.rom,
                console = fields["console"]?.toIntOrNull(),
                completion = CompletionFilter.entries.firstOrNull { it.name == fields["completion"] } ?: default.completion,
            )
        }
    }

    /**
     * Picks a type. From "Any" (every type on), that shows only [type]; otherwise it turns
     * [type] on or off, and turning off the last one goes back to "Any".
     */
    fun toggle(type: PickType): PastFilter {
        val all = PickType.entries.toSet()
        val next = when {
            types == all -> setOf(type)
            type in types -> (types - type).ifEmpty { all }
            else -> types + type
        }
        return copy(types = next)
    }
}

/** The pick's type, from the start of its event ("Game of the Week November 8 - 14, 2026"). */
internal val ClubPick.type: PickType? get() = PickType.entries.firstOrNull { event.startsWith(it.label) }

/** When the pick ran, without its type: "November 8 - 14, 2026". */
internal val ClubPick.period: String get() = type?.let { event.removePrefix(it.label).trim() } ?: event

/** A ROM was found for the pick (whether or not it can be played yet). */
internal val PickRow.hasRom: Boolean
    get() = state == PickState.READY || state == PickState.NO_EMULATOR || state == PickState.CHOOSE_COPY

/**
 * A random pick from [rows] (the filtered list) for "Surprise me": one that's ready to play if
 * any is, else one whose ROM was found, else any. Only checked picks are known to be ready, so
 * picks not loaded yet still count, just behind those.
 */
internal fun surprisePick(rows: List<PickRow>, random: kotlin.random.Random = kotlin.random.Random.Default): PickRow? =
    rows.filter { it.state == PickState.READY }.ifEmpty { rows.filter { it.hasRom } }.ifEmpty { rows }.randomOrNull(random)

/** How many club members beat the pick, from its "Beaten" stat; null if unknown. */
internal val ClubPick.beaten: Int? get() = stats.firstOrNull { it.key == "Beaten" }?.value?.filter(Char::isDigit)?.toIntOrNull()

/** The consoles in [past], for the console filter: (RA console ID, name, how many picks), by name. */
internal fun pastConsoles(past: List<PickRow>): List<Triple<Int, String, Int>> =
    past.mapNotNull { it.pick.console }
        .groupingBy { it }.eachCount()
        .map { (console, count) -> Triple(console.id, console.name, count) }
        .sortedBy { it.second }

/** No ROM matches the pick (or its console isn't supported). */
internal val PickRow.missingRom: Boolean get() = state == PickState.MISSING || state == PickState.UNKNOWN

/** History's list as it's shown: [filter] limited to what's [PastFilter.available], applied. */
internal fun historyRows(past: List<PickRow>, filter: PastFilter, ra: RaState, marks: Map<Int, PickMark>): List<PickRow> =
    applyPastFilter(past, filter.available(ra.user != null, marks.isNotEmpty()), ra.progress, ra.players, marks)

/**
 * The past picks to show: all types merged and sorted by when they ran (on the same start date,
 * week before month before romhack before season), then filtered. While ROMs are still being
 * checked, a pick shows under neither ROM filter until its result is in.
 */
internal fun applyPastFilter(
    past: List<PickRow>,
    filter: PastFilter,
    /** Your RetroAchievements progress by game ID, for the completion filter. */
    progress: Map<Int, RaProgress> = emptyMap(),
    /** RetroAchievements player counts by game ID, for the Most popular sort. */
    players: Map<Int, Int> = emptyMap(),
    /** Your own marks by game ID, for the completion filter. */
    marks: Map<Int, PickMark> = emptyMap(),
): List<PickRow> {
    val byDate = compareBy<PickRow, java.time.LocalDate?>(nullsFirst()) { ClubPage.periodStart(it.pick.period) }
        .thenByDescending { it.pick.type?.ordinal ?: Int.MAX_VALUE }
    val newest = byDate.reversed()
    val order = when (filter.sort) {
        PastSort.NEWEST -> newest
        PastSort.OLDEST -> byDate
        // Ties (and picks with no count) fall back to newest first.
        PastSort.MOST_BEATEN -> compareByDescending<PickRow> { it.pick.beaten ?: -1 }.then(newest)
        // Picks with no count go last here too.
        PastSort.LEAST_BEATEN -> compareBy<PickRow> { it.pick.beaten ?: Int.MAX_VALUE }.then(newest)
        // Picks whose count hasn't loaded yet go last.
        PastSort.MOST_POPULAR -> compareByDescending<PickRow> { players[it.pick.gameId] ?: -1 }.then(newest)
        PastSort.TITLE -> compareBy<PickRow, String>(String.CASE_INSENSITIVE_ORDER) { it.pick.displayTitle }.then(newest)
    }
    return past.sortedWith(order).filter { row ->
        row.pick.type in filter.types &&
            (filter.console == null || row.pick.console?.id == filter.console) &&
            filter.completion.matches(completionOf(progress[row.pick.gameId], marks[row.pick.gameId])) &&
            when (filter.rom) {
            RomFilter.ANY -> true
            RomFilter.HAVE -> row.hasRom
            RomFilter.MISSING -> row.missingRom
        }
    }
}
