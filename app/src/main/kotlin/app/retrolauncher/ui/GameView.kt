package app.retrolauncher.ui

import android.view.KeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** A button in the game view: what it says, and what A does. */
private data class Action(val label: String, val run: () -> Unit)

/**
 * One pick in detail: box art, dates, stats, description, which file and emulator it uses (or
 * why it can't be played), and buttons for what can be done. A presses the focused button,
 * X plays, B goes back.
 */
@Composable
fun GameView(
    row: PickRow,
    vm: ClubViewModel,
    revision: Int,
    hasSources: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onChooseFile: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenUrl: (String) -> Unit,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pick = row.pick
    val art by rememberBoxArt(pick.imageUrl)
    var pickerOpen by remember { mutableStateOf(false) }
    var copiesOpen by remember { mutableStateOf(false) }

    val actions = buildList {
        when (row.state) {
            PickState.READY -> {
                add(Action("Play") { onPlay() })
                add(Action("Emulator") { pickerOpen = true })
            }
            PickState.NO_EMULATOR -> add(Action("Emulator") { pickerOpen = true })
            PickState.CHOOSE_COPY -> add(Action("Choose copy") { copiesOpen = true })
            PickState.MISSING -> {
                add(Action("Choose ROM file") { onChooseFile() })
                add(Action("Add ROM folder") { onAddFolder() })
                add(Action("Search again") { vm.checkPicks() })
            }
            PickState.UNKNOWN -> add(Action("Add ROM folder") { onAddFolder() })
            PickState.CHECKING -> Unit
        }
        if (pick.raUrl.isNotEmpty()) add(Action("RetroAchievements") { onOpenUrl(pick.raUrl) })
        if (pick.guideUrl.isNotEmpty()) add(Action("Guide") { onOpenUrl(pick.guideUrl) })
    }
    val firstButton = remember { FocusRequester() }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPad { pad ->
                when (pad) {
                    Pad.X -> { onPlay(); true }
                    Pad.B -> { onBack(); true }
                    else -> false
                }
            },
    ) {
        Header(status = null, title = pick.event)
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(160.dp)) {
                Box(
                    Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    art?.let { Image(it, contentDescription = "Box art", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.height(10.dp))
                StatusBadge(row.state, checkSize = 32.dp)
            }
            Column(Modifier.weight(1f).padding(start = 20.dp)) {
                Text(pick.displayTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(pick.consoleName(), fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                if (pick.next.isNotEmpty()) {
                    Text(pick.next, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                }
                if (pick.stats.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (stat in pick.stats) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stat.value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(" ${stat.key}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(statusLine(row, vm, revision, hasSources), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEachIndexed { i, action ->
                        ActionButton(
                            action.label,
                            primary = i == 0 && action.label == "Play",
                            onClick = action.run,
                            modifier = if (i == 0) Modifier.focusRequester(firstButton) else Modifier,
                        )
                    }
                }
                if (pick.description.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Description(pick.description, Modifier.weight(1f))
                }
            }
        }
        Footer {
            PadHint("A", "Select")
            PadHint("X", "Play")
            PadHint("B", "Back")
        }
    }

    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val view = LocalView.current
    LaunchedEffect(row.state, windowFocused) { if (windowFocused) takeControllerFocus(view, firstButton) }

    if (pickerOpen) row.game?.let { game -> EmulatorPicker(vm, game, pick.displayTitle, onDismiss = { pickerOpen = false }) }
    if (copiesOpen) {
        ChooseCopyDialog(row, onChoose = { game -> copiesOpen = false; vm.chooseCopy(row, game)?.let(showMessage) }, onDismiss = { copiesOpen = false })
    }
}

/** What A on Play would do, or why it can't. */
private fun statusLine(row: PickRow, vm: ClubViewModel, @Suppress("UNUSED_PARAMETER") revision: Int, hasSources: Boolean): String =
    when (row.state) {
        PickState.READY -> {
            val game = row.game!!
            val player = vm.playerFor(game)
            val via = when (vm.choiceSource(game)) {
                ChoiceScope.GAME -> " (chosen for this game)"
                ChoiceScope.PLATFORM -> " (${vm.platformName(game)} default)"
                null -> ""
            }
            "${game.fileName}\n" + (player?.let { "Plays in ${it.name}$via" } ?: "No emulator installed")
        }
        PickState.NO_EMULATOR -> "${row.game?.fileName.orEmpty()}\n${row.message.orEmpty()}"
        PickState.CHOOSE_COPY -> "${row.copies.size} copies match. Choose which to play; it's remembered."
        PickState.CHECKING -> "Looking for this game in your ROM folders…"
        PickState.MISSING, PickState.UNKNOWN ->
            (row.message ?: "Not found.") + if (!hasSources) "\nNo ROM folders added yet." else ""
    }

@Composable
private fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Text(
        label,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (focused) colors.onPrimary else colors.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) colors.primary else colors.surfaceVariant)
            .border(2.dp, if (primary && !focused) colors.primary else Color.Transparent, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .onPad { pad -> if (pad == Pad.A) { onClick(); true } else false }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The description; D-pad down from the buttons focuses it, then up/down scroll long text. */
@Composable
private fun Description(text: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val scrollable = scroll.maxValue > 0
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (scroll.value < scroll.maxValue) {
                        scope.launch { scroll.animateScrollBy(160f, tween(150)) }; true
                    } else true // the bottom of the screen: nothing below to move to
                    KeyEvent.KEYCODE_DPAD_UP -> if (scroll.value > 0) {
                        scope.launch { scroll.animateScrollBy(-160f, tween(150)) }; true
                    } else false // at the top: let focus go back up to the buttons
                    else -> false
                }
            }
            .then(if (scrollable) Modifier.focusable() else Modifier)
            .verticalScroll(scroll)
            .padding(6.dp),
    ) {
        Text(text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
