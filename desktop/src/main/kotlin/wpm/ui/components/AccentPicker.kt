package wpm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import wpm.AppState
import wpm.tr
import wpm.ui.AccentPresets
import wpm.ui.DarkColors
import wpm.ui.LightColors
import wpm.ui.Theme
import wpm.ui.readableOn
import wpm.ui.withAccent
import javax.swing.JColorChooser
import javax.swing.SwingUtilities

@OptIn(ExperimentalLayoutApi::class)
/** Settings > Appearance: the app's accent color, from a row of folded swatches or any color. */
@Composable
fun AccentColorSetting(app: AppState, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Column(modifier) {
        Text(tr("Accent color"), color = c.text)
        MutedText(tr("Buttons, highlights and the page ribbon use this color."))
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val current = app.themeColor
            for (preset in AccentPresets) {
                // Show each swatch the way the current theme would draw it.
                val color = (if (c.dark) DarkColors else LightColors).withAccent(preset.rgb).accent
                Swatch(color, tr(preset.name), selected = current == preset.rgb) { app.updateThemeColor(preset.rgb) }
            }
            val custom = current != null && AccentPresets.none { it.rgb == current }
            Swatch(
                if (custom) c.accent else c.inset, tr("Custom color…"), selected = custom, icon = !custom,
            ) {
                // The Swing color chooser blocks; it runs on Swing's thread, outside Compose.
                val start = java.awt.Color(current ?: (c.accent.toArgb() and 0xFFFFFF))
                SwingUtilities.invokeLater {
                    JColorChooser.showDialog(app.window, tr("Accent color"), start)?.let { app.updateThemeColor(it.rgb and 0xFFFFFF) }
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    val c = Theme.colors
    val shape = CutCornerShape(topEnd = 9.dp, bottomStart = 9.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.size(36.dp)
            .border(2.dp, if (selected || focused) c.text else Color.Transparent, shape)
            .padding(4.dp)
            .background(color, shape)
            .selectable(selected, interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val tint = readableOn(color)
        when {
            icon -> Icon(Icons.Outlined.Palette, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
            selected -> Icon(Icons.Outlined.Check, null, tint = tint, modifier = Modifier.size(16.dp))
        }
    }
}
