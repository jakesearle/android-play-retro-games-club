package app.retrolauncher.club

import java.time.Instant

/**
 * What a quiet re-check of the club page (when the player comes back to the launcher) should
 * change on screen. The picks already shown are good, so a re-check only ever adds news: it
 * never clears the cards or swaps them for an error.
 */
sealed interface ClubRecheck {
    /** Leave everything as it is. */
    data object Nothing : ClubRecheck

    /**
     * Same picks, so no rescan; only the header may change.
     * @param offlineSince when the saved page was last confirmed, if the site couldn't be reached.
     */
    data class SamePicks(val offlineSince: Instant?) : ClubRecheck

    /** The club moved on (a new week, say): show [picks] and check them against the ROMs. */
    data class NewPicks(val picks: List<ClubPick>, val offlineSince: Instant?) : ClubRecheck

    companion object {
        /** [shown] are the club's picks on screen now, without debug test picks. */
        fun of(shown: List<ClubPick>, result: ClubResult): ClubRecheck = when (result) {
            is ClubResult.Ok -> {
                val offlineSince = result.fetchedAt.takeIf { result.fromCache }
                if (result.picks == shown) SamePicks(offlineSince) else NewPicks(result.picks, offlineSince)
            }
            // No saved page at all, or a page that no longer parses: whatever is shown stays.
            is ClubResult.NetworkError, ClubResult.ParseError -> Nothing
        }
    }
}
