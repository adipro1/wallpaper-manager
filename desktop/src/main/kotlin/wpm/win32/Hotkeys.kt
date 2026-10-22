package wpm.win32

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import wpm.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val log = logger("hotkeys")

/** A key combination such as Ctrl+Alt+N. [key] is a Windows virtual-key code. */
data class Hotkey(val key: Int, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false, val win: Boolean = false) {
    val hasModifier: Boolean get() = ctrl || alt || shift || win

    override fun toString(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (win) add("Win")
        add(keyName(key) ?: "Key$key")
    }.joinToString("+")

    /** Modifier flags for RegisterHotKey (MOD_NOREPEAT: holding the keys down fires once). */
    internal val modifiers: Int
        get() = (if (alt) 0x1 else 0) or (if (ctrl) 0x2 else 0) or (if (shift) 0x4 else 0) or (if (win) 0x8 else 0) or 0x4000

    companion object {
        private val NAMED = buildMap {
            ('A'..'Z').forEach { put(it.toString(), it.code) }
            ('0'..'9').forEach { put(it.toString(), it.code) }
            (1..24).forEach { put("F$it", 0x6F + it) }
            putAll(mapOf(
                "Space" to 0x20, "PageUp" to 0x21, "PageDown" to 0x22, "End" to 0x23, "Home" to 0x24,
                "Left" to 0x25, "Up" to 0x26, "Right" to 0x27, "Down" to 0x28, "Insert" to 0x2D, "Delete" to 0x2E,
                "Pause" to 0x13,
            ))
        }
        private val NAMES = NAMED.entries.associate { (name, code) -> code to name }

        /** The name of a key that can be used in a shortcut, or null if it can't. */
        fun keyName(key: Int): String? = NAMES[key]

        /** "Ctrl+Alt+N" (any order, any case) -> Hotkey; null if it isn't a valid shortcut. */
        fun parse(text: String): Hotkey? {
            val parts = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var hotkey = Hotkey(0)
            for ((i, part) in parts.withIndex()) {
                val last = i == parts.lastIndex
                hotkey = when (part.lowercase()) {
                    "ctrl", "control" -> hotkey.copy(ctrl = true)
                    "alt" -> hotkey.copy(alt = true)
                    "shift" -> hotkey.copy(shift = true)
                    "win", "windows" -> hotkey.copy(win = true)
                    else -> {
                        if (!last) return null
                        val code = NAMED.entries.firstOrNull { it.key.equals(part, ignoreCase = true) }?.value ?: return null
                        hotkey.copy(key = code)
                    }
                }
            }
            return hotkey.takeIf { it.key != 0 && it.hasModifier }
        }
    }
}

/**
 * System-wide keyboard shortcuts (RegisterHotKey). They work while any app has the focus.
 * Registration happens on a thread with its own message loop, which then waits for WM_HOTKEY.
 */
class GlobalHotkeys(private val onHotkey: (String) -> Unit) : AutoCloseable {
    private val user32 = User32.INSTANCE
    private val ready = CountDownLatch(1)
    @Volatile private var threadId = 0
    @Volatile private var request: Pair<Map<String, Hotkey>, CompletableFuture<Map<String, String>>>? = null
    private var registered = mapOf<Int, String>() // hotkey id -> action; only touched by the loop thread

    private val loop = thread(name = "hotkeys", isDaemon = true) {
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        val msg = WinUser.MSG()
        user32.PeekMessage(msg, null, 0, 0, 0) // creates this thread's message queue
        ready.countDown()
        while (user32.GetMessage(msg, null, 0, 0) > 0) {
            when (msg.message) {
                WinUser.WM_HOTKEY -> registered[msg.wParam.toInt()]?.let { action ->
                    log.info("Shortcut: $action")
                    runCatching { onHotkey(action) }.onFailure { log.warning("Shortcut $action failed: $it") }
                }
                WM_REGISTER -> request?.let { (bindings, result) ->
                    request = null
                    result.complete(apply(bindings))
                }
            }
        }
        unregisterAll()
    }

    private fun unregisterAll() {
        registered.keys.forEach { user32.UnregisterHotKey(null, it) }
        registered = emptyMap()
    }

    /** Runs on the loop thread. Returns action -> why it couldn't be registered. */
    private fun apply(bindings: Map<String, Hotkey>): Map<String, String> {
        unregisterAll()
        val errors = mutableMapOf<String, String>()
        val ids = mutableMapOf<Int, String>()
        bindings.entries.forEachIndexed { i, (action, hotkey) ->
            val id = i + 1
            if (bindings.entries.take(i).any { it.value == hotkey }) {
                errors[action] = "$hotkey is used twice"
            } else if (user32.RegisterHotKey(null, id, hotkey.modifiers, hotkey.key)) {
                ids[id] = action
            } else {
                errors[action] = "$hotkey is already used by another app"
            }
        }
        registered = ids
        if (ids.isNotEmpty()) log.info("Registered shortcuts: ${ids.values.joinToString { "$it=${bindings[it]}" }}")
        errors.forEach { (action, error) -> log.warning("Shortcut for $action: $error") }
        return errors
    }

    /** Replaces all shortcuts (empty map: none). Returns action -> problem for those that failed. */
    fun register(bindings: Map<String, Hotkey>): Map<String, String> {
        ready.await(5, TimeUnit.SECONDS)
        val result = CompletableFuture<Map<String, String>>()
        request = bindings to result
        user32.PostThreadMessage(threadId, WM_REGISTER, WinDef.WPARAM(0), WinDef.LPARAM(0))
        return result.get(5, TimeUnit.SECONDS)
    }

    override fun close() {
        if (threadId != 0) user32.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        loop.join(1_000)
    }

    private companion object {
        const val WM_REGISTER = WinUser.WM_USER + 1
    }
}
