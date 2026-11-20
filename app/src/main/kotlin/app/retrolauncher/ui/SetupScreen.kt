package app.retrolauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.retrolauncher.library.RomSource

/**
 * First-run setup, three steps: what the app does, choosing the ROMs folder, and how the current
 * picks look with the emulators installed (assuming emulators such as RetroArch are already set
 * up). B goes back a step. Shown until finished or skipped, while there are no ROM folders
 * ([shouldShowSetup]).
 */
@Composable
fun SetupScreen(
    vm: ClubViewModel,
    state: ClubState,
    onAddFolder: () -> Unit,
    onRequestAllFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val first = remember(step) { FocusRequester() }
    BackHandler(enabled = step > 0) { step-- }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPad { pad -> if (pad == Pad.B && step > 0) { step--; true } else false },
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 620.dp)) {
                Text(
                    "Step ${step + 1} of 3",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                when (step) {
                    0 -> WelcomeStep(first, onStart = { step = 1 }, onSkip = vm::finishSetup)
                    1 -> FolderStep(vm, state, first, onAddFolder, onRequestAllFiles, onContinue = { step = 2 })
                    else -> EmulatorStep(state, first, onDone = vm::finishSetup)
                }
            }
        }
        Footer {
            if (step > 0) PadHint("B", "Back")
            PadHint("A", "Select")
        }
    }

    // Focus the step's first button when it appears (see takeControllerFocus).
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val view = LocalView.current
    LaunchedEffect(step, windowFocused) { if (windowFocused) takeControllerFocus(view, first) }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(8.dp))
    Text(body, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
    Spacer(Modifier.height(16.dp))
}

/** A setup choice: a focusable row with a label and an optional line of detail. */
@Composable
private fun Choice(label: String, detail: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusRow(onClick = onClick, modifier = modifier) { focused ->
        val color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground
        Column {
            Text(label, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, color = color.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun WelcomeStep(first: FocusRequester, onStart: () -> Unit, onSkip: () -> Unit) {
    StepTitle(
        "Welcome",
        "This app shows what the Play Retro Games Club is playing right now (Game of the Week, " +
            "Game of the Month, Romhack of the Month and Game of the Season) and starts each one from " +
            "your own ROMs, in the emulators you already have. Setup takes a minute.",
    )
    Choice("Get started", modifier = Modifier.focusRequester(first), onClick = onStart)
    Choice("Skip for now", "You can set up later from Settings.", onClick = onSkip)
}

@Composable
private fun FolderStep(
    vm: ClubViewModel,
    state: ClubState,
    first: FocusRequester,
    onAddFolder: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onContinue: () -> Unit,
) {
    val suggestions = remember(state.sources, state.hasAllFilesAccess) { vm.rawFolderSuggestions() }
    val hasFolders = state.sources.isNotEmpty()
    StepTitle(
        "Where are your games?",
        "Choose the folder that holds one folder per console, like ROMs/gba and ROMs/snes. " +
            "ES-DE and NextUI layouts both work, and games in subfolders are found too.",
    )
    if (hasFolders) {
        // Once a folder is in, the next step is the likely choice.
        Choice("Continue", "${state.sources.size} folder${if (state.sources.size == 1) "" else "s"} added", Modifier.focusRequester(first), onContinue)
    }
    Choice(
        if (hasFolders) "Add another folder…" else "Choose your ROMs folder…",
        "Opens Android's folder picker.",
        if (hasFolders) Modifier else Modifier.focusRequester(first),
        onAddFolder,
    )
    for (path in suggestions) {
        Choice("Use $path", "Found on this device") { vm.addSource(RomSource.RawPath(path)) }
    }
    if (!state.hasAllFilesAccess) {
        Choice(
            "Find it for me…",
            "Allow access to all files, and the app looks for a ROMs folder on this device and its SD card.",
            onClick = onRequestAllFiles,
        )
    }
    if (hasFolders) {
        Spacer(Modifier.height(12.dp))
        Text("Added", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        for (source in state.sources) {
            Text(source.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun EmulatorStep(state: ClubState, first: FocusRequester, onDone: () -> Unit) {
    StepTitle(
        "Emulators",
        "Each pick starts in an emulator you have installed. Here's how the club's current picks look:",
    )
    if (state.rows.isEmpty()) {
        Text(state.status ?: "Loading the club's games…", fontSize = 14.sp)
    }
    for (row in homeCards(state.rows).mapNotNull { it.row }) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.pick.displayTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.pick.consoleName(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
            SetupStatus(row.state)
        }
    }
    Text(
        "Missing ROM or No emulator? Open the game later for what's needed.",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
    )
    Choice("Done", modifier = Modifier.focusRequester(first), onClick = onDone)
}

/** A pick's status spelled out, for setup, where there's room and nothing is learned yet. */
@Composable
private fun SetupStatus(state: PickState) {
    val (text, color) = when (state) {
        PickState.READY -> "Installed and ready to play" to Color(0xFF5BD68A)
        PickState.MISSING -> "Missing ROM" to Color(0xFFE8835A)
        PickState.NO_EMULATOR -> "No emulator installed" to Color(0xFFE8C15A)
        PickState.CHOOSE_COPY -> "Several copies found" to Color(0xFFE8C15A)
        PickState.CHECKING -> "Checking…" to MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        PickState.UNKNOWN -> "Unknown console" to MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    }
    Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}
