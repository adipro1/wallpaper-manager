package wpm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wpm.APP_VERSION
import wpm.AppState
import wpm.Page
import wpm.ui.components.AddUrlDialog
import wpm.ui.components.LocalEyebrow
import wpm.ui.components.OrigamiMark
import wpm.ui.components.ToastHost
import wpm.ui.components.crease
import wpm.ui.components.drawFoldFlap
import wpm.ui.components.facetBackground
import wpm.ui.components.lerpColor
import wpm.ui.pages.CustomizePage
import wpm.ui.pages.DesktopPage
import wpm.ui.pages.HomePage
import wpm.ui.pages.LibraryPage
import wpm.ui.pages.DiscoverPage
import wpm.ui.pages.LockScreenPage
import wpm.ui.pages.PlaylistsPage
import wpm.ui.pages.SchedulePage
import wpm.ui.pages.SettingsPage

private fun Page.icon(): ImageVector = when (this) {
    Page.Home -> Icons.Outlined.Home
    Page.Desktop -> Icons.Outlined.DesktopWindows
    Page.LockScreen -> Icons.Outlined.Lock
    Page.Library -> Icons.Outlined.PhotoLibrary
    Page.Discover -> Icons.Outlined.TravelExplore
    Page.Playlists -> Icons.AutoMirrored.Outlined.PlaylistPlay
    Page.Schedule -> Icons.Outlined.CalendarMonth
    Page.Customize -> Icons.Outlined.AutoAwesome
    Page.Settings -> Icons.Outlined.Settings
}

private fun Page.eyebrow(): String = when (this) {
    Page.Home -> "Overview"
    Page.Desktop -> "Wallpaper"
    Page.LockScreen -> "Before sign-in"
    Page.Library -> "Collection"
    Page.Discover -> "From wallhaven.cc"
    Page.Playlists -> "Rotation"
    Page.Schedule -> "Through the day"
    Page.Customize -> "Shader studio"
    Page.Settings -> "Preferences"
}

private val NavItemHeight = 46.dp

@Composable
fun App(app: AppState) {
    AppTheme(app.darkTheme) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 1000.dp
            Row(Modifier.fillMaxSize().background(Theme.colors.windowBg)) {
                Sidebar(app, compact)
                Box(Modifier.weight(1f).fillMaxHeight().facetBackground(Theme.colors)) {
                    PageHost(app)
                    ToastHost(app.toast, app::dismissToast, Modifier.align(Alignment.BottomCenter))
                    app.addUrl?.let { url -> AddUrlDialog(app, url, onDismiss = { app.addUrl = null }) }
                }
            }
        }
    }
}

/** Pages unfold into view: they swing down from the top edge like a sheet being opened. */
@Composable
private fun PageHost(app: AppState) {
    AnimatedContent(
        app.page,
        transitionSpec = {
            if (app.reduceMotion) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120))
        },
        label = "page",
    ) { page ->
        val unfold by transition.animateFloat(
            transitionSpec = { if (app.reduceMotion) snap() else tween(380, easing = FastOutSlowInEasing) },
            label = "unfold",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0f)
                rotationX = -7f * (1f - unfold)
                translationY = 18.dp.toPx() * (1f - unfold)
                cameraDistance = 14f * density
            },
        ) {
            CompositionLocalProvider(LocalEyebrow provides page.eyebrow()) {
                when (page) {
                    Page.Home -> HomePage(app)
                    Page.Settings -> SettingsPage(app)
                    Page.Desktop -> DesktopPage(app)
                    Page.LockScreen -> LockScreenPage(app)
                    Page.Library -> LibraryPage(app)
                    Page.Discover -> DiscoverPage(app)
                    Page.Playlists -> PlaylistsPage(app)
                    Page.Schedule -> SchedulePage(app)
                    Page.Customize -> CustomizePage(app)
                }
            }
        }
    }
}

@Composable
private fun Sidebar(app: AppState, compact: Boolean) {
    val c = Theme.colors
    val width by animateDpAsState(if (compact) 76.dp else 236.dp, spring(stiffness = Spring.StiffnessMediumLow))
    Column(
        Modifier.width(width).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(c.sidebarBg, c.sidebarBgShade)))
            .drawBehind {
                // Two large, quiet folds across the bottom of the sidebar.
                val w = size.width
                val h = size.height
                drawPath(Path().apply { moveTo(0f, h * 0.58f); lineTo(w, h * 0.78f); lineTo(w, h); lineTo(0f, h); close() }, Color.White.copy(alpha = 0.025f))
                drawPath(Path().apply { moveTo(0f, h * 0.82f); lineTo(w, h * 0.70f); lineTo(w, h); lineTo(0f, h); close() }, Color.White.copy(alpha = 0.03f))
            }
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 30.dp, start = if (compact) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (compact) androidx.compose.foundation.layout.Arrangement.Center else androidx.compose.foundation.layout.Arrangement.Start,
        ) {
            OrigamiMark(30.dp)
            if (!compact) {
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Wallpaper", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 19.sp, color = c.sidebarText)
                    Text("MANAGER", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 3.sp,
                        lineHeight = 12.sp, color = c.accent)
                }
            }
        }

        Box(Modifier.fillMaxWidth()) {
            // The ribbon slides to the chosen page.
            val index = Page.entries.indexOf(app.page)
            val y by animateDpAsState(NavItemHeight * index, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
            NavRibbon(Modifier.offset(y = y + 3.dp).fillMaxWidth().height(NavItemHeight - 6.dp))
            Column {
                Page.entries.forEach { page -> NavItem(page, selected = app.page == page, compact = compact) { app.page = page } }
            }
        }
        Spacer(Modifier.weight(1f))
        if (!compact) {
            Text("Version $APP_VERSION", fontSize = 11.sp, color = c.sidebarTextMuted, letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 18.dp))
        }
    }
}

/** The highlight behind the chosen page: a folded vermilion ribbon. */
@Composable
private fun NavRibbon(modifier: Modifier) {
    val c = Theme.colors
    val shape = CutCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
    Box(
        modifier
            .shadow(10.dp, shape, ambientColor = c.accent, spotColor = c.accent)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(c.accent, lerpColor(c.accent, c.accentDeep, 0.6f))))
            .crease(0.12f)
            .drawWithContent {
                drawContent()
                drawFoldFlap(12.dp.toPx(), lerpColor(c.accent, Color.White, 0.35f), Color.Black)
                // a notch on the left like the tail of a ribbon
                val h = size.height
                drawPath(Path().apply { moveTo(0f, 0f); lineTo(5.dp.toPx(), h / 2); lineTo(0f, h); close() }, c.sidebarBg)
            },
    )
}

@Composable
private fun NavItem(page: Page, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fg by animateColorAsState(
        when {
            selected -> Color.White
            hovered -> c.sidebarText
            else -> c.sidebarTextMuted
        },
        tween(180),
    )
    val nudge by animateFloatAsState(if (hovered && !selected) 1f else 0f, spring(stiffness = Spring.StiffnessMedium))
    Row(
        Modifier.fillMaxWidth().height(NavItemHeight).padding(vertical = 3.dp)
            .clip(CutCornerShape(topEnd = 12.dp))
            .background(if (hovered && !selected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = if (compact) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) androidx.compose.foundation.layout.Arrangement.Center else androidx.compose.foundation.layout.Arrangement.Start,
    ) {
        Icon(page.icon(), page.label, tint = fg, modifier = Modifier.size(19.dp).offset(x = (3 * nudge).dp))
        if (!compact) {
            Spacer(Modifier.width(14.dp))
            Text(
                page.label, color = fg, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.offset(x = (3 * nudge).dp),
            )
        }
    }
}
