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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
internal data class Card(val label: String, val row: PickRow?)

/**
 * The home screen's cards: the four pick types in order (empty when there's no current pick),
 * then any other pick, such as a special event or a second pick of one type, labelled with its
 * own event name, then test picks (debug builds only).
 */
internal fun homeCards(rows: List<PickRow>): List<Card> {
    val used = mutableSetOf<PickRow>()
    val basic = PickType.entries.map { type ->
        val row = rows.firstOrNull { it !in used && it.pick.event != DEBUG_EVENT && it.pick.event.startsWith(type.label) }
        row?.let(used::add)
        Card(type.label, row)
    }
    val specials = rows.filter { it !in used && it.pick.event != DEBUG_EVENT }
        .map { Card(it.pick.event.ifEmpty { "Special" }, it) }
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
    if (state.showSetup) {
        SetupScreen(vm, state, onAddFolder, onRequestAllFiles, modifier)
        return
    }
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    /** The card that has focus right now, or null; [focusedIndex] remembers the last one. */
    var focusedCard by remember { mutableStateOf<Int?>(null) }
    /** The card to put focus back on when the game view closes, or -1. */
    var returnTo by rememberSaveable { mutableIntStateOf(-1) }
    var openTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val cards = remember(state.rows) { homeCards(state.rows) }
    val grid = rememberLazyGridState()
    val requesters = remember(cards.size) { List(cards.size) { FocusRequester() } }
    // Looked up by title so the game view follows the row as its status updates.
    val open = openTitle?.let { t -> state.rows.firstOrNull { it.pick.title == t } }

    fun openGame(row: PickRow) {
        if (openTitle == null) returnTo = focusedIndex
        openTitle = row.pick.title
    }

    fun play(row: PickRow) {
        if (row.state == PickState.READY) vm.play(row)?.let(showMessage)
        else openGame(row) // shows what's missing and what to do
    }

    if (open != null) {
        BackHandler { openTitle = null }
        GameView(
            row = open,
            vm = vm,
            revision = state.revision,
            hasSources = state.sources.isNotEmpty(),
            onBack = { openTitle = null },
            onPlay = { play(open) },
            onChooseFile = { onChooseFile(open) },
            onAddFolder = onAddFolder,
            onOpenUrl = onOpenUrl,
            onOpenLink = onOpenLink,
            showMessage = showMessage,
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
                        else -> false
                    }
                },
        ) {
            Header(state.status, onSettings = { menuOpen = true })
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
                            .onFocusChanged {
                                // While returning from the game view, Compose briefly focuses some
                                // other card on its own; don't let that overwrite where we're going back to.
                                if (it.isFocused) { if (returnTo < 0) focusedIndex = index; focusedCard = index }
                                else if (focusedCard == index) focusedCard = null
                            },
                        onOpen = { card.row?.let(::openGame) ?: showMessage("No ${card.label} right now.") },
                        onPlay = { card.row?.let(::play) ?: showMessage("No ${card.label} right now.") },
                    )
                }
            }
            Footer(start = { PadHint("Start", "Settings") }) {
                PadHint("A", "Details")
                PadHint("X", "Play")
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
            showMessage = showMessage,
            onDismiss = { menuOpen = false },
        )
    }
}

private val CARD_HEIGHT = 150.dp

/** A card: box art on the left; the pick type, title, console and status on the right. */
@Composable
private fun PickCard(card: Card, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val row = card.row
    Row(
        modifier
            .scale(if (focused) 1.02f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant)
            .border(3.dp, if (focused) colors.primary else Color.Transparent, RoundedCornerShape(16.dp))
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
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    StatusBadge(row.state)
                }
            }
        }
    }
}

@Composable
fun StatusChip(state: PickState) {
    val (text, color) = when (state) {
        PickState.READY -> "Ready" to Color(0xFF5BD68A)
        PickState.NO_EMULATOR -> "No emulator" to Color(0xFFE8C15A)
        PickState.CHOOSE_COPY -> "Choose copy" to Color(0xFFE8C15A)
        PickState.CHECKING -> "Checking…" to Color(0xFF9AA0AA)
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

/** A pick's status: a quiet grey check when the game is ready, otherwise a status pill. */
@Composable
fun StatusBadge(state: PickState, checkSize: Dp = 26.dp) {
    if (state == PickState.READY) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Ready",
            tint = Color(0xFF9AA0AA),
            modifier = Modifier.size(checkSize),
        )
    } else {
        StatusChip(state)
    }
}

/** The top bar: a title, an optional status line, and the settings cog (home) or help button (game view). */
@Composable
fun Header(
    status: String?,
    title: String = "Play Retro Games Club",
    onSettings: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
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
        if (onSettings != null) SettingsCog(onSettings, Modifier.padding(start = 12.dp))
    }
}

/** A round, focusable button for the top bar. D-pad up from the screen below reaches it; A presses it. */
@Composable
private fun HeaderButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable (focused: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
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
