package wpm.ui.components

import wpm.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import wpm.AppState
import wpm.ToastKind
import wpm.data.MediaItem
import wpm.media.PropertyType
import wpm.media.WallpaperProperty
import wpm.ui.Theme
import java.util.Locale
import javax.swing.JColorChooser
import javax.swing.SwingUtilities
import kotlin.io.path.Path
import kotlin.io.path.name

/**
 * The settings a web wallpaper (LivelyProperties.json) or shader (// @slider ...) offers.
 * Changes reach a showing wallpaper straight away and are remembered for the item.
 * Shows nothing when the wallpaper has no settings.
 */
@Composable
fun PropertyEditor(
    app: AppState,
    item: MediaItem,
    modifier: Modifier = Modifier,
    /** Overrides what the item declares (the shader editor passes the code being edited). */
    declared: List<WallpaperProperty>? = null,
    onChanged: () -> Unit = {},
) {
    val properties = declared ?: remember(item.id, app.libraryVersion) { runCatching { app.propertiesOf(item) }.getOrDefault(emptyList()) }
    if (properties.isEmpty()) return
    var values by remember(item.id, app.libraryVersion, properties) { mutableStateOf(app.properties.values(item.id, properties)) }
    val scope = rememberCoroutineScope()
    fun change(property: WallpaperProperty, value: JsonPrimitive) {
        if (property.type != PropertyType.BUTTON) values = values + (property.name to property.normalize(value))
        scope.launch {
            try {
                withContext(Dispatchers.IO) { app.setProperty(item, property, value) }
                onChanged()
            } catch (e: Exception) {
                app.toast(tr("Couldn't change {0}: {1}", property.label, e.message), ToastKind.Error)
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("WALLPAPER SETTINGS"), color = Theme.colors.textSubtle, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                values = properties.associate { it.name to it.default }
                scope.launch {
                    withContext(Dispatchers.IO) { app.resetProperties(item) }
                    onChanged()
                }
            }) { Text(tr("Reset")) }
        }
        properties.forEach { property ->
            PropertyControl(app, item, property, values[property.name] ?: property.default) { change(property, it) }
        }
    }
}

@Composable
private fun PropertyControl(app: AppState, item: MediaItem, property: WallpaperProperty, value: JsonPrimitive, onChange: (JsonPrimitive) -> Unit) {
    val c = Theme.colors
    when (property.type) {
        PropertyType.LABEL -> MutedText(value.content.ifEmpty { property.label })
        PropertyType.BUTTON -> OutlinedButton(onClick = { onChange(JsonPrimitive(true)) }, modifier = Modifier.fillMaxWidth()) {
            Text(value.content.ifEmpty { property.label })
        }
        PropertyType.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(property.label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(value.booleanOrNull ?: false, { onChange(JsonPrimitive(it)) })
        }
        PropertyType.SLIDER -> Column {
            val number = value.doubleOrNull ?: property.min
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(property.label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(formatNumber(number, property.step), color = c.textMuted, fontSize = 12.sp)
            }
            val span = property.max - property.min
            val steps = if (property.step > 0 && span / property.step <= 200) (span / property.step).toInt() - 1 else 0
            Slider(
                value = number.toFloat(),
                onValueChange = { onChange(JsonPrimitive(it.toDouble())) },
                valueRange = property.min.toFloat()..property.max.toFloat(),
                steps = steps.coerceAtLeast(0),
            )
        }
        PropertyType.COLOR -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(property.label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val rgb = WallpaperProperty.parseColor(value.content) ?: 0
            val shape = CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp)
            Box(
                Modifier.size(width = 64.dp, height = 30.dp).background(Color(0xFF000000 or rgb.toLong()), shape)
                    .border(1.dp, c.cardBorder, shape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable {
                        // The Swing color chooser blocks; it runs on Swing's thread, outside Compose.
                        SwingUtilities.invokeLater {
                            JColorChooser.showDialog(app.window, property.label, java.awt.Color(rgb))?.let { picked ->
                                onChange(JsonPrimitive(WallpaperProperty.formatColor(picked.rgb)))
                            }
                        }
                    },
            )
        }
        PropertyType.DROPDOWN, PropertyType.FOLDER_DROPDOWN -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(property.label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val choices = remember(item.id, property.name) {
                if (property.type == PropertyType.DROPDOWN) property.items else app.properties.folderFiles(item, property)
            }
            val current = if (property.type == PropertyType.DROPDOWN) choices.getOrNull(value.intOrNull ?: -1) else value.content
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, enabled = choices.isNotEmpty()) {
                    Text(current?.takeIf { it.isNotEmpty() } ?: tr("Choose…"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp))
                }
                DropdownMenu(open, { open = false }) {
                    choices.forEachIndexed { i, choice ->
                        DropdownMenuItem(text = { Text(choice) }, onClick = {
                            open = false
                            onChange(if (property.type == PropertyType.DROPDOWN) JsonPrimitive(i) else JsonPrimitive(choice))
                        })
                    }
                }
            }
        }
        PropertyType.TEXTBOX -> Column {
            Text(property.label, color = c.text, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            var text by remember(item.id, property.name) { mutableStateOf(value.content) }
            val shape = foldedShape(6.dp)
            BasicTextField(
                text, { text = it; onChange(JsonPrimitive(it)) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().background(c.inset, shape).border(1.dp, c.cardBorder, shape).padding(10.dp),
            )
        }
        PropertyType.IMAGE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(property.label, color = c.text, fontSize = 13.sp)
                Text(value.content.takeIf { it.isNotEmpty() }?.let { Path(it).name } ?: tr("No picture yet"), color = c.textMuted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            var picking by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { picking = true }) { Text(tr("Choose…")) }
            if (picking) {
                MediaPickerDialog(app, tr("Choose a picture"), multiple = false, onDismiss = { picking = false },
                    filter = { it.isImage && !it.isRemote }) { picked ->
                    picked.firstOrNull()?.path?.let { onChange(JsonPrimitive(it)) }
                }
            }
        }
    }
}

private fun formatNumber(value: Double, step: Double): String =
    if (step >= 1 && value == Math.rint(value)) value.toLong().toString()
    else String.format(Locale.ROOT, if (step >= 0.1) "%.1f" else "%.2f", value)
