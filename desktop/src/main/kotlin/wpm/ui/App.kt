package wpm.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wpm.APP_VERSION
import wpm.AppState
import wpm.Page
import wpm.ui.components.ComingSoon
import wpm.ui.components.PageScaffold
import wpm.ui.components.ToastHost
import wpm.ui.pages.DesktopPage
import wpm.ui.pages.HomePage
import wpm.ui.pages.LibraryPage
import wpm.ui.pages.LockScreenPage
import wpm.ui.pages.SettingsPage

private fun Page.icon(): ImageVector = when (this) {
    Page.Home -> Icons.Outlined.Home
    Page.Desktop -> Icons.Outlined.DesktopWindows
    Page.LockScreen -> Icons.Outlined.Lock
    Page.Library -> Icons.Outlined.PhotoLibrary
    Page.Playlists -> Icons.AutoMirrored.Outlined.PlaylistPlay
    Page.Schedule -> Icons.Outlined.CalendarMonth
    Page.Settings -> Icons.Outlined.Settings
}

@Composable
fun App(app: AppState) {
    AppTheme(app.darkTheme) {
        Row(Modifier.fillMaxSize().background(Theme.colors.windowBg)) {
            Sidebar(app)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (app.page) {
                    Page.Home -> HomePage(app)
                    Page.Settings -> SettingsPage(app)
                    Page.Desktop -> DesktopPage(app)
                    Page.LockScreen -> LockScreenPage(app)
                    Page.Library -> LibraryPage(app)
                    Page.Playlists -> PageScaffold("Playlists") { ComingSoon("Playlists (step 4).") }
                    Page.Schedule -> PageScaffold("Schedule") { ComingSoon("Daily schedule (step 4).") }
                }
                ToastHost(app.toast, app::dismissToast, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun Sidebar(app: AppState) {
    Column(Modifier.width(220.dp).fillMaxHeight().background(Theme.colors.sidebarBg).padding(12.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 12.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource("icon.png"), null, Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Wallpaper\nManager", fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp, color = Theme.colors.text)
        }
        Page.entries.forEach { page -> NavItem(page, selected = app.page == page) { app.page = page } }
        Spacer(Modifier.weight(1f))
        Text("Version $APP_VERSION", fontSize = 11.sp, color = Theme.colors.textSubtle, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun NavItem(page: Page, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> Theme.colors.navSelected
        hovered -> Theme.colors.navHover
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(16.dp).background(if (selected) Theme.colors.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(9.dp))
        Icon(page.icon(), null, tint = Theme.colors.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(page.label, color = Theme.colors.text)
    }
}
