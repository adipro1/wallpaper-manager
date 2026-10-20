package wpm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import wpm.ToastKind
import wpm.ToastMessage
import wpm.ui.Theme

/** The small uppercase label above a page title; set by the app for each page. */
val LocalEyebrow = compositionLocalOf<String?> { null }

/**
 * A sheet of paper: lit from the top left, a hairline edge, a soft shadow and the
 * top-right corner folded down.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(22.dp),
    fold: Dp = 18.dp,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Theme.colors
    val shape = FoldShape(fold, 6.dp)
    Column(
        modifier
            .shadow(if (c.dark) 6.dp else 3.dp, shape, ambientColor = c.shadow.copy(alpha = 0.5f), spotColor = c.shadow.copy(alpha = 0.35f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(c.cardLit, c.cardBg)))
            .border(1.dp, c.cardBorder, shape)
            .drawWithContent {
                drawContent()
                if (accent != null) drawRect(accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                drawFoldFlap(fold.toPx(), c.flap, c.shadow)
            }
            .padding(padding),
        content = content,
    )
}

/** A dark, folded frame for pictures and video previews. */
@Composable
fun PreviewFrame(modifier: Modifier = Modifier, fold: Dp = 22.dp, content: @Composable BoxScope.() -> Unit) {
    val c = Theme.colors
    val shape = FoldShape(fold, 4.dp)
    Box(
        modifier
            .shadow(10.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape)
            .background(c.previewBg)
            .drawWithContent {
                drawContent()
                drawFoldFlap(fold.toPx(), c.accent, Color.Black)
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * A page: eyebrow, big title, optional subtitle and actions, a fold line, then the content.
 * On narrow windows the actions wrap below the title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageScaffold(
    title: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Theme.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 860.dp
        val side = if (narrow) 24.dp else 40.dp
        Column(Modifier.fillMaxSize().padding(start = side, end = side, top = 30.dp)) {
            val heading = @Composable {
                Column {
                    LocalEyebrow.current?.let { Eyebrow(it) }
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = c.text)
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (narrow) {
                heading()
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f)) { heading() }
                    Spacer(Modifier.width(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).creaseLine(c.accent.copy(alpha = 0.45f)))
            Spacer(Modifier.height(20.dp))
            val body = if (scroll) {
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
            } else {
                Modifier.fillMaxSize().padding(bottom = 24.dp)
            }
            Column(body, verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
        }
    }
}

@Composable
fun Eyebrow(text: String, color: Color = Theme.colors.accent, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        FoldGlyph(color, size = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FoldGlyph(Theme.colors.accent, size = 7.dp)
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Theme.colors.text)
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Theme.colors.textMuted, modifier = modifier, style = MaterialTheme.typography.bodyMedium)
}

/** A short note in a tinted, folded strip (warnings and errors). */
@Composable
fun Notice(text: String, color: Color = Theme.colors.danger, modifier: Modifier = Modifier) {
    val shape = FoldShape(12.dp, 4.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.35f), shape)
            .drawWithContent { drawContent(); drawRect(color, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Theme.colors.text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A placeholder for an empty list: a folded sheet, a title and a hint. */
@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { OrigamiMark(52.dp) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Theme.colors.text)
        Spacer(Modifier.height(6.dp))
        Text(hint, color = Theme.colors.textMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(max = 460.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun ToastHost(message: ToastMessage?, onDismiss: (ToastMessage) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(if (message.kind == ToastKind.Error) 6_000 else 3_000)
            onDismiss(message)
        }
    }
    Box(modifier.fillMaxWidth().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            message != null,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it } +
                fadeIn(tween(160)) + scaleIn(initialScale = 0.92f),
            exit = slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(180)),
        ) {
            val msg = message ?: return@AnimatedVisibility
            val c = Theme.colors
            val (icon, tint) = when (msg.kind) {
                ToastKind.Success -> Icons.Outlined.CheckCircle to c.teal
                ToastKind.Error -> Icons.Outlined.ErrorOutline to c.danger
                ToastKind.Info -> Icons.Outlined.Info to c.indigo
            }
            val shape = FoldShape(14.dp, 4.dp)
            Row(
                Modifier.widthIn(max = 640.dp)
                    .shadow(16.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
                    .clip(shape)
                    .background(if (c.dark) c.cardLit else c.sidebarBg)
                    .drawWithContent {
                        drawContent()
                        drawRect(tint, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
                        drawFoldFlap(14.dp.toPx(), tint, Color.Black)
                    }
                    .padding(start = 20.dp, end = 26.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(msg.text, color = if (c.dark) c.text else c.sidebarText, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A numbered or labelled tag with a folded corner (e.g. "Active"). */
@Composable
fun Tag(text: String, color: Color = Theme.colors.accent, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = color, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = wpm.ui.DisplayFont,
        modifier = modifier.background(color.copy(alpha = 0.13f), foldedShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, androidx.compose.foundation.shape.CutCornerShape(50)))
}

/** Lays out [first] and [second] side by side, or stacked when the space is narrower than [breakpoint]. */
@Composable
fun Adaptive(
    breakpoint: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = 24.dp,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < breakpoint) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                first(Modifier)
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                first(Modifier)
                second(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
