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
import app.retrolauncher.launch.ExtraPlayers
import app.retrolauncher.launch.GameLauncher
import app.retrolauncher.launch.PlayerChooser
import app.retrolauncher.launch.PlayerOption
import app.retrolauncher.launch.Settings
import app.retrolauncher.library.Game
import app.retrolauncher.library.LibraryScanner
import app.retrolauncher.library.RomFiles
import app.retrolauncher.library.RomSource
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
enum class PickState { CHECKING, READY, NO_EMULATOR, CHOOSE_COPY, MISSING, UNKNOWN }

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
)

enum class ChoiceScope { GAME, PLATFORM }

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
    private val links = RomLinks(File(app.filesDir, "links.json"))
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
    /** The club's own picks, without debug test picks: what a re-check compares against. */
    private var clubPicks: List<ClubPick> = emptyList()
    private var checkJob: Job? = null
    /** The launch load, a Refresh or a re-check while it runs, so club loads never overlap. */
    private var clubJob: Job? = null

    private val _state = MutableStateFlow(
        ClubState(sources = settings.sources, hasAllFilesAccess = hasAllFilesAccess(), linkCount = links.count())
    )
    val state: StateFlow<ClubState> = _state

    init {
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
        checkJob = viewModelScope.launch {
            _state.update { s -> s.copy(rows = s.rows.map { it.copy(state = PickState.CHECKING) }) }
            val games = withContext(Dispatchers.IO) { scanner.scan(settings.sources, catalogue) }.values.flatten()
            for (pick in picks) {
                if (!isActive) return@launch
                val row = withContext(Dispatchers.IO) { check(catalogue, pick, games) }
                _state.update { s -> s.copy(rows = s.rows.map { if (it.pick == pick) row else it }) }
            }
        }
    }

    private fun check(catalogue: Catalogue, pick: ClubPick, games: List<Game>): PickRow {
        val console = pick.console
        val candidates = if (console == null) emptyList() else games.filter { g ->
            catalogue.platform(g.platformId)?.let { PlatformConsoles.holds(it, console.id) } == true
        }
        return when (val result = finder.find(pick, candidates)) {
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
        _state.update { s ->
            s.copy(rows = s.rows.map { row ->
                val game = row.game
                if (game != null && (row.state == PickState.READY || row.state == PickState.NO_EMULATOR)) found(catalogue, row.pick, game) else row
            })
        }
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
        _state.update { s -> s.copy(rows = s.rows.map { if (it.pick == row.pick) updated else it }, linkCount = links.count()) }
        return play(updated)
    }

    /**
     * The player picked a ROM file by hand. For consoles that can be hashed it must be a
     * version RA recognises; disc-based consoles are trusted. Returns an error message or null.
     */
    suspend fun chooseFile(row: PickRow, uri: Uri): String? = withContext(Dispatchers.IO) {
        val catalogue = _state.value.catalogue ?: return@withContext "The emulator catalogue isn't loaded yet."
        val console = row.pick.console ?: return@withContext "Can't tell which console this game is for."
        val platform = platformFor(catalogue, console.id)
            ?: return@withContext "No emulator platform is known for ${console.name}."
        val game = romFiles.gameForDocument(uri, platform.id) ?: return@withContext "Couldn't read that file."

        val file = romFiles.open(game) ?: return@withContext "Couldn't read that file."
        when (val verdict = ClubFinder.verifyChosen(hashLibrary, console, row.pick.gameId, file)) {
            ClubFinder.Verdict.Recognized, ClubFinder.Verdict.NotCheckable -> Unit
            ClubFinder.Verdict.NotRecognized ->
                return@withContext "That file isn't a version RetroAchievements recognizes for ${row.pick.displayTitle}."
            ClubFinder.Verdict.NoHashList ->
                return@withContext "Couldn't check that file: RetroAchievements' list of ${console.name} ROMs " +
                    "hasn't been downloaded. Connect to Wi-Fi and try again."
            is ClubFinder.Verdict.Unreadable -> return@withContext "Couldn't read that file: ${verdict.reason}"
        }
        links.save(row.pick.gameId, game)
        val updated = found(catalogue, row.pick, game)
        _state.update { s -> s.copy(rows = s.rows.map { if (it.pick == row.pick) updated else it }, linkCount = links.count()) }
        null
    }

    /** Forgets every saved game-to-ROM link and searches again. Returns how many there were. */
    fun unlinkAll(): Int {
        val n = links.clear()
        _state.update { it.copy(linkCount = 0) }
        checkPicks()
        return n
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
        val existing = settings.sources.map { it.key.lowercase() }.toSet()
        return roots.flatMap { root -> names.map { File(root, it) } }
            .filter { it.isDirectory }
            .map { it.canonicalPath }
            // Shared storage is case-insensitive, so ROMs, Roms and roms are usually one folder.
            .distinctBy { it.lowercase() }
            .filter { RomSource.RawPath(it).key.lowercase() !in existing }
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

/** The event label of test picks added in debug builds. */
const val DEBUG_EVENT = "Test pick (debug build)"

/** For messages: the console a pick is for, or its raw label. */
fun ClubPick.consoleName(): String = console?.name ?: RaConsoles.labelOf(title) ?: ""
