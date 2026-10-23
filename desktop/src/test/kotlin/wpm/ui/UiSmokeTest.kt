package wpm.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import wpm.AppPaths
import wpm.AppState
import wpm.Page
import wpm.Samples
import wpm.Shortcut
import wpm.data.Database
import wpm.services.FakeDesktop
import wpm.services.FakeLivePlayer
import wpm.services.FakeLockScreen
import wpm.services.FakeProbe
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds the real UI (with fake Windows APIs) and visits every page in both themes. */
@OptIn(ExperimentalTestApi::class)
class UiSmokeTest {
    private val dir = Files.createTempDirectory("wpm-ui")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val app = AppState(
        paths, db, startMinimized = false,
        desktopApi = FakeDesktop(),
        lockScreenApi = FakeLockScreen(Samples.image(dir.resolve("lock.png"))),
        live = FakeLivePlayer(),
        videoReader = null,
        systemProbe = FakeProbe(),
        online = wpm.media.FakeOnline(Files.readAllBytes(Samples.image(dir.resolve("online.png")))),
    )

    init {
        app.updateReduceMotion(true) // page animations cost many software-rendered frames in tests
        app.library.importFiles((1..3).map { Samples.image(dir.resolve("pic$it.png")) })
    }

    @AfterTest
    fun cleanup() {
        app.close()
        db.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun visitsEveryPageInBothThemes() = runComposeUiTest {
        setContent { App(app) }
        for (mode in listOf("dark", "light")) {
            app.updateAppearance(mode)
            for (page in Page.entries) {
                app.page = page
                waitForIdle()
                assertTrue(onAllNodesWithText(page.label).fetchSemanticsNodes().isNotEmpty(), "$page in $mode")
            }
        }
    }

    @Test
    fun wallpaperSettingsInTheDetails() = runComposeUiTest {
        app.shaders.create("Waves", "// @slider speed 0 2 1 Wave speed\n// @checkbox rain on Rain\n// @color tint #ff8800 Tint\n" +
            "void mainImage(out vec4 c, in vec2 p) { c = vec4(speed); }")
        app.page = Page.Library
        setContent { App(app) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Waves").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Waves").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("WALLPAPER SETTINGS").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Wave speed").assertExists()
        onNodeWithText("Rain").assertExists()
        onNodeWithText("Tint").assertExists()
    }

    @Test
    fun controlsTellScreenReadersTheirState() = runComposeUiTest {
        app.page = Page.Settings
        setContent { App(app) }
        waitForIdle()
        // A settings row is one switch: its label and on/off state are read together, and clicking the label toggles it.
        val row = onNode(hasText("Use global keyboard shortcuts") and isToggleable())
        row.performScrollTo().assertIsOff()
        row.performClick()
        waitUntil(timeoutMillis = 5_000) { app.shortcutsEnabled }
        row.assertIsOn()
        row.performClick()
        waitUntil(timeoutMillis = 5_000) { !app.shortcutsEnabled }
        // Segmented choices say which one is selected.
        onNode(hasText("Dark") and isSelectable()).performScrollTo().assertIsSelected()
        onNode(hasText("Light") and isSelectable()).assertIsNotSelected()
    }

    @Test
    fun accentColorCanBeChanged() = runComposeUiTest {
        app.page = Page.Settings
        setContent { App(app) }
        waitForIdle()
        onNode(hasContentDescription("Vermilion") and isSelectable()).performScrollTo().assertIsSelected()
        onNode(hasContentDescription("Teal") and isSelectable()).performClick()
        waitUntil(timeoutMillis = 5_000) { app.themeColor == 0x1F9AA3 }
        onNode(hasContentDescription("Teal") and isSelectable()).assertIsSelected()
        onNode(hasContentDescription("Vermilion") and isSelectable()).assertIsNotSelected()
        assertEquals("#1F9AA3", app.settings.string("theme_color", ""))
        onNode(hasContentDescription("Vermilion") and isSelectable()).performClick()
        waitUntil(timeoutMillis = 5_000) { app.themeColor == null }
    }

    @Test
    fun picturesCanBeMadeAlive() = runComposeUiTest {
        app.page = Page.Library
        setContent { App(app) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("pic2.png").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("pic2.png").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Make it alive…").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Make it alive…").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Rain on glass").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun librarySelectionAndDetails() = runComposeUiTest {
        app.page = Page.Library
        setContent { App(app) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("pic2.png").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("pic2.png").performClick()
        waitForIdle()
        onNodeWithText("Set as desktop wallpaper").assertExists()

        app.shortcut(Shortcut.SelectAll)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("3 selected").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Add to favorites").performClick()
        waitUntil(timeoutMillis = 5_000) { db.countMedia(favoritesOnly = true) == 3 }

        app.shortcut(Shortcut.Escape)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("3 selected").fetchSemanticsNodes().isEmpty() }
    }
}
