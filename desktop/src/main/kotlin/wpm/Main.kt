package wpm

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import wpm.data.Database
import wpm.data.DatabaseTooNewException
import wpm.ui.App
import wpm.ui.handleShortcut
import wpm.ui.installFileDrop
import wpm.win32.CommandPipe
import wpm.win32.SingleInstance
import wpm.win32.parentConsole
import wpm.win32.setAppUserModelId
import androidx.compose.ui.Alignment
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.util.logging.Level
import javax.swing.JOptionPane
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Relative paths after --set mean the caller's folder, which the running copy doesn't know. */
private fun absolutePaths(args: List<String>): List<String> = args.mapIndexed { i, arg ->
    if (i > 0 && args[i - 1].equals("--set", ignoreCase = true)) {
        runCatching { Path.of(arg.trim('"')) }.getOrNull()?.takeIf { !it.isAbsolute && it.toFile().exists() }?.toAbsolutePath()?.toString() ?: arg
    } else {
        arg
    }
}

fun main(rawArgs: Array<String>) {
    val args = absolutePaths(rawArgs.toList())
    val commandLine = parseCommandLine(args)
    if (commandLine.help || commandLine.error != null) {
        val console = parentConsole()
        commandLine.error?.let { console?.println("Error: $it\n") }
        console?.println(COMMAND_LINE_HELP)
        if (console == null && commandLine.error != null) {
            JOptionPane.showMessageDialog(null, "${commandLine.error}\n\n$COMMAND_LINE_HELP", APP_NAME, JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(if (commandLine.error != null) 2 else 0)
    }
    val command = commandLine.command

    // Only one copy may run (two would fight over the live wallpaper); a second
    // launch brings the running copy's window to the front, or hands it its command.
    // WPM_INSTANCE lets a development build run next to the installed app.
    val devInstance = System.getenv("WPM_INSTANCE")?.takeIf { it.isNotBlank() }
    val instanceName = devInstance ?: APP_ID
    val instance = SingleInstance(instanceName)
    if (instance.alreadyRunning) {
        if (command == null || commandLine.selfTest) {
            instance.askExistingToShow()
            instance.close()
            return
        }
        instance.close()
        val reply = runCatching { CommandPipe.send(instanceName, args) }
            .getOrElse { wpm.win32.CommandReply(false, it.message ?: "The running copy didn't answer") }
        parentConsole()?.println(reply.message)
        exitProcess(if (reply.ok) 0 else 1)
    }
    val startMinimized = commandLine.minimized || (command != null && command != AppCommand.Show)

    val paths = AppPaths.default()
    val logFile = setupLogging(paths.logDir)
    val log = logger("main")
    log.info("Starting $APP_NAME $APP_VERSION (data: ${paths.dataDir})")

    val db = try {
        Database(paths.dbPath)
    } catch (e: DatabaseTooNewException) {
        log.severe(e.message)
        JOptionPane.showMessageDialog(null, "${e.message}\n\nPlease install the latest version.", APP_NAME, JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    log.info("Native helpers in ${wpm.mpv.NativeBin.dir.toAbsolutePath()}: libmpv=${wpm.mpv.Mpv.available}, " +
        "yt-dlp=${wpm.mpv.NativeBin.dir.resolve("yt-dlp.exe").toFile().exists()}")
    if (commandLine.selfTest) {
        val passed = runSelfTest(paths)
        instance.close()
        exitProcess(if (passed) 0 else 1)
    }
    setAppUserModelId(APP_ID)
    val app = AppState(paths, db, startMinimized)

    app.startAutomation()
    // A command given to the first launch runs once the saved wallpaper is back.
    if (command != null) app.scope.launch { app.ready.await(); app.runCommand(command) }

    thread(name = "show-requests", isDaemon = true) {
        while (true) {
            if (instance.waitForShowRequest(1_000)) app.showWindow()
        }
    }
    val commands = CommandPipe(instanceName, app::handleCommandLine)

    try {
        application {
            fun exit() {
                log.info("Exiting")
                app.shutdown()
                exitApplication()
            }

            val trayState = rememberTrayState()
            app.notify = { trayState.sendNotification(androidx.compose.ui.window.Notification(APP_NAME, it)) }
            Tray(
                icon = painterResource("icon.png"),
                state = trayState,
                tooltip = APP_NAME,
                onAction = app::showWindow,
                menu = {
                    Item("Open", onClick = app::showWindow)
                    Separator()
                    Item("Next wallpaper", onClick = app::nextWallpaper)
                    Item("Random wallpaper", onClick = app::randomWallpaper)
                    if (app.videoSupport) {
                        Separator()
                        Item("Pause / resume", onClick = { app.runCommand(AppCommand.TogglePause) })
                        Item("Stop live wallpaper", onClick = { app.runCommand(AppCommand.Stop) })
                    }
                    Separator()
                    Item("Exit", onClick = ::exit)
                },
            )

            // 1240x800, shrunk to fit small screens (the work area excludes the taskbar).
            val workArea = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val windowState = rememberWindowState(
                width = minOf(1240, workArea.width - 40).dp,
                height = minOf(800, workArea.height - 40).dp,
                position = WindowPosition(Alignment.Center),
            )
            Window(
                onCloseRequest = {
                    if (app.settings.boolean("close_to_tray", true)) {
                        app.windowVisible = false
                        if (!app.settings.boolean("tray_hint_shown", false)) {
                            trayState.sendNotification(
                                androidx.compose.ui.window.Notification(APP_NAME, "Still running here. Right-click the icon to exit."),
                            )
                            app.settings.set("tray_hint_shown", true)
                        }
                    } else {
                        exit()
                    }
                },
                visible = app.windowVisible,
                state = windowState,
                title = if (devInstance != null) "$APP_NAME ($devInstance)" else APP_NAME,
                icon = painterResource("icon.png"),
                onKeyEvent = { handleShortcut(app, it) },
            ) {
                LaunchedEffect(Unit) {
                    window.minimumSize = Dimension(minOf(980, workArea.width), minOf(620, workArea.height))
                    app.window = window
                    installFileDrop(window, app::import) { app.addUrl = it }
                }
                LaunchedEffect(app.showRequests) {
                    if (app.showRequests > 0) {
                        app.windowVisible = true
                        windowState.isMinimized = false
                        window.toFront()
                        window.requestFocus()
                    }
                }
                App(app)
            }
        }
    } catch (e: Throwable) {
        log.log(Level.SEVERE, "Fatal error - see $logFile", e)
        throw e
    } finally {
        commands.close()
        app.close()
        db.close()
        instance.close()
        log.info("Exited")
    }
    exitProcess(0)
}
