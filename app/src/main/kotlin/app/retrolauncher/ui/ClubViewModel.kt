package app.retrolauncher.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.android.InstalledPackages
import app.retrolauncher.catalogue.model.Platform
import app.retrolauncher.catalogue.model.Player
import app.retrolauncher.catalogue.net.CatalogueRepository
import app.retrolauncher.club.ClubFinder
import app.retrolauncher.club.ClubPick
import app.retrolauncher.club.ClubRecheck
import app.retrolauncher.club.ClubRepository
import app.retrolauncher.club.ClubResult
import app.retrolauncher.club.FindResult
import app.retrolauncher.club.HashCache
import app.retrolauncher.club.HashLibrary
import app.retrolauncher.club.PlatformConsoles
import app.retrolauncher.club.RaConsoles
import app.retrolauncher.club.RomLinks
import app.retrolauncher.launch.EmulatorPackRepository
import app.retrolauncher.launch.ExtraPlayers
import app.retrolauncher.launch.Recommendation
import app.retrolauncher.launch.Recommendations
import app.retrolauncher.launch.RetroArchCores
import app.retrolauncher.launch.GameLauncher
import app.retrolauncher.launch.PlayerChooser
import app.retrolauncher.launch.PlayerOption
import app.retrolauncher.launch.Settings
import app.retrolauncher.library.Game
import app.retrolauncher.library.LibraryScanner
import app.retrolauncher.library.RomFiles
import app.retrolauncher.library.RomSource
import app.retrolauncher.library.SafPaths
import app.retrolauncher.ra.RaAccount
import app.retrolauncher.ra.RaAccountStore
import app.retrolauncher.ra.RaAuthException
import app.retrolauncher.ra.RaGame
import app.retrolauncher.ra.RaProgress
import app.retrolauncher.ra.RaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

/** NO_EMULATOR: the ROM was found, but no installed emulator can launch it. */
/** NOT_CHECKED: the search was stopped before reaching this pick. */
enum class PickState { CHECKING, READY, NO_EMULATOR, CHOOSE_COPY, MISSING, UNKNOWN, NOT_CHECKED }

/**
 * How far a slow search has got: reading the ROM folders ([total] 0), or hashing [console]'s
 * ROMs, [done] of [total].
 */
data class ScanProgress(val console: String? = null, val done: Int = 0, val total: Int = 0) {
    val text: String get() = if (console == null || total == 0) "Looking through your ROM folders…" else "Checking $console ROMs… $done of $total"
}

/** A club pick and whether it can be played. */
data class PickRow(
    val pick: ClubPick,
    val state: PickState = PickState.CHECKING,
    /** The ROM to launch, when READY (or found but unplayable, when NO_EMULATOR). */
    val game: Game? = null,
    /** Several copies match (CHOOSE_COPY): the player picks one, once. */
    val copies: List<Game> = emptyList(),
    /** Why it can't be played (NO_EMULATOR, MISSING, UNKNOWN). */
    val message: String? = null,
)

data class ClubState(
    val catalogue: Catalogue? = null,
    val rows: List<PickRow> = emptyList(),
    val status: String? = "Loading the club's games…",
    val sources: List<RomSource> = emptyList(),
    val hasAllFilesAccess: Boolean = false,
    val linkCount: Int = 0,
    /** Changes whenever emulator choices or installed apps change, so the UI recomposes. */
    val revision: Int = 0,
    /** The first-run setup is showing instead of the home screen. */
    val showSetup: Boolean = false,
    /**
     * The club's earlier picks (not the current ones), grouped by type, newest first. They stay
     * [PickState.CHECKING] until the past-picks page asks for them ([ClubViewModel.checkPast]).
     */
    val past: List<PickRow> = emptyList(),
    /** How the past-picks page sorts and filters. */
    val pastFilter: PastFilter = PastFilter(),
    val ra: RaState = RaState(),
    /** Your own marks (DNF, completed elsewhere), by RA game ID. */
    val marks: Map<Int, PickMark> = emptyMap(),
    /** A chosen file that doesn't match; the home screen asks what to do with it. */
    val unrecognized: UnrecognizedChoice? = null,
    /** A search that's taking a while, shown in the header with a Stop button; null when idle. */
    val scan: ScanProgress? = null,
)

/** RetroAchievements: who's signed in, and what the Web API has said. */
data class RaState(
    /** The signed-in username, or null when not signed in. */
    val user: String? = null,
    /** Your progress by RA game ID; games you've never played aren't in it. */
    val progress: Map<Int, RaProgress> = emptyMap(),
    /** Player counts by RA game ID, for the Most popular sort. */
    val players: Map<Int, Int> = emptyMap(),
    /** Game details by RA game ID, loaded as game views open. */
    val games: Map<Int, RaGame> = emptyMap(),
    /** Player counts are being fetched for History. */
    val loadingPlayers: Boolean = false,
    /** RA refused the stored key (changed or revoked). */
    val keyRefused: Boolean = false,
)

enum class ChoiceScope { GAME, PLATFORM }

/** What came of choosing a ROM file by hand. */
sealed interface ChooseOutcome {
    data object Linked : ChooseOutcome
    data class Problem(val message: String) : ChooseOutcome
    /** RetroAchievements doesn't recognise the file; [ClubState.unrecognized] asks whether to use it anyway. */
    data object Ask : ChooseOutcome
}

/** A chosen file RetroAchievements doesn't recognise, waiting for "Use anyway" or another choice. */
data class UnrecognizedChoice(val row: PickRow, val uri: Uri, val fileName: String)

/**
 * The club's current picks, matched against the user's ROMs the way the NextUI Pak does: by
 * RetroAchievements hash, with the matched file remembered per game.
 */
class ClubViewModel(app: Application) : AndroidViewModel(app) {
    private val catalogueRepo = CatalogueRepository(File(app.cacheDir, "catalogue"))
    private val clubRepo = ClubRepository(File(app.filesDir, "club"))
    private val settings = Settings(app)
    private val scanner = LibraryScanner(app.contentResolver)
    private val romFiles = RomFiles(app.contentResolver)
    private val installed = InstalledPackages(app.packageManager)
    private val launcher = GameLauncher(app)
    private val hashLibrary = HashLibrary(File(app.filesDir, "hashlib"))
    private val emulatorPack = EmulatorPackRepository(File(app.filesDir, "pack"), HashLibrary::httpGet)
    private val links = RomLinks(File(app.filesDir, "links.json"))
    private val raAccounts = RaAccountStore(app)
    private val raRepo = RaRepository(File(app.filesDir, "ra"))
    private var raAccount: RaAccount? = raAccounts.load()
    private var raPlayersJob: Job? = null
    private val finder = ClubFinder(
        hashLibrary = hashLibrary,
        hashCache = HashCache(File(app.filesDir, "hashes.tsv")),
        links = links,
        open = romFiles::open,
        stat = romFiles::stat,
    )

    /** The catalogue as downloaded, before [ExtraPlayers] are added: what "has it changed" compares. */
    private var sourceCatalogue: Catalogue? = null
    private var picks: List<ClubPick> = emptyList()
    /** The ROM folders' games from the last scan, reused to check past picks without rescanning. */
    private var scannedGames: List<Game>? = null
    /** Past picks the past-picks page has shown, so they're checked (again, after a rescan). */
    private val pastRequested = LinkedHashSet<ClubPick>()
    private var pastJob: Job? = null
    /** Set by [stopChecking]; the finder checks it between files, so a long hash stops promptly. */
    @Volatile private var stopRequested = false
    private var lastProgressAt = 0L
    /** The club's own picks, without debug test picks: what a re-check compares against. */
    private var clubPicks: List<ClubPick> = emptyList()
    private var checkJob: Job? = null
    /** The launch load, a Refresh or a re-check while it runs, so club loads never overlap. */
    private var clubJob: Job? = null

    private val _state = MutableStateFlow(
        ClubState(
            sources = settings.sources,
            hasAllFilesAccess = hasAllFilesAccess(),
            linkCount = links.count(),
            showSetup = shouldShowSetup(settings.setupDone, settings.sources.isNotEmpty()),
            pastFilter = PastFilter.decode(settings.pastFilter),
            marks = loadMarks(),
            ra = RaState(user = raAccount?.user, progress = if (raAccount != null) raRepo.cachedProgress() else emptyMap()),
        )
    )
    val state: StateFlow<ClubState> = _state

    init {
        // Someone who added ROM folders before setup existed doesn't need it.
        if (!settings.setupDone && settings.sources.isNotEmpty()) settings.setupDone = true
        // Covers the catalogue download too: the first onResume, right after launch, then
        // doesn't start a second club load beside this one.
        clubJob = viewModelScope.launch {
            catalogueRepo.loadCached()?.let { cached ->
                sourceCatalogue = cached.catalogue
                _state.update { it.copy(catalogue = ExtraPlayers.addTo(cached.catalogue)) }
            }
            loadClub(force = false)
            try {
                val loaded = catalogueRepo.load()
                if (loaded.catalogue !== sourceCatalogue) {
                    sourceCatalogue = loaded.catalogue
                    _state.update { it.copy(catalogue = ExtraPlayers.addTo(loaded.catalogue)) }
                    checkPicks()
                }
            } catch (e: Exception) {
                if (_state.value.catalogue == null) {
                    _state.update { it.copy(status = "Couldn't download the emulator catalogue: ${e.message}") }
                }
            }
        }
    }

    /** Reloads the club page (always asking the site), rescans and re-checks every pick. */
    fun refresh() {
        val running = clubJob
        clubJob = viewModelScope.launch {
            running?.join()
            hashLibrary.forget()
            loadClub(force = true)
        }
    }

    private suspend fun loadClub(force: Boolean) {
        val result = withContext(Dispatchers.IO) { clubRepo.load(force) }
        when (result) {
            is ClubResult.Ok -> {
                clubPicks = result.picks
                picks = result.picks + debugPicks()
                setPast(result.past)
                val status = offlineStatus(result.fetchedAt.takeIf { result.fromCache })
                _state.update { s ->
                    s.copy(
                        status = status,
                        rows = picks.map { p -> s.rows.firstOrNull { it.pick == p } ?: PickRow(p) },
                    )
                }
                checkPicks()
            }
            is ClubResult.NetworkError -> _state.update {
                it.copy(status = if (it.rows.isEmpty()) "Connect to Wi-Fi to load the club's games." else "Offline")
            }
            ClubResult.ParseError -> _state.update {
                it.copy(status = "The club's page has changed and no games were found. An update is needed.")
            }
        }
    }

    /**
     * Quietly checks for new picks when the player comes back (from an emulator, say). The
     * repository decides whether the saved page is still fresh, so usually this costs no network.
     * Nothing flashes: the cards only change if the club has moved on, and errors keep what's shown.
     */
    private fun recheckClub() {
        if (clubJob?.isActive == true) return
        clubJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { clubRepo.load(force = false) }
            if (result is ClubResult.Ok) setPast(result.past, current = result.picks)
            when (val recheck = ClubRecheck.of(clubPicks, result)) {
                ClubRecheck.Nothing -> Unit
                is ClubRecheck.SamePicks -> _state.update { s ->
                    // Only touch our own header messages, not, say, a catalogue error.
                    if (s.status == null || s.status.startsWith("Offline")) s.copy(status = offlineStatus(recheck.offlineSince)) else s
                }
                is ClubRecheck.NewPicks -> {
                    clubPicks = recheck.picks
                    picks = recheck.picks + debugPicks()
                    _state.update { s ->
                        s.copy(
                            status = offlineStatus(recheck.offlineSince),
                            rows = picks.map { p -> s.rows.firstOrNull { it.pick == p } ?: PickRow(p) },
                        )
                    }
                    checkPicks()
                }
            }
        }
    }

    private fun offlineStatus(since: Instant?): String? = since?.let { "Offline · games from ${ago(it)}" }

    /**
     * Debug builds only: test picks added with adb, so matching can be tried on games that
     * aren't current club picks. Each entry is "Title (CONSOLE)|https://retroachievements.org/game/ID",
     * optionally followed by "|Event label" to pose as that kind of pick (a special, say).
     */
    fun setDebugPicks(entries: List<String>) {
        settings.debugPicks = entries
        picks = clubPicks + debugPicks()
        _state.update { s -> s.copy(rows = picks.map { p -> s.rows.firstOrNull { it.pick == p } ?: PickRow(p) }) }
        checkPicks()
    }

    fun debugPickEntries(): List<String> = settings.debugPicks

    private fun debugPicks(): List<ClubPick> = settings.debugPicks.mapNotNull { entry ->
        val parts = entry.split('|').map { it.trim() }
        val title = parts.getOrNull(0).orEmpty()
        val raUrl = parts.getOrNull(1).orEmpty()
        val event = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: DEBUG_EVENT
        if (title.isEmpty() || raUrl.isEmpty()) null
        else ClubPick(event, "", title, "Added with adb for testing (debug build).", "", raUrl, "", emptyList())
    }

    /** Scans the ROM folders and works out, pick by pick, whether each can be played. */
    fun checkPicks() {
        val catalogue = _state.value.catalogue ?: return
        if (picks.isEmpty()) return
        checkJob?.cancel()
        stopRequested = false
        checkJob = viewModelScope.launch {
            _state.update { s -> s.copy(rows = s.rows.map { it.copy(state = PickState.CHECKING) }, scan = ScanProgress()) }
            val games = withContext(Dispatchers.IO) { scanner.scan(settings.sources, catalogue) }.values.flatten()
            scannedGames = games
            _state.update { it.copy(scan = null) }
            if (stopRequested) return@launch
            // Past picks already shown are checked again against the new scan.
            _state.update { s -> s.copy(past = s.past.map { if (it.pick in pastRequested) it.copy(state = PickState.CHECKING) else it }) }
            for (pick in picks) {
                if (!isActive || stopRequested) return@launch
                val row = withContext(Dispatchers.IO) { check(catalogue, pick, games) }
                if (stopRequested) return@launch // cut short: not a real answer
                _state.update { s -> s.copy(rows = s.rows.map { if (it.pick == pick) row else it }, scan = null) }
            }
            checkPast(emptyList())
        }
    }

    // ── Past picks ───────────────────────────────────────────────────────────

    /**
     * Sets the club's earlier picks, leaving out the current ones (each Past Winners tab starts
     * with the current pick of its type). Rows already checked keep their status.
     */
    private fun setPast(all: List<ClubPick>, current: List<ClubPick> = clubPicks) {
        val earlier = all.filterNot { p ->
            val type = p.type ?: return@filterNot false
            current.any { it.raUrl == p.raUrl && it.type == type }
        }
        _state.update { s -> s.copy(past = earlier.map { p -> s.past.firstOrNull { it.pick == p } ?: PickRow(p) }) }
        if (_state.value.pastFilter.rom != RomFilter.ANY) checkPast(earlier)
    }

    /**
     * Sets how past picks are sorted and filtered. Filtering by ROM needs every past pick's
     * status, not just the loaded batch, so that checks them all (in the background).
     */
    fun setPastFilter(filter: PastFilter) {
        _state.update { it.copy(pastFilter = filter) }
        settings.pastFilter = filter.encode()
        if (filter.rom != RomFilter.ANY) {
            // Asking to filter by ROM is asking to check them all, including any a Stop left unchecked.
            _state.update { s -> s.copy(past = s.past.map { if (it.state == PickState.NOT_CHECKED) it.copy(state = PickState.CHECKING) else it }) }
            checkPast(_state.value.past.map { it.pick })
        }
    }

    /**
     * Matches past picks against the ROMs, one at a time in the background: the past-picks page
     * asks for each batch as it comes into view, so a long history doesn't mean hashing every
     * console's ROMs up front. Picks asked for before are kept and re-checked after a rescan.
     */
    fun checkPast(batch: List<ClubPick>) {
        pastRequested += batch
        if (pastJob?.isActive == true) return
        val catalogue = _state.value.catalogue ?: return
        stopRequested = false
        pastJob = viewModelScope.launch {
            while (isActive && !stopRequested) {
                val next = _state.value.past.firstOrNull { it.pick in pastRequested && it.state == PickState.CHECKING }?.pick ?: break
                val games = scannedGames ?: withContext(Dispatchers.IO) { scanner.scan(settings.sources, catalogue) }.values.flatten()
                    .also { scannedGames = it }
                val row = withContext(Dispatchers.IO) { check(catalogue, next, games) }
                if (stopRequested) break
                _state.update { s -> s.copy(past = s.past.map { if (it.pick == next) row else it }, scan = null) }
            }
        }
    }

    /**
     * Stops searching: the pick being searched and any still waiting become NOT_CHECKED, and
     * Refresh games (or Search again) picks up where it left off, since hashes already read are kept.
     */
    fun stopChecking() {
        stopRequested = true
        checkJob?.cancel()
        pastJob?.cancel()
        fun stop(row: PickRow) = if (row.state == PickState.CHECKING) PickRow(row.pick, PickState.NOT_CHECKED) else row
        _state.update { s -> s.copy(rows = s.rows.map(::stop), past = s.past.map(::stop), scan = null) }
    }

    /** Reports a slow search's progress, at most a few times a second. */
    private fun progressFor(pick: ClubPick): (Int, Int) -> Unit = { done, total ->
        val now = android.os.SystemClock.uptimeMillis()
        if (done == total || now - lastProgressAt > 150) {
            lastProgressAt = now
            _state.update { it.copy(scan = ScanProgress(pick.console?.name ?: "ROM", done, total)) }
        }
    }

    /** Replaces a pick's row wherever it is: the current picks or the past ones. */
    private fun ClubState.withRow(pick: ClubPick, row: PickRow) = copy(
        rows = rows.map { if (it.pick == pick) row else it },
        past = past.map { if (it.pick == pick) row else it },
    )

    private fun check(catalogue: Catalogue, pick: ClubPick, games: List<Game>): PickRow {
        val console = pick.console
        val candidates = if (console == null) emptyList() else games.filter { g ->
            catalogue.platform(g.platformId)?.let { PlatformConsoles.holds(it, console.id) } == true
        }
        return when (val result = finder.find(pick, candidates, cancelled = { stopRequested }, progress = progressFor(pick))) {
            is FindResult.Found -> ClubFinder.choose(result)
                ?.let { found(catalogue, pick, it) }
                ?: PickRow(pick, PickState.CHOOSE_COPY, copies = result.matches)
            is FindResult.UnknownGame -> PickRow(
                pick, PickState.UNKNOWN,
                message = "Can't tell which console \"${result.label ?: "?"}\" is, so this game can't be matched.",
            )
            is FindResult.NoGames -> PickRow(
                pick, PickState.MISSING,
                message = "No ${result.console.name} games in your ROM folders. Add a folder for them, or choose the ROM file.",
            )
            is FindResult.NotFound -> PickRow(
                pick, PickState.MISSING,
                message = "None of your ${result.searched} ${result.console.name} ROMs is a version RetroAchievements " +
                    "recognizes for this game. Any file name works; it's the contents that count.",
            )
            is FindResult.NoHashList -> PickRow(
                pick, PickState.MISSING,
                message = "Couldn't download RetroAchievements' list of ${result.console.name} ROMs. Connect to Wi-Fi and refresh.",
            )
            is FindResult.Unsupported -> PickRow(
                pick, PickState.MISSING,
                message = "${result.console.name} games can't be matched automatically yet. Choose the ROM file once and it's remembered.",
            )
        }
    }

    /** Launches a READY pick. Returns an error message, or null if the emulator started. */
    /** READY if an installed emulator can launch [game], otherwise NO_EMULATOR with the reason. */
    private fun found(catalogue: Catalogue, pick: ClubPick, game: Game): PickRow {
        val platform = catalogue.platform(game.platformId)
            ?: return PickRow(pick, PickState.NO_EMULATOR, game = game, message = "Unknown platform ${game.platformId}.")
        val player = PlayerChooser.pick(catalogue, platform, game, settings.choices(game), installed::isInstalled)
        return if (player != null) PickRow(pick, PickState.READY, game = game)
        else PickRow(pick, PickState.NO_EMULATOR, game = game, message = noPlayerMessage(catalogue, platform, game))
    }

    /** Re-checks found games against installed emulators, without searching again. */
    private fun refreshEmulators() {
        val catalogue = _state.value.catalogue ?: return
        fun refreshed(row: PickRow): PickRow {
            val game = row.game
            return if (game != null && (row.state == PickState.READY || row.state == PickState.NO_EMULATOR)) found(catalogue, row.pick, game) else row
        }
        _state.update { s -> s.copy(rows = s.rows.map(::refreshed), past = s.past.map(::refreshed)) }
    }

    fun play(row: PickRow): String? {
        if (row.state == PickState.NO_EMULATOR) return row.message
        val game = row.game ?: return "This game isn't ready to play."
        val catalogue = _state.value.catalogue ?: return "The emulator catalogue isn't loaded yet."
        val platform = catalogue.platform(game.platformId) ?: return "Unknown platform ${game.platformId}."
        val player = PlayerChooser.pick(catalogue, platform, game, settings.choices(game), installed::isInstalled)
            ?: return noPlayerMessage(catalogue, platform, game)
        val error = launcher.launch(catalogue, game, player)
        if (error == null && row.pick.gameId > 0) {
            links.save(row.pick.gameId, game)
            _state.update { it.copy(linkCount = links.count()) }
        }
        return error
    }

    /** The player chose which of several matching copies to play. */
    fun chooseCopy(row: PickRow, game: Game): String? {
        links.save(row.pick.gameId, game)
        val catalogue = _state.value.catalogue ?: return "The emulator catalogue isn't loaded yet."
        val updated = found(catalogue, row.pick, game)
        _state.update { s -> s.withRow(row.pick, updated).copy(linkCount = links.count()) }
        return play(updated)
    }

    /**
     * The player picked a ROM file by hand. For consoles that can be hashed it must be a
     * version RA recognises; disc-based consoles are trusted. Returns an error message or null.
     */
    suspend fun chooseFile(row: PickRow, uri: Uri): ChooseOutcome = withContext(Dispatchers.IO) {
        fun problem(message: String) = ChooseOutcome.Problem(message)
        val catalogue = _state.value.catalogue ?: return@withContext problem("The emulator catalogue isn't loaded yet.")
        val console = row.pick.console ?: return@withContext problem("Can't tell which console this game is for.")
        val platform = platformFor(catalogue, console.id)
            ?: return@withContext problem("No emulator platform is known for ${console.name}.")
        val game = romFiles.gameForDocument(uri, platform.id) ?: return@withContext problem("Couldn't read that file.")

        val file = romFiles.open(game) ?: return@withContext problem("Couldn't read that file.")
        when (val verdict = ClubFinder.verifyChosen(hashLibrary, console, row.pick.gameId, file)) {
            ClubFinder.Verdict.Recognized, ClubFinder.Verdict.NotCheckable -> Unit
            // It may still play (another region, a revision…), just without achievements: ask.
            ClubFinder.Verdict.NotRecognized -> {
                _state.update { it.copy(unrecognized = UnrecognizedChoice(row, uri, game.fileName)) }
                return@withContext ChooseOutcome.Ask
            }
            ClubFinder.Verdict.NoHashList ->
                return@withContext problem(
                    "Couldn't check that file: RetroAchievements' list of ${console.name} ROMs " +
                        "hasn't been downloaded. Connect to Wi-Fi and try again."
                )
            is ClubFinder.Verdict.Unreadable -> return@withContext problem("Couldn't read that file: ${verdict.reason}")
        }
        link(row, game, catalogue, unverified = false)
        ChooseOutcome.Linked
    }

    /** Links the unrecognised file anyway: it plays, but RetroAchievements won't count it. */
    fun useUnrecognizedAnyway(done: (String) -> Unit) {
        val choice = _state.value.unrecognized ?: return
        _state.update { it.copy(unrecognized = null) }
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                val catalogue = _state.value.catalogue ?: return@withContext "The emulator catalogue isn't loaded yet."
                val platform = choice.row.pick.console?.let { platformFor(catalogue, it.id) }
                    ?: return@withContext "No emulator platform is known for this game."
                val game = romFiles.gameForDocument(choice.uri, platform.id) ?: return@withContext "Couldn't read that file."
                link(choice.row, game, catalogue, unverified = true)
                "${choice.row.pick.displayTitle} will play from ${game.fileName}, without achievements."
            }
            done(message)
        }
    }

    fun dismissUnrecognized() = _state.update { it.copy(unrecognized = null) }

    private fun link(row: PickRow, game: Game, catalogue: Catalogue, unverified: Boolean) {
        links.save(row.pick.gameId, game, unverified)
        val updated = found(catalogue, row.pick, game)
        _state.update { s -> s.withRow(row.pick, updated).copy(linkCount = links.count()) }
    }

    /** The pick plays from a file RetroAchievements doesn't recognise, so achievements won't unlock. */
    fun playsWithoutAchievements(row: PickRow): Boolean {
        val link = links.get(row.pick.gameId) ?: return false
        return link.unverified && row.game?.key == link.key
    }

    // ── First-run setup ──────────────────────────────────────────────────────

    /** Finishes (or skips) the first-run setup and shows the home screen. */
    fun finishSetup() {
        settings.setupDone = true
        _state.update { it.copy(showSetup = false) }
    }

    /** Shows the setup again (Settings › Set up again). */
    fun startSetup() = _state.update { it.copy(showSetup = true) }

    /** Forgets every saved game-to-ROM link and searches again. Returns how many there were. */
    fun unlinkAll(): Int {
        val n = links.clear()
        _state.update { it.copy(linkCount = 0) }
        checkPicks()
        return n
    }

    // ── Getting an emulator ──────────────────────────────────────────────────

    /**
     * Whether the pick has no emulator to play it: found but unplayable ([PickState.NO_EMULATOR]),
     * or missing and nothing installed plays its console either.
     */
    fun needsEmulator(row: PickRow): Boolean {
        if (row.state == PickState.NO_EMULATOR) return true
        if (row.state != PickState.MISSING) return false
        val catalogue = _state.value.catalogue ?: return false
        val console = row.pick.console ?: return false
        return catalogue.platforms
            .filter { PlatformConsoles.holds(it, console.id) }
            .none { catalogue.installedPlayers(it, installed::isInstalled).isNotEmpty() }
    }

    /**
     * Emulators to suggest for the pick's platform, from the Obtainium Emulation Pack (loaded
     * from the network, kept a week). Null if the pack couldn't be loaded and was never saved.
     */
    suspend fun emulatorSuggestions(row: PickRow): List<Recommendation>? = withContext(Dispatchers.IO) {
        val catalogue = _state.value.catalogue ?: return@withContext emptyList()
        val platform = row.game?.let { catalogue.platform(it.platformId) }
            ?: row.pick.console?.let { platformFor(catalogue, it.id) }
            ?: return@withContext emptyList()
        val pack = emulatorPack.load() ?: return@withContext null
        Recommendations.forPlatform(catalogue, platform, pack)
    }

    /** Prefer a platform with an installed emulator, so a hand-picked file can launch. */
    private fun platformFor(catalogue: Catalogue, consoleId: Int): Platform? {
        val platforms = catalogue.platforms.filter { PlatformConsoles.holds(it, consoleId) }
        return platforms.firstOrNull { catalogue.installedPlayers(it, installed::isInstalled).isNotEmpty() }
            ?: platforms.firstOrNull()
    }

    // ── Emulator choice ──────────────────────────────────────────────────────

    fun options(game: Game): List<PlayerOption> {
        val catalogue = _state.value.catalogue ?: return emptyList()
        val platform = catalogue.platform(game.platformId) ?: return emptyList()
        return PlayerChooser.options(catalogue, platform, game, installed::isInstalled)
    }

    /** The RetroArch core the game would launch with, or null if it doesn't play in RetroArch. */
    fun retroArchCore(game: Game): String? {
        val catalogue = _state.value.catalogue ?: return null
        return playerFor(game)?.let { RetroArchCores.coreName(catalogue, it) }
    }

    fun playerFor(game: Game): Player? {
        val catalogue = _state.value.catalogue ?: return null
        val platform = catalogue.platform(game.platformId) ?: return null
        return PlayerChooser.pick(catalogue, platform, game, settings.choices(game), installed::isInstalled)
    }

    fun choiceSource(game: Game): ChoiceScope? {
        val picked = playerFor(game) ?: return null
        val choices = settings.choices(game)
        fun matches(id: String?) = id != null && (id == picked.id || id in picked.was)
        return when {
            matches(choices.gameOverride) -> ChoiceScope.GAME
            matches(choices.platformDefault) -> ChoiceScope.PLATFORM
            else -> null
        }
    }

    fun platformName(game: Game): String = _state.value.catalogue?.platform(game.platformId)?.name ?: game.platformId

    fun setChoice(game: Game, player: Player?, scope: ChoiceScope) {
        when (scope) {
            ChoiceScope.GAME -> settings.setGameOverride(game, player?.id)
            ChoiceScope.PLATFORM -> settings.setPlatformDefault(game.platformId, player?.id)
        }
        _state.update { it.copy(revision = it.revision + 1) }
        refreshEmulators()
    }

    private fun noPlayerMessage(catalogue: Catalogue, platform: Platform, game: Game): String {
        val options = PlayerChooser.options(catalogue, platform, game, installed::isInstalled)
        if (options.isNotEmpty()) return options.first().problem ?: "No usable emulator."
        val names = platform.players.filter { catalogue.playerAccepts(it, game.fileName) }
            .map { it.name.substringBefore(" - ") }.distinct().take(4)
        return "No emulator for ${platform.name} is installed. Try: ${names.joinToString()}"
    }

    // ── Folders and permissions ──────────────────────────────────────────────

    /** Call from onResume: apps or permissions may have changed while the user was away. */
    fun onResume() {
        installed.refresh()
        val access = hasAllFilesAccess()
        val changed = access != _state.value.hasAllFilesAccess
        _state.update { it.copy(hasAllFilesAccess = access, revision = it.revision + 1) }
        // An emulator may have been installed or removed while the user was away.
        if (changed) checkPicks() else refreshEmulators()
        // The club may have moved on (a new week at midnight UTC) while the launcher stayed open.
        recheckClub()
        // Back from a game: achievements may have unlocked.
        refreshRa()
    }

    // ── Marks ────────────────────────────────────────────────────────────────

    private fun loadMarks(): Map<Int, PickMark> = settings.marks().mapNotNull { (id, name) ->
        PickMark.entries.firstOrNull { it.name == name }?.let { id to it }
    }.toMap()

    /** Sets or clears ([mark] null) your own mark on a game, by its RA game ID. */
    fun setMark(gameId: Int, mark: PickMark?) {
        if (gameId <= 0) return
        settings.setMark(gameId, mark?.name)
        _state.update { it.copy(marks = loadMarks()) }
    }

    // ── RetroAchievements ────────────────────────────────────────────────────

    /** Fetches your progress if the cached copy is older than [maxAgeMs]. Offline, the cache stays. */
    fun refreshRa(maxAgeMs: Long = RaRepository.PROGRESS_MAX_AGE_MS) {
        val account = raAccount ?: return
        if (!raRepo.progressStale(maxAgeMs)) return
        viewModelScope.launch {
            try {
                val progress = withContext(Dispatchers.IO) { raRepo.fetchProgress(account) }
                _state.update { it.copy(ra = it.ra.copy(progress = progress, keyRefused = false)) }
            } catch (_: RaAuthException) {
                _state.update { it.copy(ra = it.ra.copy(keyRefused = true)) }
            } catch (_: Exception) {
                // Offline: keep showing the cached progress.
            }
        }
    }

    /**
     * Signs in: checks the username and key by fetching your progress, and keeps them only if
     * RetroAchievements accepts them. [done] gets null on success, else what went wrong.
     */
    fun raSignIn(user: String, key: String, done: (String?) -> Unit) {
        val account = RaAccount(user.trim(), key.trim())
        if (account.user.isEmpty() || account.key.isEmpty()) return done("Enter your username and Web API key.")
        viewModelScope.launch {
            val error = try {
                withContext(Dispatchers.IO) { raRepo.clear(); raRepo.fetchProgress(account) }.let { progress ->
                    withContext(Dispatchers.IO) { raAccounts.save(account) }
                    raAccount = account
                    _state.update { it.copy(ra = RaState(user = account.user, progress = progress)) }
                }
                null
            } catch (_: RaAuthException) {
                "RetroAchievements didn't accept that username and key. Check both, then try again."
            } catch (_: Exception) {
                "Couldn't reach RetroAchievements. Check the Wi-Fi, then try again."
            }
            done(error)
        }
    }

    fun raSignOut() {
        raPlayersJob?.cancel()
        raAccount = null
        raAccounts.clear()
        viewModelScope.launch(Dispatchers.IO) { raRepo.clear() }
        _state.update { it.copy(ra = RaState()) }
    }

    /** Loads a game's achievements and your progress for its game view. */
    fun loadRaGame(gameId: Int) {
        val account = raAccount ?: return
        if (gameId <= 0) return
        viewModelScope.launch {
            val game = try {
                withContext(Dispatchers.IO) { raRepo.game(account, gameId) }
            } catch (_: RaAuthException) {
                _state.update { it.copy(ra = it.ra.copy(keyRefused = true)) }
                null
            } ?: return@launch
            _state.update { s ->
                s.copy(ra = s.ra.copy(
                    games = s.ra.games + (gameId to game),
                    players = s.ra.players + (gameId to game.players),
                    // The game's own answer is the freshest progress for it.
                    progress = if (game.progress.started) s.ra.progress + (gameId to game.progress) else s.ra.progress,
                ))
            }
        }
    }

    /**
     * Player counts for [gameIds], for History's Most popular sort: from the cache when recent,
     * else one request at a time, with a pause between them to go easy on RetroAchievements.
     */
    fun loadPlayers(gameIds: Collection<Int>) {
        val account = raAccount ?: return
        if (raPlayersJob?.isActive == true) return
        raPlayersJob = viewModelScope.launch {
            val todo = gameIds.filter { it > 0 && it !in _state.value.ra.players }.distinct()
            if (todo.isEmpty()) return@launch
            _state.update { it.copy(ra = it.ra.copy(loadingPlayers = true)) }
            try {
                for (id in todo) {
                    if (!isActive) break
                    val cached = withContext(Dispatchers.IO) { raRepo.cachedPlayers(id) }
                    val players = cached ?: try {
                        withContext(Dispatchers.IO) { raRepo.game(account, id, RaRepository.PLAYERS_MAX_AGE_MS) }?.players
                            .also { delay(250) }
                    } catch (_: RaAuthException) {
                        _state.update { it.copy(ra = it.ra.copy(keyRefused = true)) }
                        break
                    } ?: continue
                    _state.update { it.copy(ra = it.ra.copy(players = it.ra.players + (id to players))) }
                }
            } finally {
                _state.update { it.copy(ra = it.ra.copy(loadingPlayers = false)) }
            }
        }
    }

    fun addSource(source: RomSource) {
        settings.sources = (settings.sources + source).distinctBy { it.key }
        _state.update { it.copy(sources = settings.sources) }
        checkPicks()
    }

    fun removeSource(source: RomSource) {
        settings.sources = settings.sources.filter { it.key != source.key }
        _state.update { it.copy(sources = settings.sources) }
        checkPicks()
    }

    /** ROM folders on internal storage and SD cards that can be added as raw paths. */
    fun rawFolderSuggestions(): List<String> {
        if (!hasAllFilesAccess()) return emptyList()
        val roots = buildList {
            add(Environment.getExternalStorageDirectory())
            File("/storage").listFiles()?.filter { it.name != "emulated" && it.name != "self" }?.let(::addAll)
        }
        val names = listOf("ROMs", "Roms", "roms", "Games", "Emulation/roms")
        // Folders already added, by key, and by real path for folders added with the picker.
        val existing = settings.sources.map { it.key.lowercase() }.toSet()
        val existingPaths = settings.sources.mapNotNull { (it as? RomSource.Saf)?.let { s -> SafPaths.treeUriToPath(s.treeUri) } }
            .map { it.lowercase() }.toSet()
        return roots.flatMap { root -> names.map { File(root, it) } }
            .filter { it.isDirectory }
            .map { it.canonicalPath }
            // Shared storage is case-insensitive, so ROMs, Roms and roms are usually one folder.
            .distinctBy { it.lowercase() }
            .filter { RomSource.RawPath(it).key.lowercase() !in existing && it.lowercase() !in existingPaths }
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun ago(time: Instant): String {
        val d = Duration.between(time, Instant.now())
        return when {
            d.toMinutes() < 60 -> "${d.toMinutes().coerceAtLeast(1)} min ago"
            d.toHours() < 48 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} days ago"
        }
    }
}

/** Setup shows on first run: until it's finished or skipped, and only while there are no ROM folders. */
fun shouldShowSetup(setupDone: Boolean, hasSources: Boolean): Boolean = !setupDone && !hasSources

/** The event label of test picks added in debug builds. */
const val DEBUG_EVENT = "Test pick (debug build)"

/** For messages: the console a pick is for, or its raw label. */
fun ClubPick.consoleName(): String = console?.name ?: RaConsoles.labelOf(title) ?: ""
