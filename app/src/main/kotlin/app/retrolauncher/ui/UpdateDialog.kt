package app.retrolauncher.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.retrolauncher.launch.AppUpdate
import java.util.Locale

/**
 * Settings › "Version X is available…": what's new, then one row that does the next step of
 * updating in the app (download, allow installs, install, try again), with Obtainium and the
 * release page as the other ways. Closing it leaves a download running.
 */
@Composable
fun UpdateDialog(
    update: AppUpdate,
    step: UpdateStep,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onAllow: () -> Unit,
    onObtainium: () -> Unit,
    onReleasePage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = rememberFirstFocus()
    PadDialog(title = "Version ${update.version} is available", onDismiss = onDismiss, focus = first, wide = true) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            val notes = plainNotes(update.notes)
            if (notes.isNotEmpty()) {
                Text(notes, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            when (step) {
                is UpdateStep.Downloading -> {
                    Message(
                        if (step.total > 0) "Downloading… ${megabytes(step.done)} of ${megabytes(step.total)}"
                        else "Downloading… ${megabytes(step.done)}"
                    )
                    val fraction = if (step.total > 0) (step.done.toFloat() / step.total).coerceIn(0f, 1f) else null
                    val bar = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar) else LinearProgressIndicator(modifier = bar)
                }
                UpdateStep.NeedsPermission -> Message(
                    "Downloaded. To install it, Android needs you to let PRGC Launcher install apps: turn on " +
                        "\"Allow from this source\", then come back here. You only need to do this once."
                )
                UpdateStep.Installing -> Message("Android is asking you to confirm. The launcher closes while it updates; open it again afterwards.")
                is UpdateStep.Failed -> Message(step.message)
                UpdateStep.Idle -> if (update.apkUrl == null) Message("This release has no APK to install from here. Use Obtainium or the release page.")
            }

            val primary = Modifier.focusRequester(first)
            when {
                step is UpdateStep.Downloading -> FocusRow(onClick = onCancel, modifier = primary) { f ->
                    RowText("Cancel download", f)
                }
                step == UpdateStep.NeedsPermission -> FocusRow(onClick = onAllow, modifier = primary) { f ->
                    RowText("Allow installs from PRGC Launcher…", f, "Opens Android's settings for this app.")
                }
                step == UpdateStep.Installing -> FocusRow(onClick = onInstall, modifier = primary) { f ->
                    RowText("Show Android's prompt again", f, "If you closed it without choosing.")
                }
                update.apkUrl != null -> FocusRow(onClick = onStart, modifier = primary) { f ->
                    RowText(
                        if (step is UpdateStep.Failed) "Try again" else "Download and install",
                        f,
                        listOfNotNull(
                            update.apkSize.takeIf { it > 0 }?.let { megabytes(it) },
                            "from GitHub. Android asks you to confirm.",
                        ).joinToString(" "),
                    )
                }
            }
            FocusRow(onClick = onObtainium, modifier = if (update.apkUrl == null) primary else Modifier) { f ->
                RowText("Update with Obtainium instead", f, "If you use Obtainium to keep apps up to date.")
            }
            FocusRow(onClick = onReleasePage) { f ->
                RowText("Release page", f, "What's new and the APK, on GitHub.")
            }
        }
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

/**
 * Release notes as plain text for the dialog: Markdown headings, bold, code marks and links are
 * reduced to their words, bullets to "•", and long notes are cut at [max] characters.
 */
internal fun plainNotes(markdown: String, max: Int = 700): String {
    val text = markdown.replace("\r\n", "\n").lines().joinToString("\n") { line ->
        line.trimEnd()
            .replace(Regex("""^\s{0,3}#{1,6}\s+"""), "")
            .replace(Regex("""^(\s*)[-*+]\s+"""), "$1• ")
            .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
            .replace(Regex("""\*\*|__|`"""), "")
    }.replace(Regex("""\n{3,}"""), "\n\n").trim()
    if (text.length <= max) return text
    val cut = text.take(max)
    return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: max).trimEnd() + "…"
}
