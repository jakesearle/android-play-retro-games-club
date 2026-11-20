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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.retrolauncher.launch.RetroArchCores
import kotlinx.coroutines.launch

/** A button in the game view: what it says, and what A does. */
private data class Action(val label: String, val icon: ImageVector, val primary: Boolean = false, val run: () -> Unit)

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
    onOpenLink: (link: String, fallbackUrl: String) -> Unit,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pick = row.pick
    val art by rememberBoxArt(pick.imageUrl)
    var pickerOpen by remember { mutableStateOf(false) }
    var copiesOpen by remember { mutableStateOf(false) }
    var getEmulatorOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    val help = remember(row, revision) { helpTopics(row, vm) }

    val actions = buildList {
        // No emulator for this game (or its console): getting one comes first.
        if (vm.needsEmulator(row)) add(Action("Get emulator", Icons.Filled.AddCircle) { getEmulatorOpen = true })
        when (row.state) {
            PickState.READY -> {
                add(Action("Play", Icons.Filled.PlayArrow, primary = true) { onPlay() })
                add(Action("Emulator", Icons.Filled.Build) { pickerOpen = true })
            }
            PickState.NO_EMULATOR -> add(Action("Emulator", Icons.Filled.Build) { pickerOpen = true })
            PickState.CHOOSE_COPY -> add(Action("Choose copy", Icons.AutoMirrored.Filled.List) { copiesOpen = true })
            PickState.MISSING -> {
                add(Action("Choose ROM file", Icons.Filled.Search) { onChooseFile() })
                add(Action("Add ROM folder", Icons.Filled.Add) { onAddFolder() })
                add(Action("Search again", Icons.Filled.Refresh) { vm.checkPicks() })
            }
            PickState.UNKNOWN -> add(Action("Add ROM folder", Icons.Filled.Add) { onAddFolder() })
            PickState.CHECKING -> Unit
        }
        if (pick.raUrl.isNotEmpty()) add(Action("RetroAchievements", Icons.Filled.Star) { onOpenUrl(pick.raUrl) })
        if (pick.guideUrl.isNotEmpty()) add(Action("Guide", Icons.Filled.Info) { onOpenUrl(pick.guideUrl) })
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
        Header(status = null, title = pick.event, onHelp = if (help.isNotEmpty()) ({ helpOpen = true }) else null)
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
                GameStatus(row.state)
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
                        for (stat in pick.statsInOrder) {
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
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEachIndexed { i, action ->
                        ActionButton(
                            action.label,
                            action.icon,
                            primary = action.primary,
                            onClick = action.run,
                            modifier = if (i == 0) Modifier.focusRequester(firstButton) else Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // The game's summary, then which file and emulator it uses (or what's missing).
                Description(pick.description, statusLine(row, vm, revision, hasSources), Modifier.weight(1f))
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
    if (helpOpen) HelpDialog(help, onDismiss = { helpOpen = false })
    if (getEmulatorOpen) GetEmulatorDialog(vm, row, onOpenLink, onDismiss = { getEmulatorOpen = false })
    if (copiesOpen) {
        ChooseCopyDialog(row, onChoose = { game -> copiesOpen = false; vm.chooseCopy(row, game)?.let(showMessage) }, onDismiss = { copiesOpen = false })
    }
}

/**
 * Troubleshooting for this game's emulator, for the ? button: only what applies (so far, a
 * RetroArch core that may be missing, since the app can't see RetroArch's cores). Empty means
 * no ? button.
 */
private fun helpTopics(row: PickRow, vm: ClubViewModel): List<HelpTopic> {
    val game = row.game ?: return emptyList()
    if (row.state != PickState.READY) return emptyList()
    return listOfNotNull(
        vm.retroArchCore(game)?.let { core ->
            HelpTopic(
                "RetroArch launches with a black screen",
                "RetroArch is probably missing the $core core this game plays with. " +
                    "Get it in ${RetroArchCores.HOW_TO_GET}, then play again. " +
                    "Or choose another emulator or core with the Emulator button.",
            )
        },
    )
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

/** A game view button: an icon and a label. Play, the main action, is the largest. */
@Composable
private fun ActionButton(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val content = if (focused) colors.onPrimary else colors.onSurface
    val background = when {
        focused -> colors.primary
        primary -> colors.primary.copy(alpha = 0.22f)
        else -> colors.surfaceVariant
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(2.dp, if (primary && !focused) colors.primary else Color.Transparent, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .onPad { pad -> if (pad == Pad.A) { onClick(); true } else false }
            .clickable(onClick = onClick)
            .padding(horizontal = if (primary) 26.dp else 18.dp, vertical = if (primary) 13.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (primary) 28.dp else 20.dp))
        Text(
            label,
            fontSize = if (primary) 21.sp else 16.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
            color = content,
            modifier = Modifier.padding(start = if (primary) 8.dp else 6.dp),
        )
    }
}

/** The status under the box art: spelled out when the ROM is matched, otherwise the status pill. */
@Composable
private fun GameStatus(state: PickState) {
    if (state == PickState.READY) {
        val grey = Color(0xFF9AA0AA)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = grey, modifier = Modifier.size(22.dp))
            Text("ROM matched", color = grey, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
    } else {
        StatusChip(state)
    }
}

/**
 * The game's summary, then a second paragraph on its file and emulator. D-pad down from the
 * buttons focuses it when it's long, then up/down scroll it.
 */
@Composable
private fun Description(summary: String, details: String, modifier: Modifier) {
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
        Column {
            if (summary.isNotEmpty()) {
                Text(summary, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(10.dp))
            }
            Text(details, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
    }
}
