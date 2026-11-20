package app.retrolauncher.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Gamepad buttons used by the launcher. */
enum class Pad { A, B, X, Y, L1, R1, START, SELECT }

fun padButton(keyCode: Int): Pad? = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> Pad.A
    KeyEvent.KEYCODE_BUTTON_B -> Pad.B
    KeyEvent.KEYCODE_BUTTON_X -> Pad.X
    KeyEvent.KEYCODE_BUTTON_Y -> Pad.Y
    KeyEvent.KEYCODE_BUTTON_L1 -> Pad.L1
    KeyEvent.KEYCODE_BUTTON_R1 -> Pad.R1
    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> Pad.START
    KeyEvent.KEYCODE_BUTTON_SELECT -> Pad.SELECT
    else -> null
}

/**
 * Handles gamepad buttons on key down. [handler] returns true if it used the button. Android
 * sends a fallback key (BUTTON_A → DPAD_CENTER, BUTTON_B → BACK) only for an unhandled key
 * down, so consuming the down event stops the action firing twice. Unused buttons bubble up
 * and still get their fallback.
 */
fun Modifier.onPad(handler: (Pad) -> Boolean): Modifier = onKeyEvent { event ->
    val pad = padButton(event.nativeKeyEvent.keyCode) ?: return@onKeyEvent false
    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && handler(pad)
}

/**
 * A full-width list row that can be focused with the D-pad, activated with A, Enter or the
 * D-pad centre, and tapped on the touchscreen. [onY] gives the row a secondary action.
 */
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onY: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val background = when {
        focused -> colors.primary
        selected -> colors.surfaceVariant
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .onPad { pad ->
                when (pad) {
                    Pad.A -> { onClick(); true }
                    Pad.Y -> onY?.let { it(); true } ?: false
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

/** A button-glyph hint for the footer bar, such as "Ⓐ Launch". */
@Composable
fun PadHint(button: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 20.dp)) {
        Text(
            button,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text(label, modifier = Modifier.padding(start = 6.dp), fontSize = 14.sp)
    }
}

/**
 * Focuses [target] for controller use. Until a key is pressed, Android is in touch mode, where
 * views can't take focus, so a focus request at launch is ignored and the first D-pad press is
 * spent leaving touch mode. `requestFocusFromTouch()` leaves touch mode and gives the Compose
 * view focus, so the request sticks. Touching the screen returns to touch mode as usual.
 */
fun takeControllerFocus(view: android.view.View, target: androidx.compose.ui.focus.FocusRequester) {
    if (!view.hasFocus()) view.requestFocusFromTouch()
    runCatching { target.requestFocus() }
}
