package app.retrolauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.retrolauncher.launch.Recommendation
import app.retrolauncher.library.Game
import kotlinx.coroutines.delay
import app.retrolauncher.library.RomSource

/** A dialog that closes on B, Start or Select as well as Back, with a title and focusable rows. */
@Composable
internal fun PadDialog(
    title: String,
    onDismiss: () -> Unit,
    focus: FocusRequester,
    wide: Boolean = false,
    /** Nearly the whole screen's width, for dialogs laid out across rather than down. */
    fullWidth: Boolean = false,
    hints: @Composable () -> Unit = { PadHint("B", "Close"); PadHint("A", "Select") },
    content: @Composable ColumnScope.() -> Unit,
) {
    // Android caps a dialog at its default dialog width (about a third of a 16:9 handheld screen)
    // unless told otherwise, so a wide dialog opts out and sizes itself to the screen.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !wide && !fullWidth)) {
        // A dialog has its own window and view. Focus has to be requested from inside it, once
        // its window has focus (a moment after it appears), so retry briefly, but stop as soon as
        // a row has focus: retrying after that would pull a quick D-pad press back to the top.
        val dialogView = LocalView.current
        var focusLanded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            for (wait in longArrayOf(0, 50, 100, 150, 200)) {
                delay(wait)
                if (focusLanded) break
                takeControllerFocus(dialogView, focus)
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .then(
                    when {
                        fullWidth -> Modifier.fillMaxWidth(0.96f)
                        wide -> Modifier.fillMaxWidth(0.9f).widthIn(max = 760.dp)
                        else -> Modifier.width(600.dp)
                    }
                )
                // Fit the 16:9 handheld screen; the list scrolls and the hint row stays visible.
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                // Unhandled, Start would fall back to a "click" on the focused row.
                .onPad { pad -> if (pad == Pad.B || pad == Pad.START || pad == Pad.SELECT) { onDismiss(); true } else false }
                .onFocusChanged { if (it.hasFocus) focusLanded = true },

        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                content()
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { hints() }
            }
        }
    }
}

@Composable
internal fun RowText(text: String, focused: Boolean, detail: String? = null, enabled: Boolean = true) {
    val base = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val color = if (enabled) base else base.copy(alpha = 0.5f)
    Column {
        Text(text, color = color, fontSize = 17.sp)
        if (detail != null) Text(detail, color = color.copy(alpha = 0.75f), fontSize = 13.sp)
    }
}

@Composable
internal fun Message(text: String) {
    Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

/** The row a dialog focuses when it opens; pass it to [PadDialog] and to that row. */
@Composable
internal fun rememberFirstFocus(): FocusRequester = remember { FocusRequester() }

/** Choose the emulator for a game, for this game only or all games on its platform. */
@Composable
fun EmulatorPicker(vm: ClubViewModel, game: Game, title: String, onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf(ChoiceScope.GAME) }
    val options = remember(game) { vm.options(game) }
    val current = remember(game) { vm.playerFor(game) }
    val platformName = vm.platformName(game)
    val first = rememberFirstFocus()

    PadDialog(title = "Emulator for $title", onDismiss = onDismiss, focus = first) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            item {
                FocusRow(
                    onClick = { scope = if (scope == ChoiceScope.GAME) ChoiceScope.PLATFORM else ChoiceScope.GAME },
                    modifier = Modifier.focusRequester(first),
                ) { focused ->
                    RowText(
                        text = "Apply to: " + if (scope == ChoiceScope.GAME) "this game only" else "all $platformName games",
                        detail = "Press A to switch",
                        focused = focused,
                    )
                }
            }
            if (options.isEmpty()) item { Message("No installed emulator can open this file.") }
            items(options, key = { it.player.id }) { option ->
                FocusRow(
                    onClick = {
                        if (option.usable) {
                            vm.setChoice(game, option.player, scope)
                            onDismiss()
                        }
                    },
                    selected = option.player.id == current?.id,
                ) { focused ->
                    RowText(
                        text = option.player.label + if (option.player.id == current?.id) "  ✓" else "",
                        detail = option.problem ?: if (option.player.cantResume) "Can't resume" else null,
                        focused = focused,
                        enabled = option.usable,
                    )
                }
            }
            item {
                FocusRow(onClick = { vm.setChoice(game, null, scope); onDismiss() }) { focused ->
                    RowText(if (scope == ChoiceScope.GAME) "Use the platform default" else "Clear the platform default", focused)
                }
            }
        }
    }
}

/** Several files are this game; the choice is remembered. */
@Composable
fun ChooseCopyDialog(row: PickRow, onChoose: (Game) -> Unit, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    PadDialog(title = "Which copy of ${row.pick.displayTitle}?", onDismiss = onDismiss, focus = first) {
        Message("These files are all versions RetroAchievements recognizes. Your choice is remembered.")
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(row.copies, key = { it.key }) { game ->
                FocusRow(
                    onClick = { onChoose(game) },
                    modifier = if (game == row.copies.first()) Modifier.focusRequester(first) else Modifier,
                ) { f -> RowText(game.fileName, f, game.path ?: game.uri) }
            }
        }
    }
}

@Composable
fun MenuDialog(
    vm: ClubViewModel,
    state: ClubState,
    onAddFolder: () -> Unit,
    onOpenFolder: (RomSource) -> Unit,
    onRequestAllFiles: () -> Unit,
    onRetroAchievements: () -> Unit,
    onAbout: () -> Unit,
    onShareReport: () -> Unit,
    onUpdate: () -> Unit,
    showMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val suggestions = remember(state.sources, state.hasAllFilesAccess) { vm.rawFolderSuggestions() }
    var confirmUnlink by remember { mutableStateOf(false) }
    val first = rememberFirstFocus()

    PadDialog(title = "Settings", onDismiss = onDismiss, focus = first, wide = true) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            state.update?.let { update ->
                item {
                    FocusRow(onClick = onUpdate) { f ->
                        RowText(
                            "Version ${update.version} is available…",
                            f,
                            when (state.updateStep) {
                                is UpdateStep.Downloading -> "Downloading…"
                                else -> "See what's new and update, here or with Obtainium."
                            },
                        )
                    }
                }
            }
            item {
                FocusRow(onClick = { vm.refresh(); onDismiss() }, modifier = Modifier.focusRequester(first)) { f ->
                    RowText("Refresh games", f, "Asks the club's site for its current picks and searches again.")
                }
            }
            item {
                FocusRow(onClick = onRetroAchievements) { f ->
                    val ra = state.ra
                    RowText(
                        "RetroAchievements…",
                        f,
                        when {
                            ra.user == null -> "Sign in to see your progress on each pick."
                            ra.keyRefused -> "Signed in as ${ra.user}, but the key was refused. Sign in again."
                            else -> "Signed in as ${ra.user}."
                        },
                    )
                }
            }
            // Open a ROM folder in the file manager: one row, or one per folder when there are several.
            items(state.sources, key = { "open:${it.key}" }) { source ->
                FocusRow(onClick = { onOpenFolder(source) }) { f ->
                    RowText(
                        if (state.sources.size == 1) "Open ROMs folder" else "Open ${source.label}",
                        f,
                        if (state.sources.size == 1) "${source.label}, in your file manager" else "In your file manager",
                    )
                }
            }
            item {
                FocusRow(onClick = onAddFolder) { f ->
                    RowText("Add ROM folder…", f, "Opens the system file picker.")
                }
            }
            if (!state.hasAllFilesAccess) {
                item {
                    FocusRow(onClick = onRequestAllFiles) { f ->
                        RowText("Grant all-files access…", f, "Needed for path-based emulators such as RetroArch.")
                    }
                }
            }
            items(suggestions, key = { "add:$it" }) { path ->
                FocusRow(onClick = { vm.addSource(RomSource.RawPath(path)); onDismiss() }) { f ->
                    RowText("Add $path", f, "Raw path folder")
                }
            }
            item {
                FocusRow(
                    onClick = {
                        if (!confirmUnlink && state.linkCount > 0) {
                            confirmUnlink = true
                        } else {
                            val n = vm.unlinkAll()
                            showMessage("Unlinked $n game${if (n == 1) "" else "s"}. They'll be searched for again.")
                            onDismiss()
                        }
                    },
                ) { f ->
                    RowText(
                        if (confirmUnlink) "Press A again to unlink ${state.linkCount} games" else "Unlink ROM files",
                        f,
                        "Forgets which file each game uses (${state.linkCount} linked), so a copy you've added is found.",
                    )
                }
            }
            item {
                FocusRow(onClick = { onDismiss(); vm.startSetup() }) { f ->
                    RowText("Set up again", f, "Walks through choosing ROM folders and emulators.")
                }
            }
            item {
                FocusRow(onClick = onShareReport) { f ->
                    RowText("Share a bug report…", f, "A text file of the app's status and recent log, to send on Discord. No keys or passwords.")
                }
            }
            item {
                FocusRow(onClick = onAbout) { f ->
                    RowText("About…", f, "Version, privacy, the club's Discord and leaderboard, and licences.")
                }
            }
            if (state.sources.isNotEmpty()) {
                item {
                    Text(
                        "ROM folders (A to remove)",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.sources, key = { "src:${it.key}" }) { source ->
                FocusRow(onClick = { vm.removeSource(source) }) { f ->
                    RowText("Remove ${source.label}", f, if (source is RomSource.Saf) "File picker folder" else "Raw path folder")
                }
            }
        }
    }
}

/**
 * Suggests emulators for a pick that has none, from the Obtainium Emulation Pack. Choosing one
 * opens Obtainium, which installs it from its official source and keeps it updated. Coming back
 * to the app picks up the new emulator by itself.
 */
@Composable
fun GetEmulatorDialog(vm: ClubViewModel, row: PickRow, onOpenLink: (String, String) -> Unit, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    val result by produceState<Suggestions>(Suggestions.Loading, row) {
        value = vm.emulatorSuggestions(row)?.let { Suggestions.Loaded(it) } ?: Suggestions.Failed
    }
    PadDialog(title = "Get an emulator for ${row.pick.consoleName()}", onDismiss = onDismiss, focus = first, wide = true) {
        val list = (result as? Suggestions.Loaded)?.list
        when {
            result == Suggestions.Loading -> Message("Finding emulators…")
            result == Suggestions.Failed -> Message("Couldn't load emulator suggestions. Connect to Wi-Fi and try again.")
            list.isNullOrEmpty() -> Message("No emulator in the Obtainium Emulation Pack plays this yet.")
            else -> Message("Opens Obtainium, which installs it from its official source and keeps it up to date. Come back here when it's installed.")
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(list.orEmpty(), key = { it.app.id }) { rec ->
                FocusRow(
                    onClick = { onOpenLink(rec.app.obtainiumLink, rec.app.url); onDismiss() },
                    modifier = if (rec == list?.firstOrNull()) Modifier.focusRequester(first) else Modifier,
                ) { f ->
                    RowText(
                        "Add ${rec.app.name} to Obtainium",
                        f,
                        listOfNotNull(rec.app.about, if (rec.achievements) "Works with RetroAchievements" else null).joinToString(" · ").ifEmpty { null },
                    )
                }
            }
        }
    }
}

private sealed interface Suggestions {
    data object Loading : Suggestions
    data object Failed : Suggestions
    data class Loaded(val list: List<Recommendation>) : Suggestions
}

/** A question and its answer, for [HelpDialog]. */
data class HelpTopic(val question: String, val answer: String)

/** The game view's ? dialog: a list of questions; A shows an answer, B goes back to the list. */
@Composable
fun HelpDialog(topics: List<HelpTopic>, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    var open by remember { mutableStateOf<HelpTopic?>(null) }
    val back = remember { FocusRequester() }
    PadDialog(title = open?.question ?: "Help", onDismiss = { if (open != null) open = null else onDismiss() }, focus = first, wide = true) {
        val topic = open
        if (topic == null) {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(topics, key = { it.question }) { t ->
                    FocusRow(
                        onClick = { open = t },
                        modifier = if (t == topics.first()) Modifier.focusRequester(first) else Modifier,
                    ) { f -> RowText(t.question, f) }
                }
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        } else {
            Message(topic.answer)
            FocusRow(onClick = { open = null }, modifier = Modifier.focusRequester(back)) { f -> RowText("Back to questions", f) }
            LaunchedEffect(topic) { runCatching { back.requestFocus() } }
        }
    }
}

/** Your own mark on a game: completed somewhere else, did not finish, or none. */
@Composable
fun MarkDialog(title: String, current: PickMark?, onMark: (PickMark?) -> Unit, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    PadDialog(title = "Mark $title", onDismiss = onDismiss, focus = first, wide = true) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            PickMark.entries.forEachIndexed { i, mark ->
                item {
                    FocusRow(onClick = { onMark(mark) }, selected = current == mark, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { f ->
                        RowText(mark.label, f, mark.detail)
                    }
                }
            }
            item {
                FocusRow(onClick = { onMark(null) }, selected = current == null) { f ->
                    RowText("No mark", f, "RetroAchievements decides, if you're signed in.")
                }
            }
        }
    }
}

/**
 * A file chosen by hand that RetroAchievements doesn't recognise for the pick. It may still
 * play (another region, a revision, a patched copy), just without achievements, so offer that,
 * after choosing another file (the usual fix) and before cancelling.
 */
@Composable
fun UnrecognizedFileDialog(choice: UnrecognizedChoice, onChooseAnother: () -> Unit, onUseAnyway: () -> Unit, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    PadDialog(title = "Not a recognized version", onDismiss = onDismiss, focus = first, wide = true) {
        Message(
            "${choice.fileName} isn't a version RetroAchievements recognizes for ${choice.row.pick.displayTitle}. " +
                "It may still play, but achievements won't unlock and it won't count for the club."
        )
        LazyColumn(Modifier.weight(1f, fill = false)) {
            item {
                FocusRow(onClick = onChooseAnother, modifier = Modifier.focusRequester(first)) { f ->
                    RowText("Choose another file…", f, "The game's RetroAchievements page lists the versions it supports.")
                }
            }
            item {
                FocusRow(onClick = onUseAnyway) { f ->
                    RowText("Use anyway (no achievements)", f, "Plays this file. Settings › Unlink ROM files undoes it.")
                }
            }
            item {
                FocusRow(onClick = onDismiss) { f -> RowText("Cancel", f) }
            }
        }
    }
}

/**
 * Shown once, after the first hack is saved in a Hacks folder: Cocoon keeps its settings to
 * itself, so this app can't turn on its sub-folder option; it can only say where it is.
 */
@Composable
fun CocoonTipDialog(onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    PadDialog(title = "Saved in the Hacks folder", onDismiss = onDismiss, focus = first, wide = true, hints = { PadHint("A", "OK") }) {
        Message(
            "The hack was saved in that console's Hacks folder. This app plays it from there. To see it in " +
                "Cocoon as well, turn on Cocoon's option to show games in sub-folders for that console, in Cocoon's settings."
        )
        FocusRow(onClick = onDismiss, modifier = Modifier.focusRequester(first)) { f -> RowText("Got it", f) }
    }
}

/** A row in the game view's ⋮ menu: what it says, an optional second line, and what A does. */
internal data class MenuItem(val label: String, val detail: String? = null, val run: () -> Unit)

/** The game view's ⋮ menu. Choosing an item closes the menu, then runs it. */
@Composable
internal fun GameMenuDialog(title: String, items: List<MenuItem>, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    PadDialog(title = title, onDismiss = onDismiss, focus = first, wide = true) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items.forEachIndexed { i, item ->
                item {
                    FocusRow(
                        onClick = { onDismiss(); item.run() },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { f -> RowText(item.label, f, item.detail) }
                }
            }
        }
    }
}
