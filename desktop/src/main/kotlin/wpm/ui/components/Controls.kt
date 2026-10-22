package wpm.ui.components

import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import wpm.ui.Theme

/*
 * Origami controls. They keep the names and parameters of the Material controls they replace,
 * so pages only switch imports. Every button is a sheet with two folded corners; filled ones
 * carry a crease of light, and hovering one folds its corner down a little further.
 */

/** Button outline: top-right and bottom-left corners folded away. */
fun foldedShape(fold: Dp = 8.dp): Shape = CutCornerShape(topStart = 0.dp, topEnd = fold, bottomEnd = 0.dp, bottomStart = fold)

private enum class ButtonKind { Filled, Outlined, Text }

@Composable
private fun FoldButton(
    kind: ButtonKind,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    danger: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tone = if (danger) c.danger else c.accent
    val bg by animateColorAsState(
        when {
            kind == ButtonKind.Filled && !enabled -> c.inset
            kind == ButtonKind.Filled -> if (hovered) lerpColor(tone, Color.White, 0.10f) else tone
            kind == ButtonKind.Outlined && hovered && enabled -> lerpColor(c.cardBg, tone, 0.08f)
            kind == ButtonKind.Outlined -> c.cardBg
            hovered && enabled -> tone.copy(alpha = 0.09f)
            else -> Color.Transparent
        },
        tween(160),
    )
    val fg = when {
        !enabled -> c.textSubtle
        kind == ButtonKind.Filled -> c.accentText
        kind == ButtonKind.Text -> tone
        danger -> c.danger
        else -> c.text
    }
    val borderColor by animateColorAsState(if (hovered && enabled) tone else c.cardBorder, tween(160))
    val fold by animateDpAsState(if (hovered && enabled) 11.dp else 7.dp, spring(stiffness = Spring.StiffnessMediumLow))
    val scale by animateFloatAsState(if (pressed && enabled) 0.965f else 1f, spring(stiffness = Spring.StiffnessHigh))
    val shape = foldedShape(fold)
    val elevation = if (kind == ButtonKind.Filled && enabled) (if (hovered) 6.dp else 2.dp) else 0.dp

    Row(
        modifier
            .scale(scale)
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = tone, spotColor = tone) else Modifier)
            .clip(shape)
            .background(bg)
            .then(if (kind == ButtonKind.Filled && enabled) Modifier.crease(if (hovered) 0.20f else 0.13f) else Modifier)
            .then(if (kind == ButtonKind.Outlined) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .focusRing(focused, shape)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .defaultMinSize(minWidth = 40.dp, minHeight = 38.dp)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg, LocalTextStyle provides MaterialTheme.typography.labelLarge) {
            content()
        }
    }
}

private val ButtonPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
private val TextButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    contentPadding: PaddingValues = ButtonPadding,
    content: @Composable RowScope.() -> Unit,
) = FoldButton(ButtonKind.Filled, onClick, modifier, enabled, danger, contentPadding, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    contentPadding: PaddingValues = ButtonPadding,
    content: @Composable RowScope.() -> Unit,
) = FoldButton(ButtonKind.Outlined, onClick, modifier, enabled, danger, contentPadding, content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    contentPadding: PaddingValues = TextButtonPadding,
    content: @Composable RowScope.() -> Unit,
) = FoldButton(ButtonKind.Text, onClick, modifier, enabled, danger, contentPadding, content)

/** An icon sized for buttons. */
@Composable
fun ButtonIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(icon, null, modifier.size(18.dp))
}

/** A square icon button whose hover state is a small folded diamond. */
@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg by animateColorAsState(if ((hovered || focused) && enabled) c.accent.copy(alpha = 0.12f) else Color.Transparent, tween(140))
    val tilt by animateFloatAsState(if ((hovered || focused) && enabled) 45f else 0f, spring(stiffness = Spring.StiffnessMediumLow))
    Box(
        modifier.size(36.dp)
            .focusRing(focused, CutCornerShape(6.dp))
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(26.dp).rotate(tilt).background(bg, CutCornerShape(4.dp)))
        CompositionLocalProvider(LocalContentColor provides if (enabled) (if (hovered) c.accent else c.textMuted) else c.textSubtle.copy(alpha = 0.5f)) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** On/off toggle: a folded hexagonal track with a diamond that slides across. */
@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val track by animateColorAsState(
        when {
            !enabled -> c.inset
            checked -> c.accent
            else -> c.inset
        },
        tween(180),
    )
    val position by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium))
    val spin by animateFloatAsState(if (checked) 45f else 0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium))
    val shape = CutCornerShape(percent = 50)
    Box(
        modifier.size(width = 46.dp, height = 24.dp)
            .clip(shape)
            .background(track)
            .then(if (checked && enabled) Modifier.crease(0.14f) else Modifier)
            .border(1.dp, if (checked && enabled) Color.Transparent else if (hovered) c.accent else c.cardBorder, shape)
            .focusRing(focused, shape)
            .hoverable(interaction, enabled)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch) { onCheckedChange(it) }
                        .pointerHoverIcon(PointerIcon.Hand)
                } else Modifier.semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = position).size(20.dp).padding(3.dp).rotate(spin)
                .shadow(2.dp, CutCornerShape(3.dp))
                .background(if (enabled) (if (checked) Color.White else c.textMuted) else c.textSubtle, CutCornerShape(3.dp)),
        )
    }
}

/** The recessed tray that holds a row of [SegmentedButton]s. */
@Composable
fun SingleChoiceSegmentedButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Theme.colors
    val shape = foldedShape(9.dp)
    Row(
        modifier.height(38.dp).clip(shape).background(c.inset).border(1.dp, c.cardBorder, shape).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

object SegmentedButtonDefaults {
    /** Kept for call-site compatibility; the tray gives the row its outline. */
    @Suppress("UNUSED_PARAMETER")
    fun itemShape(index: Int, count: Int): Shape = RectangleShape
}

/** One option in a [SingleChoiceSegmentedButtonRow]; the chosen one is a raised, folded tab. */
@Composable
fun SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg by animateColorAsState(
        when {
            selected -> c.cardLit
            hovered -> c.cardBg.copy(alpha = 0.55f)
            else -> Color.Transparent
        },
        tween(180),
    )
    val fg by animateColorAsState(if (selected) c.accent else if (hovered) c.text else c.textMuted, tween(180))
    val tab = foldedShape(6.dp)
    Box(
        modifier.height(32.dp)
            .then(if (selected) Modifier.shadow(2.dp, tab, ambientColor = c.shadow, spotColor = c.shadow) else Modifier)
            .clip(tab)
            .background(bg)
            .drawWithContent {
                drawContent()
                if (selected) drawFoldFlap(6.dp.toPx(), c.accent.copy(alpha = 0.55f), c.shadow)
            }
            .focusRing(focused, tab)
            .hoverable(interaction, enabled)
            .selectable(selected, interaction, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg, LocalTextStyle provides MaterialTheme.typography.labelLarge) { label() }
    }
}

/** A toggle chip (e.g. "Favorites"). */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg by animateColorAsState(if (selected) c.accentSoft else if (hovered) c.cardBg else Color.Transparent, tween(160))
    val shape = foldedShape(8.dp)
    Row(
        modifier.height(38.dp).clip(shape).background(bg)
            .border(1.dp, if (selected || hovered) c.accent else c.cardBorder, shape)
            .focusRing(focused, shape)
            .hoverable(interaction)
            .toggleable(selected, interaction, indication = null, role = Role.Checkbox) { onClick() }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) c.accent else c.text,
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) {
            leadingIcon?.invoke()
            label()
        }
    }
}

/** A compact search box on a recessed, folded strip. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val edge by animateColorAsState(if (focused) c.accent else if (hovered) c.textSubtle else c.cardBorder, tween(160))
    val shape = foldedShape(9.dp)
    BasicTextField(
        value, onValueChange, singleLine = true, interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        modifier = modifier.height(38.dp).hoverable(interaction),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().clip(shape).background(if (focused) c.cardLit else c.inset).border(1.dp, edge, shape)
                    .drawWithContent {
                        drawContent()
                        if (focused) drawRect(c.accent, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = if (focused) c.accent else c.textMuted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = c.textSubtle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    field()
                }
                if (value.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close, "Clear", tint = c.textMuted,
                        modifier = Modifier.size(16.dp).clickable { onValueChange("") }.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        },
    )
}

/** A row in a side list (playlists, shaders); the chosen one wears the vermilion fold. */
@Composable
fun ListEntry(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> c.accentSoft
            hovered -> c.inset
            else -> Color.Transparent
        },
        tween(160),
    )
    val bar by animateDpAsState(if (selected) 3.dp else 0.dp, spring(stiffness = Spring.StiffnessMedium))
    val shape = CutCornerShape(topEnd = 10.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(bg)
            .drawWithContent {
                drawContent()
                if (bar > 0.dp) drawRect(c.accent, size = Size(bar.toPx(), size.height))
                if (selected) drawFoldFlap(10.dp.toPx(), c.accent, c.shadow)
            }
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A short single-line entry (numbers, times) matching the height of buttons. */
@Composable
fun NumberField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier.width(72.dp)) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = foldedShape(8.dp)
    BasicTextField(
        value, onValueChange, singleLine = true, interactionSource = interaction,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = c.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        cursorBrush = SolidColor(c.accent),
        modifier = modifier.height(38.dp),
        decorationBox = { field ->
            Box(
                Modifier.fillMaxSize().clip(shape).background(if (focused) c.cardLit else c.inset)
                    .border(1.dp, if (focused) c.accent else c.cardBorder, shape).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { field() }
        },
    )
}

/** A clear outline while a control has the keyboard focus (Tab), so keyboard users see where they are. */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape): Modifier {
    val color = Theme.colors.accent
    return if (focused) this.border(2.dp, color, shape) else this
}
