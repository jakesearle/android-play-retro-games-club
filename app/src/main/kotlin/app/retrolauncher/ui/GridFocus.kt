package app.retrolauncher.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * D-pad up/down for a two-column card grid. The lazy grid's own focus search lays out
 * off-screen cards one at a time and takes the first one below, which is the left column's,
 * so pressing down on the right column could jump left. This moves to the card in the same
 * column instead (or the last card, if the row below is shorter). Reaching the first or last
 * row also scrolls to that end of the grid, so its padding shows instead of the card sitting
 * flush against the edge.
 */
class GridRows internal constructor(
    private val grid: LazyGridState,
    private val scope: CoroutineScope,
    private val rowStep: Float,
) {
    /**
     * Moves focus from card [from] a row [down] or up, among [requesters] (one per card).
     * [atEnd] says the grid has no more cards to load, so the last row really is the last.
     * Returns false at the top (focus goes on up to the header) and at the bottom.
     */
    fun move(from: Int, down: Boolean, requesters: List<FocusRequester>, atEnd: Boolean = true): Boolean {
        val last = requesters.lastIndex
        val target = when {
            down && from + 2 <= last -> from + 2
            down && from / 2 < last / 2 -> last
            !down && from - 2 >= 0 -> from - 2
            else -> return false
        }
        scope.launch {
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                grid.scrollBy(if (down) rowStep else -rowStep)
                withFrameNanos { }
            }
            runCatching { requesters[target].requestFocus() }
            withFrameNanos { }
            when {
                target / 2 == last / 2 && atEnd -> grid.animateScrollBy(rowStep)
                target / 2 == 0 -> grid.animateScrollToItem(0)
            }
        }
        return true
    }
}

/** [GridRows] for [grid], whose rows are [rowHeight] apart (card height plus spacing). */
@Composable
fun rememberGridRows(grid: LazyGridState, rowHeight: Dp): GridRows {
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { rowHeight.toPx() }
    return remember(grid, scope, step) { GridRows(grid, scope, step) }
}

/** Sends D-pad up/down on card [index] to [rows] (see [GridRows.move]). */
fun Modifier.gridRowKeys(rows: GridRows, index: Int, requesters: List<FocusRequester>, atEnd: () -> Boolean = { true }): Modifier =
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionDown -> rows.move(index, down = true, requesters, atEnd())
            Key.DirectionUp -> rows.move(index, down = false, requesters, atEnd())
            else -> false
        }
    }
