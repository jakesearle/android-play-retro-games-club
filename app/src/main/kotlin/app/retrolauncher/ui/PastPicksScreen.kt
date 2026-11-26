package app.retrolauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/** How many past picks the page shows at a time; the next batch loads as you near the end. */
const val PAST_BATCH = 10

/**
 * The club's earlier picks of every type in one list, sorted and filtered by [filter] (Y or the
 * header button opens the options). Cards load [shown] at a time and the next [PAST_BATCH]
 * loads as the grid nears the end; only loaded cards are matched against the ROMs ([onCheck]),
 * unless a ROM filter needs them all. A opens the game view, X plays, B goes back.
 */
@Composable
fun PastPicksScreen(
    past: List<PickRow>,
    ra: RaState,
    marks: Map<Int, PickMark>,
    /** A slow ROM search in progress, if any, and how to stop it. */
    scan: ScanProgress?,
    onStop: () -> Unit,
    onLoadPlayers: (List<Int>) -> Unit,
    filter: PastFilter,
    onFilter: (PastFilter) -> Unit,
    shown: Int,
    onShowMore: () -> Unit,
    /** The card to focus when the page appears (where the player was before the game view). */
    focusIndex: Int,
    onFocus: (Int) -> Unit,
    onCheck: (List<PickRow>) -> Unit,
    onOpen: (PickRow) -> Unit,
    onPlay: (PickRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Signed out, sorting by popularity and filtering by completion aren't available.
    val signedIn = ra.user != null
    val shownFilter = filter.available(signedIn, marks.isNotEmpty())
    val rows = remember(past, filter, ra, marks) { historyRows(past, filter, ra, marks) }
    // Most popular needs every past pick's player count; they load in the background.
    LaunchedEffect(shownFilter.sort, past.size) {
        if (shownFilter.sort == PastSort.MOST_POPULAR) onLoadPlayers(past.map { it.pick.gameId })
    }
    val visible = rows.take(shown)
    val grid = rememberLazyGridState()
    val requesters = remember(visible.size) { List(visible.size) { FocusRequester() } }
    var focusedCard by remember { mutableStateOf<Int?>(null) }
    var filterOpen by remember { mutableStateOf(false) }

    /** Opens a random pick from the filtered list, one you can play if there is one. */
    fun surprise() {
        surprisePick(rows)?.let(onOpen)
    }
    val checking = filter.rom != RomFilter.ANY && past.any { it.state == PickState.CHECKING }

    // Match only the cards that are loaded; the view model skips ones already checked.
    LaunchedEffect(filter, shown, rows.size) { onCheck(visible) }
    // Load the next batch when the last loaded cards scroll into view (by D-pad or touch).
    val currentShown by rememberUpdatedState(shown)
    val total by rememberUpdatedState(rows.size)
    val gridRows = rememberGridRows(grid, CARD_HEIGHT + 16.dp)
    LaunchedEffect(filter) {
        grid.scrollToItem(0)
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= currentShown - 2 && currentShown < total) onShowMore() }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPad { pad ->
                when (pad) {
                    Pad.Y -> { filterOpen = true; true }
                    Pad.SELECT -> { surprise(); true }
                    else -> false
                }
            },
    ) {
        val status = when {
            scan != null -> scan.text
            checking -> "Checking ROMs…"
            shownFilter.sort == PastSort.MOST_POPULAR && ra.loadingPlayers -> "Loading player counts…"
            shownFilter.filtering -> "${rows.size} of ${past.size} picks"
            else -> "${past.size} picks"
        }
        Header(
            status,
            title = "History",
            onFilter = { filterOpen = true },
            filtering = shownFilter.filtering,
            onSurprise = ::surprise,
            onStop = if (scan != null) onStop else null,
        )
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (checking) "Checking which ROMs you have…" else "No picks match the filters.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(visible, key = { _, row -> row.key }) { index, row ->
                    PickCard(
                        card = Card(row.pick.type?.label ?: row.pick.event, row, note = row.pick.periodLabel),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CARD_HEIGHT)
                            .focusRequester(requesters[index])
                            .gridRowKeys(gridRows, index, requesters) { currentShown >= total }
                            .onFocusChanged {
                                if (it.isFocused) { focusedCard = index; onFocus(index) }
                                else if (focusedCard == index) focusedCard = null
                            },
                        onOpen = { onOpen(row) },
                        onPlay = { onPlay(row) },
                        completion = completionOf(ra.progress[row.pick.gameId], marks[row.pick.gameId]),
                    )
                }
            }
        }
        Footer(start = { PadHint("Y", "Sort & filter"); PadHint("Select", "Surprise me") }) {
            PadHint("X", "Play")
            PadHint("B", "Back")
            PadHint("A", "Details")
        }
    }

    // Focus the card the player was on (the first, after the filters change), as the home
    // screen does; this also runs when the filter dialog closes and the window regains focus.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val view = LocalView.current
    LaunchedEffect(filter, windowFocused, visible.isEmpty()) {
        if (!windowFocused || visible.isEmpty()) return@LaunchedEffect
        val index = focusIndex.coerceIn(0, visible.lastIndex)
        withFrameNanos { }
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
        for (attempt in 0 until 10) {
            takeControllerFocus(view, requesters[index])
            withFrameNanos { }
            if (focusedCard == index) break
        }
    }

    if (filterOpen) PastFilterDialog(shownFilter, past, signedIn, showProgress = signedIn || marks.isNotEmpty(), onChange = onFilter, onDismiss = { filterOpen = false })
}

