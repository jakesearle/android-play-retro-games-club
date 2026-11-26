package app.retrolauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retrolauncher.library.RomSource

/** The four kinds of club pick, in the order the home screen shows them. */
enum class PickType(val label: String) {
    WEEK("Game of the Week"),
    MONTH("Game of the Month"),
    ROMHACK("Romhack of the Month"),
    SEASON("Game of the Season"),
}

/** A home-screen card: one of the four pick types (maybe with no current pick), a special, or a test pick. */
internal data class Card(
    val label: String,
    val row: PickRow?,
    /** A line in the card's bottom-left corner, such as when a past pick ran. */
    val note: String? = null,
)

/**
 * The home screen's cards: the four pick types in order (empty when there's no current pick),
 * then any other pick, such as a special event or a second pick of one type, labelled with its
 * own event name, then test picks (debug builds only).
 */
/**
 * How long a current pick has left, for the bottom of its card: "3 days left", "Last day" in its
 * final 24 hours, or null once it's over (the page will move on) or when the end isn't known.
 * Days round up, so "2 days left" means more than one day.
 */
internal fun timeLeftLabel(endsAt: java.time.Instant?, now: java.time.Instant): String? {
    endsAt ?: return null
    val millis = java.time.Duration.between(now, endsAt).toMillis()
    if (millis <= 0) return null
    val days = (millis + DAY_MS - 1) / DAY_MS
    return if (days <= 1) "Last day" else "$days days left"
}

private const val DAY_MS = 24L * 60 * 60 * 1000

internal fun homeCards(rows: List<PickRow>): List<Card> {
    val used = mutableSetOf<PickRow>()
    val basic = PickType.entries.map { type ->
        val row = rows.firstOrNull { it !in used && it.pick.event != DEBUG_EVENT && it.pick.event.startsWith(type.label) }
        row?.let(used::add)
        Card(type.label, row)
    }
    val specials = rows.filter { it !in used && it.pick.event != DEBUG_EVENT }
        .map { Card(it.pick.eventLabel.ifEmpty { "Special" }, it) }
    val tests = rows.filter { it.pick.event == DEBUG_EVENT }.map { Card("Test pick", it) }
    return basic + specials + tests
}

/**
 * The home screen: one card per kind of pick. A opens the game view; X plays straight away
 * (or opens the game view when the game can't be played yet); Start opens Settings.
 */
@Composable
fun ClubScreen(
    vm: ClubViewModel,
    onAddFolder: () -> Unit,
    onOpenFolder: (RomSource) -> Unit,
    onRequestAllFiles: () -> Unit,
    onChooseFile: (PickRow) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenLink: (link: String, fallbackUrl: String) -> Unit,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val usageContext = LocalContext.current
    fun requestUsageAccess() {
        runCatching { usageContext.startActivity(vm.usageAccessIntent()) }
            .onFailure { showMessage("Couldn't open Usage Access settings.") }
    }
    if (state.showSetup) {
        SetupScreen(vm, state, onAddFolder, onRequestAllFiles, ::requestUsageAccess, onOpenUrl, showMessage, modifier)
        return
    }
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    /** The card that has focus right now, or null; [focusedIndex] remembers the last one. */
    var focusedCard by remember { mutableStateOf<Int?>(null) }
    /** The card to put focus back on when the game view closes, or -1. */
    var returnTo by rememberSaveable { mutableIntStateOf(-1) }
    /** The open game view's pick, as [PickRow.key]; null on the home or past-picks page. */
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var raOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var updateOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The past-picks page. Kept here so it comes back as it was when its game view closes.
    var pastOpen by rememberSaveable { mutableStateOf(false) }
    var pastShown by rememberSaveable { mutableIntStateOf(PAST_BATCH) }
    var pastFocus by rememberSaveable { mutableIntStateOf(0) }

    // Each current pick's time left goes at the bottom of its card; worked out again on resume.
    val cards = remember(state.rows, state.revision) {
        val now = java.time.Instant.now()
        homeCards(state.rows).map { card ->
            val row = card.row ?: return@map card
            if (row.pick.event == DEBUG_EVENT) card else card.copy(note = timeLeftLabel(app.retrolauncher.club.ClubPage.endsAt(row.pick), now))
        }
    }
    val grid = rememberLazyGridState()
    val gridRows = rememberGridRows(grid, CARD_HEIGHT + 16.dp)
    val requesters = remember(cards.size) { List(cards.size) { FocusRequester() } }
    // Looked up by key so the game view follows the row as its status updates.
    val open = openKey?.let { k -> state.rows.firstOrNull { it.key == k } ?: state.past.firstOrNull { it.key == k } }

    fun openGame(row: PickRow) {
        if (openKey == null && !pastOpen) returnTo = focusedIndex
        openKey = row.key
    }

    fun openPast() {
        returnTo = focusedIndex
        pastOpen = true
    }

    fun play(row: PickRow) {
        if (row.state == PickState.READY) vm.play(row)?.let(showMessage)
        else openGame(row) // shows what's missing and what to do
    }

    // L1/R1 in the game view step through the list it was opened from: History as filtered and
    // sorted, or the home screen's cards.
    val stepList = remember(pastOpen, state.past, state.pastFilter, state.ra, state.marks, cards) {
        if (pastOpen) historyRows(state.past, state.pastFilter, state.ra, state.marks) else cards.mapNotNull { it.row }
    }
    val openIndex = open?.let { o -> stepList.indexOfFirst { it.key == o.key } } ?: -1

    fun stepTo(index: Int) {
        val row = stepList[index]
        openKey = row.key
        // Coming back, focus lands on the game you stepped to.
        if (pastOpen) {
            pastFocus = index
            if (index >= pastShown) pastShown = (index / PAST_BATCH + 1) * PAST_BATCH
        } else {
            returnTo = cards.indexOfFirst { it.row?.key == row.key }
        }
    }

    if (open != null) {
        BackHandler { openKey = null }
        // Keyed by game, so stepping to another one starts its view afresh (focus, dialogs).
        key(open.key) {
        GameView(
            row = open,
            vm = vm,
            ra = state.ra,
            mark = state.marks[open.pick.gameId],
            onPrevious = if (openIndex > 0) ({ stepTo(openIndex - 1) }) else null,
            onNext = if (openIndex in 0 until stepList.lastIndex) ({ stepTo(openIndex + 1) }) else null,
            revision = state.revision,
            hasSources = state.sources.isNotEmpty(),
            onBack = { openKey = null },
            onPlay = { play(open) },
            onChooseFile = { onChooseFile(open) },
            onAddFolder = onAddFolder,
            onOpenUrl = onOpenUrl,
            onOpenLink = onOpenLink,
            showMessage = showMessage,
            modifier = modifier,
        )
        }
    } else if (pastOpen) {
        BackHandler { pastOpen = false }
        PastPicksScreen(
            past = state.past,
            ra = state.ra,
            marks = state.marks,
            scan = state.scan,
            onStop = vm::stopChecking,
            onLoadPlayers = vm::loadPlayers,
            filter = state.pastFilter,
            onFilter = { vm.setPastFilter(it); pastShown = PAST_BATCH; pastFocus = 0 },
            shown = pastShown,
            onShowMore = { pastShown += PAST_BATCH },
            focusIndex = pastFocus,
            onFocus = { pastFocus = it },
            onCheck = { rows -> vm.checkPast(rows.map { it.pick }) },
            onOpen = ::openGame,
            onPlay = ::play,
            modifier = modifier,
        )
    } else {
        Column(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .onPad { pad ->
                    when (pad) {
                        Pad.START -> { menuOpen = true; true }
                        Pad.SELECT -> { openPast(); true }
                        Pad.Y -> if (state.scan != null) { vm.stopChecking(); true } else false
                        else -> false
                    }
                },
        ) {
            Header(
                state.scan?.text ?: state.status,
                onSettings = { menuOpen = true },
                onPast = ::openPast,
                onStop = if (state.scan != null) vm::stopChecking else null,
            )
            // Two cards side by side, scrolling vertically; the grid keeps the focused card in view.
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(cards, key = { i, c -> "$i:${c.label}" }) { index, card ->
                    PickCard(
                        card = card,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CARD_HEIGHT)
                            .focusRequester(requesters[index])
                            .gridRowKeys(gridRows, index, requesters)
                            .onFocusChanged {
                                // While returning from the game view, Compose briefly focuses some
                                // other card on its own; don't let that overwrite where we're going back to.
                                if (it.isFocused) { if (returnTo < 0) focusedIndex = index; focusedCard = index }
                                else if (focusedCard == index) focusedCard = null
                            },
                        onOpen = { card.row?.let(::openGame) ?: showMessage("No ${card.label} right now.") },
                        onPlay = { card.row?.let(::play) ?: showMessage("No ${card.label} right now.") },
                        completion = card.row?.let { completionOf(state.ra.progress[it.pick.gameId], state.marks[it.pick.gameId]) },
                    )
                }
            }
            Footer(start = {
                PadHint("Start", "Settings")
                PadHint("Select", "History")
                if (state.scan != null) PadHint("Y", "Stop checking")
            }) {
                PadHint("X", "Play")
                PadHint("A", "Details")
            }
        }
        // Put focus on the card the player was on, so the first D-pad press moves instead of
        // just showing where focus is (see [takeControllerFocus]).
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val view = LocalView.current
        LaunchedEffect(cards.size, windowFocused) {
            if (!windowFocused) return@LaunchedEffect
            val index = (if (returnTo >= 0) returnTo else focusedIndex).coerceIn(0, cards.lastIndex)
            // The grid lays out its cards a frame after it reappears (coming back from the game
            // view), and off-screen cards aren't composed at all, so wait for layout, scroll the
            // card into view if needed, and retry until focus lands on it.
            withFrameNanos { }
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
            for (attempt in 0 until 10) {
                takeControllerFocus(view, requesters[index])
                withFrameNanos { }
                if (focusedCard == index) break
            }
            focusedIndex = index
            returnTo = -1
        }
    }

    if (menuOpen) {
        MenuDialog(
            vm = vm,
            state = state,
            onAddFolder = { menuOpen = false; onAddFolder() },
            onOpenFolder = { source -> menuOpen = false; onOpenFolder(source) },
            onRequestAllFiles = { menuOpen = false; onRequestAllFiles() },
            onRetroAchievements = { menuOpen = false; raOpen = true },
            onAbout = { menuOpen = false; aboutOpen = true },
            onUpdate = { menuOpen = false; updateOpen = true },
            onShareReport = {
                menuOpen = false
                scope.launch {
                    val file = runCatching {
                        withContext(Dispatchers.IO) { app.retrolauncher.support.BugReport.write(context, vm.reportStatus()) }
                    }.getOrNull()
                    if (file == null) showMessage("Couldn't write the report.")
                    else runCatching { app.retrolauncher.support.BugReport.share(context, file) }.onFailure { showMessage("No app to share it with.") }
                }
            },
            showMessage = showMessage,
            onDismiss = { menuOpen = false },
        )
    }
    state.unrecognized?.let { choice ->
        UnrecognizedFileDialog(
            choice,
            onChooseAnother = { vm.dismissUnrecognized(); onChooseFile(choice.row) },
            onUseAnyway = { vm.useUnrecognizedAnyway(showMessage) },
            onDismiss = vm::dismissUnrecognized,
        )
    }
    state.retroArchSwitch?.let { switch ->
        RetroArchSwitchDialog(
            switch,
            onResume = { vm.resumeRetroArch()?.let(showMessage) },
            onStartAnyway = { vm.switchRetroArchAnyway()?.let(showMessage) },
            onUsageAccess = { vm.dismissRetroArchSwitch(); requestUsageAccess() },
            onDismiss = vm::dismissRetroArchSwitch,
        )
    }
    if (state.cocoonTip) CocoonTipDialog(onDismiss = vm::dismissCocoonTip)
    val update = state.update
    if (updateOpen && update != null) {
        UpdateDialog(
            update = update,
            step = state.updateStep,
            onStart = vm::startUpdate,
            onCancel = vm::cancelUpdate,
            onInstall = vm::installUpdate,
            onAllow = {
                runCatching { context.startActivity(vm.updatePermissionIntent()) }
                    .onFailure { showMessage("Couldn't open Android's settings. Allow PRGC Launcher under Install unknown apps.") }
            },
            onObtainium = { onOpenLink(app.retrolauncher.launch.UpdateCheck.OBTAINIUM_LINK, update.url) },
            onReleasePage = { onOpenUrl(update.url) },
            onDismiss = { updateOpen = false; vm.dismissUpdateMessage() },
        )
    }
    if (aboutOpen) AboutDialog(update = state.update, onOpenUrl = onOpenUrl, onDismiss = { aboutOpen = false })
    if (raOpen) {
        RaSignInDialog(vm, state.ra, onOpenUrl = onOpenUrl, showMessage = showMessage, onDismiss = { raOpen = false })
    }
}

internal val CARD_HEIGHT = 150.dp

/** Identifies a pick across the current and past lists: the event names the type and period. */
internal val PickRow.key: String get() = pick.event + "|" + pick.title

/** A card: box art on the left; the pick type, title, console and status on the right. */
@Composable
internal fun PickCard(card: Card, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit, completion: Completion? = null) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val row = card.row
    // A beaten game stands out: an outline and a faint wash in its trophy's colour.
    val accent = trophyColor(completion)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .scale(if (focused) 1.02f else 1f)
            .clip(shape)
            .background(colors.surfaceVariant)
            .then(if (accent != null) Modifier.background(accent.copy(alpha = 0.10f)) else Modifier)
            .border(
                if (focused) 3.dp else 2.dp,
                when { focused -> colors.primary; accent != null -> accent; else -> Color.Transparent },
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .onPad { pad ->
                when (pad) {
                    Pad.A -> { onOpen(); true }
                    Pad.X -> { onPlay(); true }
                    else -> false
                }
            }
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (row != null) {
                val art by rememberBoxArt(row.pick.imageUrl)
                art?.let { Image(it, contentDescription = "Box art", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            } else {
                Text("No pick\nright now", textAlign = TextAlign.Center, fontSize = 13.sp, color = colors.onSurface.copy(alpha = 0.6f))
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 14.dp)) {
            Text(card.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                row?.pick?.displayTitle ?: "—",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row != null) {
                Text(row.pick.consoleName(), fontSize = 13.sp, color = colors.onSurface.copy(alpha = 0.7f), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (row != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.note.orEmpty(),
                        fontSize = 12.sp,
                        color = colors.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    // Ready is the norm, so it shows nothing; a problem shows its pill.
                    if (row.state == PickState.MISSING && row.patchBase != null) PatchChip()
                    else if (row.state != PickState.READY) StatusChip(row.state)
                    if (completion == Completion.DNF) {
                        Text(
                            "DNF",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(50))
                                .border(1.5.dp, colors.onSurface.copy(alpha = 0.4f), RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (accent != null) {
                        Icon(
                            AppIcons.Trophy,
                            contentDescription = if (completion == Completion.MASTERED) "Mastered" else "Beaten",
                            tint = accent,
                            modifier = Modifier.padding(start = 8.dp).size(30.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A Missing romhack that can be made from a base ROM you have. */
@Composable
fun PatchChip() {
    Text(
        "Can patch",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF101114),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF5BC0D6)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusChip(state: PickState) {
    val (text, color) = when (state) {
        PickState.READY -> "Ready" to Color(0xFF5BD68A)
        PickState.NO_EMULATOR -> "No emulator" to Color(0xFFE8C15A)
        PickState.CHOOSE_COPY -> "Choose copy" to Color(0xFFE8C15A)
        PickState.CHECKING -> "Checking…" to Color(0xFF9AA0AA)
        PickState.NOT_CHECKED -> "Not checked" to Color(0xFF9AA0AA)
        PickState.MISSING -> "Missing ROM" to Color(0xFFE8835A)
        PickState.UNKNOWN -> "Unknown" to Color(0xFF9AA0AA)
    }
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF101114),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** A beaten game's accent: gold when mastered, silver when beaten; null otherwise. */
private fun trophyColor(completion: Completion?): Color? = when (completion) {
    Completion.MASTERED -> Color(0xFFE8C15A)
    Completion.BEATEN -> Color(0xFFC3C9D3)
    else -> null
}

/** The top bar: a title, an optional status line, and the settings cog (home) or help button (game view). */
@Composable
fun Header(
    status: String?,
    title: String = "Play Retro Games Club",
    onSettings: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
    /** The game view's ⋮ menu. */
    onMenu: (() -> Unit)? = null,
    onPast: (() -> Unit)? = null,
    onFilter: (() -> Unit)? = null,
    filtering: Boolean = false,
    onSurprise: (() -> Unit)? = null,
    /** While a slow search runs: stops it. */
    onStop: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (status != null) {
            Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), maxLines = 1, modifier = Modifier.padding(start = 16.dp))
        }
        if (onHelp != null) HelpButton(onHelp, Modifier.padding(start = 12.dp))
        if (onMenu != null) MenuButton(onMenu, Modifier.padding(start = 12.dp))
        if (onStop != null) StopButton(onStop, Modifier.padding(start = 12.dp))
        if (onSurprise != null) SurpriseButton(onSurprise, Modifier.padding(start = 12.dp))
        if (onFilter != null) FilterButton(onFilter, filtering, Modifier.padding(start = 12.dp))
        if (onPast != null) PastButton(onPast, Modifier.padding(start = 12.dp))
        if (onSettings != null) SettingsCog(onSettings, Modifier.padding(start = 12.dp))
    }
}

/** A round, focusable button for the top bar. D-pad up from the screen below reaches it; A presses it. */
@Composable
private fun HeaderButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Spelled out after the icon, making the button a pill. */
    label: String? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    if (label != null) {
        Row(
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(if (focused) colors.primary else Color.Transparent)
                .onFocusChanged { focused = it.isFocused }
                .onPad { pad -> if (pad == Pad.A) { onClick(); true } else false }
                .clickable(onClick = onClick)
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(focused)
            Text(
                label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) colors.onPrimary else colors.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        return
    }
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onPad { pad -> if (pad == Pad.A) { onClick(); true } else false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(focused) }
}

/** The home screen's settings cog. */
@Composable
private fun SettingsCog(onClick: () -> Unit, modifier: Modifier = Modifier) = HeaderButton(onClick, modifier) { focused ->
    Icon(
        Icons.Filled.Settings,
        contentDescription = "Settings",
        tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        modifier = Modifier.size(28.dp),
    )
}

/** Stops a slow ROM search: a cross, beside the progress it stops. */
@Composable
private fun StopButton(onClick: () -> Unit, modifier: Modifier = Modifier) = HeaderButton(onClick, modifier) { focused ->
    Icon(
        Icons.Filled.Close,
        contentDescription = "Stop checking",
        tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        modifier = Modifier.size(26.dp),
    )
}

/** History's "Surprise me" button: a die. */
@Composable
private fun SurpriseButton(onClick: () -> Unit, modifier: Modifier = Modifier) = HeaderButton(onClick, modifier) { focused ->
    Icon(
        AppIcons.Dice,
        contentDescription = "Surprise me",
        tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        modifier = Modifier.size(26.dp),
    )
}

/** The past-picks page's sort and filter button; a dot shows when a filter is on. */
@Composable
private fun FilterButton(onClick: () -> Unit, filtering: Boolean, modifier: Modifier = Modifier) = HeaderButton(onClick, modifier) { focused ->
    val colors = MaterialTheme.colorScheme
    Box {
        Icon(
            AppIcons.FilterList,
            contentDescription = "Sort and filter",
            tint = if (focused) colors.onPrimary else colors.onBackground.copy(alpha = 0.8f),
            modifier = Modifier.size(28.dp),
        )
        if (filtering) {
            Box(
                Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape)
                    .background(if (focused) colors.onPrimary else colors.primary),
            )
        }
    }
}

/** The home screen's past-picks button: a history clock and "History", beside the cog. */
@Composable
private fun PastButton(onClick: () -> Unit, modifier: Modifier = Modifier) = HeaderButton(onClick, modifier, label = "History") { focused ->
    Icon(
        AppIcons.History,
        contentDescription = null,
        tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        modifier = Modifier.size(28.dp),
    )
}

/** The game view's menu button: three vertical dots. */
@Composable
private fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) = HeaderButton(onClick, modifier) { focused ->
    Icon(
        Icons.Filled.MoreVert,
        contentDescription = "More",
        tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        modifier = Modifier.size(28.dp),
    )
}

/** The game view's help button: a "?" in a ring. */
@Composable
private fun HelpButton(onClick: () -> Unit, modifier: Modifier = Modifier) = HeaderButton(onClick, modifier) { focused ->
    val color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
    Box(
        Modifier.size(26.dp).border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("?", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Footer(start: @Composable () -> Unit = {}, hints: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // [start] sits at the left edge (Settings on the home screen); [hints] at the right.
        start()
        Spacer(Modifier.weight(1f))
        hints()
    }
}
