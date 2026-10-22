package wpm

import kotlinx.coroutines.runBlocking
import wpm.data.Database
import wpm.services.FakeDesktop
import wpm.services.FakeLivePlayer
import wpm.services.FakeLockScreen
import wpm.services.FakeProbe
import wpm.win32.CommandPipe
import wpm.win32.CommandReply
import wpm.win32.GlobalHotkeys
import wpm.win32.Hotkey
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandLineTest {
    @Test
    fun parsesCommandsAndOptions() {
        assertEquals(CommandLine(), parseCommandLine(emptyList()))
        assertEquals(AppCommand.Next, parseCommandLine(listOf("--next")).command)
        assertEquals(AppCommand.TogglePause, parseCommandLine(listOf("--TOGGLE-PAUSE")).command)
        val set = parseCommandLine(listOf("--minimized", "--set", "C:\\Pictures\\a b.jpg"))
        assertEquals(AppCommand.Set("C:\\Pictures\\a b.jpg"), set.command)
        assertTrue(set.minimized)
        assertEquals(AppCommand.Playlist("Evenings"), parseCommandLine(listOf("--playlist", "Evenings")).command)
        assertTrue(parseCommandLine(listOf("--self-test")).selfTest)
        assertTrue(parseCommandLine(listOf("/?")).help)
    }

    @Test
    fun reportsMistakes() {
        assertNotNull(parseCommandLine(listOf("--set")).error)
        assertNotNull(parseCommandLine(listOf("--playlist", "--next")).error)
        assertNotNull(parseCommandLine(listOf("--next", "--random")).error)
        assertNotNull(parseCommandLine(listOf("--frobnicate")).error)
    }

    @Test
    fun hotkeysParseAndFormat() {
        assertEquals(Hotkey('N'.code, ctrl = true, alt = true), Hotkey.parse("Ctrl+Alt+N"))
        assertEquals("Ctrl+Alt+N", Hotkey.parse(" alt + control + n ").toString())
        assertEquals("Shift+Win+F12", Hotkey.parse("win+shift+f12").toString())
        assertEquals("Ctrl+Right", Hotkey.parse("Ctrl+Right").toString())
        assertNull(Hotkey.parse("N")) // needs a modifier
        assertNull(Hotkey.parse("Ctrl+Alt"))
        assertNull(Hotkey.parse("Ctrl+N+Alt"))
        assertNull(Hotkey.parse("Ctrl+Banana"))
        assertNull(Hotkey.parse(""))
        SHORTCUT_ACTIONS.filter { it.defaultKeys.isNotEmpty() }.forEach { assertNotNull(Hotkey.parse(it.defaultKeys), it.id) }
    }

    @Test
    fun commandPipeCarriesArgumentsAndReplies() {
        val name = "WpmTest${System.nanoTime()}"
        CommandPipe(name) { args -> CommandReply(args.size == 2, args.joinToString("|")) }.use {
            assertEquals(CommandReply(true, "--set|C:\\ä ö.jpg"), CommandPipe.send(name, listOf("--set", "C:\\ä ö.jpg")))
            assertEquals(CommandReply(false, "--next"), CommandPipe.send(name, listOf("--next")))
        }
    }

    @Test
    fun hotkeysRegisterAndReportConflicts() {
        // RegisterHotKey needs an interactive desktop.
        if (System.getenv("WPM_DESKTOP_TEST") != "1") return
        val rare = Hotkey.parse("Ctrl+Alt+Shift+F23")!!
        GlobalHotkeys {}.use { first ->
            assertEquals(emptyMap(), first.register(mapOf("next" to rare)))
            GlobalHotkeys {}.use { second ->
                assertTrue(second.register(mapOf("random" to rare)).containsKey("random"))
            }
            assertTrue(first.register(mapOf("a" to rare, "b" to rare)).containsKey("b"))
            assertEquals(emptyMap(), first.register(emptyMap()))
        }
    }
}

class AppCommandsTest {
    private val dir = Files.createTempDirectory("wpm-commands")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val desktop = FakeDesktop()
    private val live = FakeLivePlayer()
    private val app = AppState(
        paths, db, startMinimized = true,
        desktopApi = desktop,
        lockScreenApi = FakeLockScreen(Samples.image(dir.resolve("lock.png"))),
        live = live,
        videoReader = null,
        systemProbe = FakeProbe(),
    )

    init {
        app.library.importFiles(listOf("sunrise.png", "sunset.png", "night sky.png").map { Samples.image(dir.resolve(it)) })
    }

    @AfterTest
    fun cleanup() {
        app.close()
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun perform(command: AppCommand) = runBlocking { app.perform(command) }

    @Test
    fun nextAndRandomChangeTheWallpaper() {
        perform(AppCommand.Next)
        val first = app.wallpapers.state.mediaId
        assertNotNull(first)
        perform(AppCommand.Next)
        assertTrue(app.wallpapers.state.mediaId != first)
        perform(AppCommand.Random)
        assertNotNull(app.wallpapers.state.mediaId)
    }

    @Test
    fun nextAdvancesTheRunningPlaylist() {
        val items = db.listMedia(sort = "name")
        val playlist = db.createPlaylist("Evenings")
        db.addToPlaylist(playlist, items.take(2).map { it.id })
        assertEquals("Playing “Evenings”: ${items[0].filename}", perform(AppCommand.Playlist("evenings")))
        assertEquals("Showing ${items[1].filename}", perform(AppCommand.Next))
        assertEquals(playlist, app.playlists.state?.id)
        perform(AppCommand.StopPlaylist)
        assertNull(app.playlists.state)
        assertFailsWith<IllegalStateException> { perform(AppCommand.StopPlaylist) }
        assertFailsWith<IllegalArgumentException> { perform(AppCommand.Playlist("Nope")) }
    }

    @Test
    fun setFindsLibraryItemsByName() {
        assertEquals("Showing sunset.png", perform(AppCommand.Set("SUNSET")))
        assertEquals("Showing night sky.png", perform(AppCommand.Set("night")))
        assertFailsWith<IllegalArgumentException> { perform(AppCommand.Set("sun")) } // sunrise and sunset
        assertFailsWith<IllegalArgumentException> { perform(AppCommand.Set("mountains")) }
    }

    @Test
    fun setAddsNewFilesToTheLibrary() {
        val file = Samples.image(dir.resolve("new folder/beach.png"))
        assertEquals("Showing beach.png", perform(AppCommand.Set(file.toString())))
        assertEquals(4, db.countMedia())
        assertEquals(file.toString(), desktop.current)
        perform(AppCommand.Set(file.toString())) // already there: not added twice
        assertEquals(4, db.countMedia())
        val shader = dir.resolve("waves.glsl")
        Files.writeString(shader, "void mainImage(out vec4 c, in vec2 p) { c = vec4(1.0); }")
        perform(AppCommand.Set(shader.toString()))
        assertTrue(live.active)
        assertFailsWith<IllegalArgumentException> { perform(AppCommand.Set(dir.resolve("new folder").toString())) }
    }

    @Test
    fun liveControlsNeedALiveWallpaper() {
        assertFailsWith<IllegalStateException> { perform(AppCommand.TogglePause) }
        val shader = db.listMedia().first().let { app.shaders.create("Waves", "void mainImage(out vec4 c, in vec2 p) { c = vec4(1.0); }") }
        perform(AppCommand.Set("Waves"))
        assertTrue(live.active && !live.paused)
        perform(AppCommand.TogglePause)
        assertTrue(live.paused)
        perform(AppCommand.TogglePause)
        assertFalse(live.paused)
        perform(AppCommand.Stop)
        assertFalse(live.active)
        assertTrue(shader > 0)
    }

    @Test
    fun commandLineFromASecondLaunch() {
        app.ready.complete(Unit)
        assertEquals(CommandReply(true, "Showing sunrise.png"), app.handleCommandLine(listOf("--set", "sunrise")))
        assertFalse(app.handleCommandLine(listOf("--set")).ok)
        assertFalse(app.handleCommandLine(listOf("--pause")).ok)
    }
}
