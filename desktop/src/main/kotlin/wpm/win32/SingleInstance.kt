package wpm.win32

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT

/**
 * Makes sure only one copy of the app runs (two would fight over the live wallpaper).
 *
 * The first copy owns a named mutex. A second copy notices the mutex already exists,
 * signals a named event to ask the first copy to show its window, and exits.
 * Uses the same names as version 1.x, so the old and new apps can't run together either.
 */
class SingleInstance(name: String) : AutoCloseable {
    private val k32 = Kernel32.INSTANCE
    private var mutex: WinNT.HANDLE? = k32.CreateMutex(null, false, "Local\\$name.Instance")
    val alreadyRunning: Boolean = k32.GetLastError() == WinError.ERROR_ALREADY_EXISTS
    private var showEvent: WinNT.HANDLE? = k32.CreateEvent(null, false, false, "Local\\$name.Show") // auto-reset

    fun askExistingToShow() {
        k32.SetEvent(showEvent)
    }

    /** Blocks until another copy asks this one to show itself (or [timeoutMs] passes). */
    fun waitForShowRequest(timeoutMs: Int): Boolean =
        showEvent != null && k32.WaitForSingleObject(showEvent, timeoutMs) == WinBase.WAIT_OBJECT_0

    override fun close() {
        listOfNotNull(showEvent, mutex).forEach { k32.CloseHandle(it) }
        showEvent = null
        mutex = null
    }
}
