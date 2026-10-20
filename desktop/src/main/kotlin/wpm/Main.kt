package wpm

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
import wpm.win32.SingleInstance
import wpm.win32.setAppUserModelId
import androidx.compose.ui.Alignment
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.util.logging.Level
import javax.swing.JOptionPane
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val startMinimized = "--minimized" in args

    // Only one copy may run (two would fight over the live wallpaper); a second
    // launch just brings the running copy's window to the front.
    // WPM_INSTANCE lets a development build run next to the installed app.
    val devInstance = System.getenv("WPM_INSTANCE")?.takeIf { it.isNotBlank() }
    val instance = SingleInstance(devInstance ?: APP_ID)
    if (instance.alreadyRunning) {
        instance.askExistingToShow()
        instance.close()
        return
    }

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
    setAppUserModelId(APP_ID)
    val app = AppState(paths, db, startMinimized)
    var showRequests by mutableIntStateOf(0)

    app.startAutomation()

    thread(name = "show-requests", isDaemon = true) {
        while (true) {
            if (instance.waitForShowRequest(1_000)) showRequests++
        }
    }

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
                onAction = { app.windowVisible = true; showRequests++ },
                menu = {
                    Item("Open", onClick = { app.windowVisible = true; showRequests++ })
                    Separator()
                    Item("Next wallpaper", onClick = app::nextWallpaper)
                    Item("Random wallpaper", onClick = app::randomWallpaper)
                    if (app.videoSupport) {
                        Separator()
                        Item("Pause / resume video", onClick = {
                            app.videoAction { if (app.wallpapers.videoPaused) app.wallpapers.resumeVideo() else app.wallpapers.pauseVideo() }
                        })
                        Item("Stop video", onClick = { app.videoAction { app.wallpapers.stopVideo() } })
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
                LaunchedEffect(showRequests) {
                    if (showRequests > 0) {
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
        app.close()
        db.close()
        instance.close()
        log.info("Exited")
    }
    exitProcess(0)
}
