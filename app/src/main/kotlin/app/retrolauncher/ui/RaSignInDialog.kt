package app.retrolauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.retrolauncher.ra.RaApi

/**
 * Signs in to RetroAchievements with a username and Web API key, or shows who's signed in.
 * The key page link opens RetroAchievements' account settings, where the key is in the Keys
 * section; "Paste key" fills it from the clipboard, so a controller can do the rest.
 */
@Suppress("DEPRECATION") // LocalClipboardManager: the replacement's API is suspend-only and still settling.
@Composable
fun RaSignInDialog(vm: ClubViewModel, ra: RaState, onOpenUrl: (String) -> Unit, showMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    var user by rememberSaveable { mutableStateOf(ra.user.orEmpty()) }
    var key by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val signedIn = ra.user != null && !ra.keyRefused

    fun signIn() {
        if (busy) return
        busy = true
        error = null
        vm.raSignIn(user, key) { problem ->
            busy = false
            if (problem == null) {
                showMessage("Signed in to RetroAchievements as ${user.trim()}.")
                onDismiss()
            } else {
                error = problem
            }
        }
    }

    PadDialog(title = "RetroAchievements", onDismiss = onDismiss, focus = first, wide = true) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (signedIn) {
                Message("Signed in as ${ra.user}. Your progress shows on each pick and in History.")
                FocusRow(onClick = { vm.raSignOut(); showMessage("Signed out of RetroAchievements."); onDismiss() }, modifier = Modifier.focusRequester(first)) { f ->
                    RowText("Sign out", f, "Forgets your username and key on this device.")
                }
                FocusRow(onClick = { onOpenUrl(RaApi.KEY_PAGE) }) { f ->
                    RowText("Open your RetroAchievements settings", f, "Where your Web API key is, under Keys.")
                }
            } else {
                Message(
                    if (ra.keyRefused) "RetroAchievements no longer accepts the saved key. Sign in again with your current one."
                    else "Sign in to see your progress on each pick. You'll need your Web API key: it's in your " +
                        "RetroAchievements settings, under Keys. It's kept encrypted on this device."
                )
                FocusRow(onClick = { onOpenUrl(RaApi.KEY_PAGE) }, modifier = Modifier.focusRequester(first)) { f ->
                    RowText("Get your Web API key…", f, "Opens retroachievements.org › Settings. Copy the key under Keys, then come back.")
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it; error = null },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim(); error = null },
                    label = { Text("Web API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                )
                FocusRow(onClick = {
                    val text = clipboard.getText()?.text?.trim().orEmpty()
                    if (text.isEmpty()) error = "The clipboard is empty. Copy your key from the RetroAchievements page first."
                    else { key = text; error = null }
                }) { f ->
                    RowText("Paste key", f, "Fills in the key you copied.")
                }
                FocusRow(onClick = ::signIn) { f ->
                    RowText(if (busy) "Checking with RetroAchievements…" else "Sign in", f, enabled = !busy)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                }
            }
        }
    }
}
