package app.retrolauncher.ui

import app.retrolauncher.ra.RaAward
import app.retrolauncher.ra.RaProgress

/** A mark you set on a game yourself, for what RetroAchievements can't know. */
enum class PickMark(val label: String, val detail: String) {
    COMPLETED_ELSEWHERE("Completed somewhere else", "You finished it outside RetroAchievements (another console, years ago…). Counts as beaten."),
    DNF("Did not finish", "You gave up on it. Shown as DNF, and kept out of Unbeaten."),
}

/** Where you are with a game: RetroAchievements' award and your own mark, combined. */
enum class Completion { NOT_STARTED, IN_PROGRESS, BEATEN, MASTERED, DNF }

/**
 * Combines RetroAchievements and your mark. An RA beaten or mastered award wins (you did beat
 * it); otherwise your mark applies; otherwise RA's unlocks say whether you've started.
 * Softcore "completed" (every achievement) ranks with mastered.
 */
fun completionOf(progress: RaProgress?, mark: PickMark?): Completion = when {
    progress?.award == RaAward.MASTERED || progress?.award == RaAward.COMPLETED -> Completion.MASTERED
    progress?.beaten == true -> Completion.BEATEN
    mark == PickMark.COMPLETED_ELSEWHERE -> Completion.BEATEN
    mark == PickMark.DNF -> Completion.DNF
    progress?.started == true -> Completion.IN_PROGRESS
    else -> Completion.NOT_STARTED
}
