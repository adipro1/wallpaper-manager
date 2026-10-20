package wpm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import wpm.ToastKind
import wpm.ToastMessage
import wpm.ui.Theme

@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(20.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Theme.colors.cardBg,
        border = BorderStroke(1.dp, Theme.colors.cardBorder),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** A page: big title, optional subtitle and actions, then scrolling content. */
@Composable
fun PageScaffold(
    title: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = Theme.colors.text)
                if (subtitle != null) Text(subtitle, color = Theme.colors.textMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
        Spacer(Modifier.size(20.dp))
        val body = if (scroll) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()
        Column(body, verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Theme.colors.text)
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Theme.colors.textMuted, modifier = modifier)
}

@Composable
fun ToastHost(message: ToastMessage?, onDismiss: (ToastMessage) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(if (message.kind == ToastKind.Error) 6_000 else 3_000)
            onDismiss(message)
        }
    }
    Box(modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(message != null, enter = fadeIn(), exit = fadeOut()) {
            val msg = message ?: return@AnimatedVisibility
            val (icon, tint) = when (msg.kind) {
                ToastKind.Success -> Icons.Outlined.CheckCircle to Theme.colors.accent
                ToastKind.Error -> Icons.Outlined.ErrorOutline to Theme.colors.danger
                ToastKind.Info -> Icons.Outlined.Info to Theme.colors.textMuted
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Theme.colors.cardBg,
                border = BorderStroke(1.dp, Theme.colors.cardBorder),
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(msg.text, color = Theme.colors.text)
                }
            }
        }
    }
}

/** Placeholder body for a page that hasn't been ported yet. */
@Composable
fun ComingSoon(what: String) {
    Card(Modifier.fillMaxWidth()) {
        SectionTitle("Coming in a later step")
        Spacer(Modifier.size(6.dp))
        MutedText(what)
    }
}

@Composable
fun Dot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
}
