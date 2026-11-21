package app.retrolauncher.ra

import app.retrolauncher.club.ClubPick
import app.retrolauncher.ui.Completion
import app.retrolauncher.ui.CompletionFilter
import app.retrolauncher.ui.PickMark
import app.retrolauncher.ui.completionOf
import app.retrolauncher.ui.PastFilter
import app.retrolauncher.ui.PastSort
import app.retrolauncher.ui.PickRow
import app.retrolauncher.ui.applyPastFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val account = RaAccount("player", "secretkey123")

/** API_GetGameInfoAndUserProgress, shaped like RetroAchievements' documented HTTP response. */
private val GAME = """
{"ID":1,"Title":"Sonic the Hedgehog","ConsoleID":1,"NumDistinctPlayers":27080,"NumDistinctPlayersCasual":27080,
 "NumDistinctPlayersHardcore":20000,"NumAchievements":4,
 "Achievements":{
  "9":{"ID":9,"Title":"That Was Easy","Description":"Complete Green Hill Act 1","Points":3,"DisplayOrder":1,"type":"progression",
       "DateEarned":"2016-03-12 17:47:29","DateEarnedHardcore":"2016-03-12 17:47:29"},
  "10":{"ID":10,"Title":"Marble","Description":"Complete Marble Zone","Points":5,"DisplayOrder":2,"type":"progression",
       "DateEarned":"2016-03-12 18:00:00"},
  "11":{"ID":11,"Title":"Ring Hoarder","Description":"Get 100 rings","Points":5,"DisplayOrder":3,"type":null},
  "12":{"ID":12,"Title":"Doctor's Defeat","Description":"Beat the final boss","Points":10,"DisplayOrder":4,"type":"win_condition"}
 },
 "NumAwardedToUser":2,"NumAwardedToUserHardcore":1,"UserCompletion":"50.00%","UserCompletionHardcore":"25.00%",
 "HighestAwardKind":null,"HighestAwardDate":null}
""".trimIndent()

class RaApiTest {
    @Test
    fun `game info parses achievements, players and progress`() {
        var url = ""
        val game = RaApi { url = it; 200 to GAME }.game(account, 1)
        assertTrue(url.startsWith("https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php?"))
        assertTrue("u=player" in url && "g=1" in url && "y=secretkey123" in url)
        assertEquals(27080, game.players)
        assertEquals(4, game.achievements.size)
        assertEquals(RaProgress(1, awarded = 2, awardedHardcore = 1, total = 4), game.progress)
        assertNull(game.achievements.first { it.id == 11 }.type)
        // Hardcore counts: Marble was only earned in softcore, so it's next.
        assertEquals("Marble", game.nextToBeat()?.title)
    }

    @Test
    fun `next to beat falls back to the win condition, and is null once all are done`() {
        val game = RaApi.parseGame(kotlinx.serialization.json.Json.parseToJsonElement(GAME) as kotlinx.serialization.json.JsonObject)
        val progressionDone = game.copy(achievements = game.achievements.map { if (it.type == "progression") it.copy(earnedHardcore = true) else it })
        assertEquals("Doctor's Defeat", progressionDone.nextToBeat()?.title)
        val allDone = game.copy(achievements = game.achievements.map { it.copy(earned = true, earnedHardcore = true) })
        assertNull(allDone.nextToBeat())
    }

    @Test
    fun `completion progress pages through every game`() {
        val urls = mutableListOf<String>()
        val pages = listOf(
            """{"Count":2,"Total":3,"Results":[
                {"GameID":1,"NumAwarded":5,"NumAwardedHardcore":5,"MaxPossible":5,"HighestAwardKind":"mastered"},
                {"GameID":2,"NumAwarded":3,"NumAwardedHardcore":0,"MaxPossible":10,"HighestAwardKind":"beaten-softcore"}]}""",
            """{"Count":1,"Total":3,"Results":[{"GameID":3,"NumAwarded":0,"NumAwardedHardcore":0,"MaxPossible":8,"HighestAwardKind":null}]}""",
        )
        val progress = RaApi { urls += it; 200 to pages[urls.size - 1] }.completion(account)
        assertEquals(2, urls.size)
        assertTrue("o=0" in urls[0] && "c=500" in urls[0] && "o=2" in urls[1])
        assertEquals(RaAward.MASTERED, progress.getValue(1).award)
        val softcore = progress.getValue(2)
        assertTrue(softcore.softcore)
        assertEquals(3, softcore.shown)
        assertFalse(progress.getValue(3).started)
    }

    @Test
    fun `supported files and the console's game list parse`() {
        val files = RaApi { 200 to """{"Results":[
            {"MD5":"aa","Name":"Kirby's Halloween Adventure (v1.4) (Kirb-Star).nes","Labels":["rapatches"],"PatchUrl":"https://github.com/RetroAchievements/RAPatches/raw/main/NES/x.zip"},
            {"MD5":"bb","Name":"Kirby's Adventure (USA).nes","Labels":["nointro"],"PatchUrl":null}]}""" }.supportedFiles(account, 20071)
        assertEquals(listOf("Kirby's Halloween Adventure (v1.4) (Kirb-Star).nes", "Kirby's Adventure (USA).nes"), files.map { it.name })
        assertEquals(listOf(true, false), files.map { it.patchUrl != null })
        val games = RaApi.parseGameList("""[{"Title":"Kirby's Adventure","ID":1729},{"Title":"~Hack~ Kirby's Halloween Adventure","ID":20071}]""")
        assertEquals(listOf(1729 to "Kirby's Adventure", 20071 to "~Hack~ Kirby's Halloween Adventure"), games)
    }

    @Test(expected = RaAuthException::class)
    fun `a refused key is reported as such`() {
        RaApi { 401 to """{"message":"Unauthenticated."}""" }.completion(account)
    }

    @Test
    fun `the account never prints its key`() {
        assertFalse("secretkey123" in account.toString())
    }
}

class RaRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `progress and games are cached and read back`() {
        var calls = 0
        var now = 1_000_000L
        val api = RaApi { url ->
            calls++
            200 to if ("Completion" in url) """{"Count":1,"Total":1,"Results":[{"GameID":7,"NumAwarded":2,"NumAwardedHardcore":2,"MaxPossible":9,"HighestAwardKind":"beaten-hardcore"}]}""" else GAME
        }
        val repo = RaRepository(tmp.root, api) { now }
        assertTrue(repo.progressStale(60_000))
        repo.fetchProgress(account)
        assertEquals(RaProgress(7, 2, 2, 9, RaAward.BEATEN_HARDCORE), repo.cachedProgress()[7])

        val first = repo.game(account, 1)
        val second = repo.game(account, 1) // fresh enough: from the cache
        assertEquals(2, calls)
        assertEquals(first, second)
        assertEquals(27080, repo.cachedPlayers(1))

        repo.clear()
        assertTrue(repo.cachedProgress().isEmpty())
        assertNull(repo.cachedPlayers(1))
    }
}

class RaPastFilterTest {
    private fun row(id: Int, event: String) =
        PickRow(ClubPick(event, "", "Game $id (SNES)", "", "", "https://retroachievements.org/game/$id", "", emptyList()))

    private val a = row(1, "Game of the Week November 8 - 14, 2026")
    private val b = row(2, "Game of the Week November 1 - 7, 2026")
    private val c = row(3, "Game of the Month October 2026")
    private val d = row(4, "Game of the Month September 2026")
    private val e = row(5, "Game of the Month August 2026")
    private val f = row(6, "Game of the Month July 2026")
    private val all = listOf(a, b, c, d, e, f)
    private val progress = mapOf(
        1 to RaProgress(1, 3, 3, 10),
        2 to RaProgress(2, 10, 10, 10, RaAward.MASTERED),
        3 to RaProgress(3, 6, 0, 10, RaAward.BEATEN_SOFTCORE),
        6 to RaProgress(6, 4, 4, 10, RaAward.BEATEN_HARDCORE),
    )
    // 5 was finished elsewhere; 4 was given up; 6 was given up but RA says beaten, so RA wins.
    private val marks = mapOf(5 to PickMark.COMPLETED_ELSEWHERE, 4 to PickMark.DNF, 6 to PickMark.DNF)

    private fun ids(filter: PastFilter, players: Map<Int, Int> = emptyMap()) =
        applyPastFilter(all, filter, progress, players, marks).map { it.pick.gameId }

    @Test
    fun `completion combines RA awards and your marks`() {
        assertEquals(listOf(1), ids(PastFilter(completion = CompletionFilter.IN_PROGRESS)))
        assertEquals(listOf(3, 5, 6), ids(PastFilter(completion = CompletionFilter.BEATEN)))
        assertEquals(listOf(2), ids(PastFilter(completion = CompletionFilter.MASTERED)))
        assertEquals(listOf(4), ids(PastFilter(completion = CompletionFilter.DNF)))
        assertEquals(emptyList<Int>(), ids(PastFilter(completion = CompletionFilter.NOT_STARTED)))
        // Unbeaten is not started plus in progress; DNF stays out.
        assertEquals(listOf(1), ids(PastFilter(completion = CompletionFilter.UNBEATEN)))
        assertEquals(Completion.MASTERED, completionOf(RaProgress(9, 9, 0, 9, RaAward.COMPLETED), PickMark.DNF))
        assertEquals(Completion.NOT_STARTED, completionOf(null, null))
    }

    @Test
    fun `most popular sorts by players, unknown last`() {
        assertEquals(listOf(3, 1, 2, 4, 5, 6), ids(PastFilter(sort = PastSort.MOST_POPULAR), mapOf(1 to 500, 2 to 20, 3 to 9000)))
    }

    @Test
    fun `what's available depends on signing in and marks, and completion is saved`() {
        val filter = PastFilter(sort = PastSort.MOST_POPULAR, completion = CompletionFilter.UNBEATEN)
        assertEquals(PastFilter(), filter.available(signedIn = false, hasMarks = false))
        assertEquals(PastFilter(completion = CompletionFilter.UNBEATEN), filter.available(signedIn = false, hasMarks = true))
        assertEquals(filter, filter.available(signedIn = true, hasMarks = false))
        assertEquals(filter, PastFilter.decode(filter.encode()))
    }
}
