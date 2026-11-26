package app.retrolauncher.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The club's own pages, as linked from its site. */
object ClubLinks {
    const val DISCORD = "https://joeysrh.link/discord"
    const val LEADERBOARD = "https://www.playretrogamesclub.com/leaderboards/"
}

/** Settings › About: the version, what the app sends where, the club's links, and the licences. */
@Composable
fun AboutDialog(update: app.retrolauncher.launch.AppUpdate?, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var licencesOpen by remember { mutableStateOf(false) }
    val first = rememberFirstFocus()

    PadDialog(title = "About", onDismiss = onDismiss, focus = first, wide = true) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Message(
                "PRGC Launcher, version $version" + (update?.let { " (version ${it.version} is available)" } ?: "") +
                    ". Not affiliated with Joey's Retro Handhelds, the Play Retro Games Club or RetroAchievements."
            )
            Message(
                "Privacy: no analytics, ads or tracking. The app only talks to the club's site (its picks " +
                    "and their art), RetroAchievements (ROM lists, box art and, if you sign in, your progress) " +
                    "and GitHub (emulator lists, romhack patches, and checking for and downloading a new version). Your RetroAchievements key " +
                    "stays on this device, encrypted."
            )
            FocusRow(onClick = { onOpenUrl(ClubLinks.DISCORD) }, modifier = Modifier.focusRequester(first)) { f ->
                RowText("Club Discord", f, "Chat about the picks with the club.")
            }
            FocusRow(onClick = { onOpenUrl(ClubLinks.LEADERBOARD) }) { f ->
                RowText("Club leaderboard", f, "On the club's page.")
            }
            FocusRow(onClick = { onOpenUrl(app.retrolauncher.launch.UpdateCheck.REPO + "/releases") }) { f ->
                RowText("Releases", f, "What's new in each version, on GitHub.")
            }
            FocusRow(onClick = { licencesOpen = true }) { f ->
                RowText("Licences", f, "This app's licence, and the code and libraries it uses.")
            }
        }
    }
    if (licencesOpen) LicencesDialog(onDismiss = { licencesOpen = false })
}

/** The app's licence and third-party notices, bundled into the app at build time. */
@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf("LICENSE", "THIRD-PARTY-NOTICES.md").joinToString("\n\n") { name ->
            runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
        }.let(::plainNotices)
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val first = rememberFirstFocus()

    PadDialog(title = "Licences", onDismiss = onDismiss, focus = first, fullWidth = true, hints = { PadHint("B", "Close") }) {
        // One focusable, scrolling block: the D-pad scrolls it, a page at a time with L1/R1.
        Box(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                .focusRequester(first)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionDown -> 160f
                        Key.DirectionUp -> -160f
                        Key.ButtonR1, Key.PageDown -> 800f
                        Key.ButtonL1, Key.PageUp -> -800f
                        else -> return@onPreviewKeyEvent false
                    }
                    scope.launch { scroll.animateScrollBy(step, tween(150)) }
                    true
                }
                .focusable()
                .verticalScroll(scroll)
                .padding(10.dp),
        ) {
            Text(text, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Markdown notices as plain text: no heading marks, code fences or table rules; links show their address. */
internal fun plainNotices(markdown: String): String = markdown.lines()
    .filterNot { it.trim().startsWith("```") || it.trim().matches(Regex("""\|?[-| :]+\|?""")) }
    .joinToString("\n") { line ->
        line.trimStart('#', ' ').takeIf { line.startsWith("#") }
            ?: line.trim().takeIf { it.startsWith("|") }?.trim('|')?.split('|')?.joinToString(" — ") { it.trim() }
            ?: line
    }
    .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1 ($2)")
    .replace("`", "")
