package wpm.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import wpm.win32.Hotkey

/** AWT key codes that differ from Windows virtual-key codes. */
private fun windowsKeyCode(awt: Int): Int = when (awt) {
    155 -> 0x2D // Insert
    127 -> 0x2E // Delete
    else -> awt
}

/**
 * Shows a shortcut; click it, then press the new keys. Esc cancels, Backspace removes the shortcut.
 * [onHint] explains keys that can't be used.
 */
@Composable
fun ShortcutField(keys: String, onChange: (String) -> Unit, onHint: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var recording by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(recording) { if (recording) focus.requestFocus() }
    OutlinedButton(
        onClick = { recording = !recording },
        enabled = enabled,
        modifier = modifier
            .widthIn(min = 150.dp)
            .focusRequester(focus)
            .onFocusChanged { if (!it.isFocused) recording = false }
            .onPreviewKeyEvent { event ->
                if (!recording) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (event.key) {
                    Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.ShiftLeft, Key.ShiftRight, Key.MetaLeft, Key.MetaRight -> {}
                    Key.Escape -> recording = false
                    Key.Backspace -> {
                        recording = false
                        onChange("")
                    }
                    else -> {
                        val code = windowsKeyCode(event.key.nativeKeyCode)
                        val hotkey = Hotkey(code, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed)
                        when {
                            Hotkey.keyName(code) == null -> onHint("Use a letter, digit, F-key or arrow key")
                            !hotkey.hasModifier -> onHint("Hold Ctrl, Alt or Shift too, so the keys still type normally elsewhere")
                            else -> {
                                recording = false
                                onChange(hotkey.toString())
                            }
                        }
                    }
                }
                true
            },
    ) {
        Text(if (recording) "Press keys…" else keys.ifEmpty { "None" })
    }
}
