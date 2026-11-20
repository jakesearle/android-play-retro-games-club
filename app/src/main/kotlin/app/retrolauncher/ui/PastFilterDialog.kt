package app.retrolauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The past-picks page's options, laid out across the screen: one row per option (Sort, Type,
 * ROM, Console) with its label on the left and its choices as chips. The D-pad moves between
 * chips in any direction; A toggles one and applies it at once, X resets, B closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PastFilterDialog(filter: PastFilter, past: List<PickRow>, signedIn: Boolean, showProgress: Boolean, onChange: (PastFilter) -> Unit, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    val consoles = remember(past) { pastConsoles(past) }
    PadDialog(
        title = "Sort & filter",
        onDismiss = onDismiss,
        focus = first,
        fullWidth = true,
        hints = {
            PadHint("X", "Reset")
            PadHint("B", "Close")
            PadHint("A", "Select")
        },
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .onPad { pad -> if (pad == Pad.X) { onChange(PastFilter()); true } else false },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Option("Sort") {
                // Most popular needs RetroAchievements' player counts, so it's there once signed in.
                PastSort.entries.filter { signedIn || it != PastSort.MOST_POPULAR }.forEachIndexed { i, sort ->
                    Chip(sort.label, filter.sort == sort, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        onChange(filter.copy(sort = sort))
                    }
                }
            }
            Option("Type") {
                Chip("Any", filter.types.size == PickType.entries.size) { onChange(filter.copy(types = PickType.entries.toSet())) }
                PickType.entries.forEach { type ->
                    Chip(type.label, type in filter.types) { onChange(filter.toggle(type)) }
                }
            }
            Option("ROM") {
                RomFilter.entries.forEach { rom ->
                    Chip(rom.label, filter.rom == rom) { onChange(filter.copy(rom = rom)) }
                }
            }
            // From RetroAchievements and your own marks, so it shows once either exists.
            if (showProgress) {
                Option("Progress") {
                    CompletionFilter.entries.forEach { c ->
                        Chip(c.label, filter.completion == c) { onChange(filter.copy(completion = c)) }
                    }
                }
            }
            Option("Console") {
                Chip("Any", filter.console == null) { onChange(filter.copy(console = null)) }
                consoles.forEach { (id, name, count) ->
                    Chip(name, filter.console == id, count = count) { onChange(filter.copy(console = id)) }
                }
            }
        }
    }
}

/** One option: its label in a fixed-width column, then its chips, wrapping onto more lines if needed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Option(label: String, chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(84.dp).padding(top = 9.dp),
        )
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
    }
}

/**
 * A choice. Chosen chips are filled with a check mark; the focused chip gets a bright outline,
 * so focus and choice can be told apart.
 */
@Composable
private fun Chip(text: String, chosen: Boolean, modifier: Modifier = Modifier, count: Int? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val content = if (chosen) colors.onPrimary else colors.onSurface
    Row(
        modifier
            .clip(shape)
            .background(if (chosen) colors.primary else colors.surfaceVariant)
            .border(3.dp, if (focused) colors.onSurface else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPad { pad -> if (pad == Pad.A) { onClick(); true } else false }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chosen) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = content, fontSize = 15.sp)
        // A count ("Arcade 3") reads as secondary, so the name stands out.
        if (count != null) Text(" $count", color = content.copy(alpha = 0.6f), fontSize = 15.sp)
    }
}
