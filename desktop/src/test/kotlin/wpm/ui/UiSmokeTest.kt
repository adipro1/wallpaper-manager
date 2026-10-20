package wpm.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    )

    init {
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
