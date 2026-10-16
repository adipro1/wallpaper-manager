"""A native window that sits *behind the desktop icons*, for live wallpapers.

How it works (the technique used by most live wallpaper apps):

The desktop is drawn by Explorer's "Progman" window. Sending it the
undocumented message 0x052C makes Explorer split the desktop into two layers:
a "WorkerW" window that paints the wallpaper, and the window that holds the
icons ("SHELLDLL_DefView") on top of it. A child window placed in that WorkerW
is therefore drawn above the wallpaper but below the icons - exactly where a
video wallpaper should be.

* Windows 10 / Windows 11 up to 23H2: the wallpaper WorkerW is a top-level
  window directly behind the top-level window that contains SHELLDLL_DefView.
* Windows 11 24H2 and later: the WorkerW and SHELLDLL_DefView are both children
  of Progman. We become a child of Progman too and put ourselves between them.

The window belongs to a dedicated thread that does nothing but run a message
loop. Windows links the input of a child window's thread with its parent's
(here: Explorer), so the thread must never block, or the desktop would freeze.
"""

import ctypes
import logging
import os
import threading
from ctypes import wintypes

log = logging.getLogger(__name__)

user32 = ctypes.WinDLL("user32", use_last_error=True)
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

LRESULT = ctypes.c_ssize_t
WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)


class WNDCLASSEXW(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.UINT), ("style", wintypes.UINT), ("lpfnWndProc", WNDPROC),
        ("cbClsExtra", ctypes.c_int), ("cbWndExtra", ctypes.c_int), ("hInstance", wintypes.HINSTANCE),
        ("hIcon", wintypes.HICON), ("hCursor", wintypes.HANDLE), ("hbrBackground", wintypes.HBRUSH),
        ("lpszMenuName", wintypes.LPCWSTR), ("lpszClassName", wintypes.LPCWSTR), ("hIconSm", wintypes.HICON),
    ]


def _fn(func, restype, *argtypes):
    """Declare a Win32 function's signature (needed so 64-bit handles aren't truncated)."""
    func.restype, func.argtypes = restype, argtypes
    return func


FindWindowW = _fn(user32.FindWindowW, wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR)
FindWindowExW = _fn(user32.FindWindowExW, wintypes.HWND,
                    wintypes.HWND, wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR)
SendMessageTimeoutW = _fn(user32.SendMessageTimeoutW, LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM,
                          wintypes.LPARAM, wintypes.UINT, wintypes.UINT, ctypes.POINTER(ctypes.c_size_t))
EnumWindows = _fn(user32.EnumWindows, wintypes.BOOL, WNDENUMPROC, wintypes.LPARAM)
RegisterClassExW = _fn(user32.RegisterClassExW, wintypes.ATOM, ctypes.POINTER(WNDCLASSEXW))
CreateWindowExW = _fn(user32.CreateWindowExW, wintypes.HWND, wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR,
                      wintypes.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                      wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID)
DefWindowProcW = _fn(user32.DefWindowProcW, LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
DestroyWindow = _fn(user32.DestroyWindow, wintypes.BOOL, wintypes.HWND)
PostMessageW = _fn(user32.PostMessageW, wintypes.BOOL, wintypes.HWND, wintypes.UINT, wintypes.WPARAM,
                   wintypes.LPARAM)
GetMessageW = _fn(user32.GetMessageW, wintypes.BOOL, ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT,
                  wintypes.UINT)
TranslateMessage = _fn(user32.TranslateMessage, wintypes.BOOL, ctypes.POINTER(wintypes.MSG))
DispatchMessageW = _fn(user32.DispatchMessageW, LRESULT, ctypes.POINTER(wintypes.MSG))
PostQuitMessage = _fn(user32.PostQuitMessage, None, ctypes.c_int)
IsWindow = _fn(user32.IsWindow, wintypes.BOOL, wintypes.HWND)
GetParent = _fn(user32.GetParent, wintypes.HWND, wintypes.HWND)
IsWindowVisible = _fn(user32.IsWindowVisible, wintypes.BOOL, wintypes.HWND)
GetWindow = _fn(user32.GetWindow, wintypes.HWND, wintypes.HWND, wintypes.UINT)
GetClassNameW = _fn(user32.GetClassNameW, ctypes.c_int, wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
GetWindowThreadProcessId = _fn(user32.GetWindowThreadProcessId, wintypes.DWORD, wintypes.HWND,
                               ctypes.POINTER(wintypes.DWORD))
SetWindowPos = _fn(user32.SetWindowPos, wintypes.BOOL, wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int,
                   ctypes.c_int, ctypes.c_int, wintypes.UINT)
MapWindowPoints = _fn(user32.MapWindowPoints, ctypes.c_int, wintypes.HWND, wintypes.HWND,
                      ctypes.POINTER(wintypes.POINT), wintypes.UINT)
GetSystemMetrics = _fn(user32.GetSystemMetrics, ctypes.c_int, ctypes.c_int)
SetLayeredWindowAttributes = _fn(user32.SetLayeredWindowAttributes, wintypes.BOOL, wintypes.HWND,
                                 wintypes.DWORD, wintypes.BYTE, wintypes.DWORD)
GetStockObject = _fn(gdi32.GetStockObject, wintypes.HGDIOBJ, ctypes.c_int)
GetModuleHandleW = _fn(kernel32.GetModuleHandleW, wintypes.HMODULE, wintypes.LPCWSTR)

WM_DESTROY, WM_CLOSE = 0x0002, 0x0010
WS_CHILD, WS_VISIBLE, WS_CLIPSIBLINGS, WS_CLIPCHILDREN = 0x40000000, 0x10000000, 0x04000000, 0x02000000
WS_EX_LAYERED, WS_EX_NOACTIVATE = 0x00080000, 0x08000000
SWP_NOSIZE, SWP_NOMOVE, SWP_NOZORDER, SWP_NOACTIVATE = 0x0001, 0x0002, 0x0004, 0x0010
LWA_ALPHA = 0x2
HWND_TOP = 0
SMTO_NORMAL = 0x0
SM_CXSCREEN, SM_CYSCREEN = 0, 1
BLACK_BRUSH = 4
SPAWN_WORKERW = 0x052C  # undocumented Progman message, see module docstring

CLASS_NAME = "WallpaperManagerLiveHost"
GW_HWNDNEXT = 2

HELP_TEXT = ("Live video wallpapers could not be attached to the desktop on this PC. "
             "Please save a diagnostics file (Settings → Diagnostics) and send it to the developer.")


class DesktopLayerError(RuntimeError):
    """The desktop window layout is not what we expect (e.g. a new Windows version)."""

    def __init__(self, detail: str):
        super().__init__(f"{detail}\n\n{HELP_TEXT}")


def find_wallpaper_layer() -> tuple[int, int | None]:
    """Return (parent, insert_after): the window to attach to and, on 24H2+, the icon
    window we must sit directly below (None for the classic layout)."""
    progman = FindWindowW("Progman", None)
    if not progman:
        raise DesktopLayerError("Explorer's desktop window (Progman) was not found. Is Explorer running?")

    result = ctypes.c_size_t()
    SendMessageTimeoutW(progman, SPAWN_WORKERW, 0xD, 0x1, SMTO_NORMAL, 1000, ctypes.byref(result))
    SendMessageTimeoutW(progman, SPAWN_WORKERW, 0, 0, SMTO_NORMAL, 1000, ctypes.byref(result))

    # Windows 11 24H2+: WorkerW is a child of Progman, next to the icons.
    if FindWindowExW(progman, None, "WorkerW", None):
        defview = FindWindowExW(progman, None, "SHELLDLL_DefView", None)
        if defview:
            log.info("Desktop layout: 24H2 (WorkerW and icons inside Progman)")
            return progman, defview

    # Classic layout: the WorkerW that follows the top-level window holding the icons.
    found: list[int] = []

    def on_window(hwnd, _lparam):
        if FindWindowExW(hwnd, None, "SHELLDLL_DefView", None):
            workerw = FindWindowExW(None, hwnd, "WorkerW", None)
            if workerw:
                found.append(workerw)
        return True

    EnumWindows(WNDENUMPROC(on_window), 0)
    if not found:
        raise DesktopLayerError("The desktop wallpaper layer (WorkerW) was not found.")
    log.info("Desktop layout: classic (top-level WorkerW behind the icons)")
    return found[0], None


def class_name(hwnd: int) -> str:
    buf = ctypes.create_unicode_buffer(128)
    GetClassNameW(hwnd, buf, len(buf))
    return buf.value


def _children(parent: int) -> list[int]:
    result, child = [], FindWindowExW(parent, None, None, None)
    while child:
        result.append(child)
        child = FindWindowExW(parent, child, None, None)
    return result


def describe_desktop() -> list[str]:
    """Read-only description of Explorer's desktop windows, for diagnostics."""
    lines = []

    def describe(hwnd: int, indent: str) -> None:
        lines.append(f"{indent}{class_name(hwnd)}  hwnd={hwnd:#x}  visible={bool(IsWindowVisible(hwnd))}  "
                     f"process={window_process_name(hwnd)}")

    def on_window(hwnd, _lparam):
        if class_name(hwnd) in ("Progman", "WorkerW"):
            children = _children(hwnd)
            if children or IsWindowVisible(hwnd):  # skip Explorer's many empty helper windows
                describe(hwnd, "")
                for child in children:
                    describe(child, "    ")
        return True

    EnumWindows(WNDENUMPROC(on_window), 0)
    return lines


def window_pid(hwnd: int) -> int:
    pid = wintypes.DWORD()
    GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return pid.value


OpenProcess = _fn(kernel32.OpenProcess, wintypes.HANDLE, wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
QueryFullProcessImageNameW = _fn(kernel32.QueryFullProcessImageNameW, wintypes.BOOL, wintypes.HANDLE,
                                 wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD))
CloseHandle = _fn(kernel32.CloseHandle, wintypes.BOOL, wintypes.HANDLE)
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def window_process_name(hwnd: int) -> str:
    """Executable name (e.g. 'Lively.exe') of the process that owns a window."""
    handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, window_pid(hwnd))
    if not handle:
        return "another app"
    try:
        buf = ctypes.create_unicode_buffer(512)
        size = wintypes.DWORD(len(buf))
        if not QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size)):
            return "another app"
        return os.path.basename(buf.value)
    finally:
        CloseHandle(handle)


def primary_screen_rect(parent: int) -> tuple[int, int, int, int]:
    """Primary monitor as (x, y, width, height) in the parent's client coordinates.
    (The wallpaper layer spans all monitors; its origin is the virtual screen's corner.)"""
    width, height = GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)
    points = (wintypes.POINT * 1)(wintypes.POINT(0, 0))
    MapWindowPoints(None, parent, points, 1)
    return points[0].x, points[0].y, width, height


# --- screen on/off --------------------------------------------------------------------
# Windows reports the console display state (off / on / dimmed) to windows that register
# for GUID_CONSOLE_DISPLAY_STATE. A hidden top-level window in the host thread receives it.

class GUID(ctypes.Structure):
    _fields_ = [("Data1", wintypes.DWORD), ("Data2", wintypes.WORD), ("Data3", wintypes.WORD),
                ("Data4", ctypes.c_ubyte * 8)]


class POWERBROADCAST_SETTING(ctypes.Structure):
    _fields_ = [("PowerSetting", GUID), ("DataLength", wintypes.DWORD), ("Data", wintypes.DWORD)]


GUID_CONSOLE_DISPLAY_STATE = GUID(0x6FE69556, 0x704A, 0x47A0,
                                  (ctypes.c_ubyte * 8)(0x8F, 0x24, 0xC2, 0x8D, 0x93, 0x6F, 0xDA, 0x47))
RegisterPowerSettingNotification = _fn(user32.RegisterPowerSettingNotification, wintypes.HANDLE,
                                       wintypes.HANDLE, ctypes.POINTER(GUID), wintypes.DWORD)
UnregisterPowerSettingNotification = _fn(user32.UnregisterPowerSettingNotification, wintypes.BOOL,
                                         wintypes.HANDLE)
WM_POWERBROADCAST, PBT_POWERSETTINGCHANGE = 0x0218, 0x8013
WS_POPUP = 0x80000000

_display = {"on": True}  # updated by the host thread, read by the app


def display_is_on() -> bool:
    return _display["on"]


@WNDPROC
def _window_proc(hwnd, msg, wparam, lparam):
    if msg == WM_POWERBROADCAST and wparam == PBT_POWERSETTINGCHANGE and lparam:
        setting = ctypes.cast(lparam, ctypes.POINTER(POWERBROADCAST_SETTING)).contents
        if bytes(setting.PowerSetting) == bytes(GUID_CONSOLE_DISPLAY_STATE):
            _display["on"] = setting.Data != 0  # 0 = off, 1 = on, 2 = dimmed (still visible)
            log.info("Screen is %s", "on" if _display["on"] else "off")
        return 1
    if msg == WM_CLOSE:
        DestroyWindow(hwnd)
        return 0
    if msg == WM_DESTROY:
        if hwnd in _host_windows:  # the video window ends the thread; the helper window doesn't
            PostQuitMessage(0)
        return 0
    return DefWindowProcW(hwnd, msg, wparam, lparam)


_host_windows: set[int] = set()


_class_registered = False


def _register_class(hinstance) -> None:
    global _class_registered
    if _class_registered:
        return
    wc = WNDCLASSEXW()
    wc.cbSize = ctypes.sizeof(WNDCLASSEXW)
    wc.lpfnWndProc = _window_proc
    wc.hInstance = hinstance
    wc.hbrBackground = GetStockObject(BLACK_BRUSH)
    wc.lpszClassName = CLASS_NAME
    if not RegisterClassExW(ctypes.byref(wc)):
        raise ctypes.WinError(ctypes.get_last_error())
    _class_registered = True


class DesktopHost:
    """Owns the behind-the-icons window and the thread that runs its message loop."""

    def __init__(self):
        self.hwnd: int | None = None
        self.parent: int | None = None
        self.rect: tuple[int, int, int, int] | None = None
        self._thread: threading.Thread | None = None
        self._ready = threading.Event()
        self._error: Exception | None = None
        self._power_hwnd = None
        self._power_notify = None

    def start(self, timeout: float = 5.0) -> None:
        self._thread = threading.Thread(target=self._run, name="desktop-host", daemon=True)
        self._thread.start()
        if not self._ready.wait(timeout):
            raise RuntimeError("Timed out creating the live wallpaper window")
        if self._error:
            raise self._error

    def _run(self) -> None:
        try:
            parent, insert_after = find_wallpaper_layer()
            hinstance = GetModuleHandleW(None)
            _register_class(hinstance)
            x, y, width, height = primary_screen_rect(parent)
            ex_style = WS_EX_NOACTIVATE | (WS_EX_LAYERED if insert_after else 0)
            hwnd = CreateWindowExW(ex_style, CLASS_NAME, "Wallpaper Manager live wallpaper",
                                   WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_CLIPSIBLINGS,
                                   x, y, width, height, parent, None, hinstance, None)
            if not hwnd:
                raise ctypes.WinError(ctypes.get_last_error())
            if insert_after:
                # 24H2+: a layered (fully opaque) child placed just below the icon window.
                SetLayeredWindowAttributes(hwnd, 0, 255, LWA_ALPHA)
                SetWindowPos(hwnd, insert_after, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE)
            else:
                # New child windows go *below* existing siblings (e.g. another wallpaper app's
                # player), so move ours to the top of the wallpaper layer.
                SetWindowPos(hwnd, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE)
            self.hwnd, self.parent, self.rect = hwnd, parent, (x, y, width, height)
            _host_windows.add(hwnd)
            log.info("Live wallpaper window %#x attached to %#x at %s", hwnd, parent, self.rect)
            self._watch_display(hinstance)
        except Exception as exc:  # noqa: BLE001 - reported to the caller of start()
            self._error = exc
            self._ready.set()
            return
        self._ready.set()

        msg = wintypes.MSG()
        while GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            TranslateMessage(ctypes.byref(msg))
            DispatchMessageW(ctypes.byref(msg))
        if self._power_notify:
            UnregisterPowerSettingNotification(self._power_notify)
        if self._power_hwnd:
            DestroyWindow(self._power_hwnd)
        _host_windows.discard(self.hwnd)
        _display["on"] = True
        log.info("Live wallpaper window closed")

    def _watch_display(self, hinstance) -> None:
        """Hidden top-level window that receives "screen turned off/on" notifications."""
        self._power_hwnd = CreateWindowExW(0, CLASS_NAME, "Wallpaper Manager power", WS_POPUP,
                                           0, 0, 0, 0, None, None, hinstance, None)
        if self._power_hwnd:
            self._power_notify = RegisterPowerSettingNotification(
                self._power_hwnd, ctypes.byref(GUID_CONSOLE_DISPLAY_STATE), 0)  # 0 = window handle

    def is_alive(self) -> bool:
        """False if Explorer was restarted (our window dies with its parent)."""
        return bool(self.hwnd and IsWindow(self.hwnd) and GetParent(self.hwnd) == self.parent)

    def is_behind_icons(self) -> bool:
        """Check that our window really sits below the desktop icons in the z-order."""
        if not self.is_alive():
            return False
        if self.parent == FindWindowW("Progman", None):  # 24H2: siblings inside Progman
            start = FindWindowExW(self.parent, None, "SHELLDLL_DefView", None)
            target = self.hwnd
        else:  # classic: our parent WorkerW must come after the window holding the icons
            found: list[int] = []

            def on_window(hwnd, _lparam):
                if FindWindowExW(hwnd, None, "SHELLDLL_DefView", None):
                    found.append(hwnd)
                return True

            EnumWindows(WNDENUMPROC(on_window), 0)
            defview_owner = found[0] if found else None
            start, target = defview_owner, self.parent
        hwnd = start
        while hwnd:
            hwnd = GetWindow(hwnd, GW_HWNDNEXT)
            if hwnd == target:
                return True
        return False

    def foreign_windows(self) -> list[int]:
        """Other processes' windows in our layer, e.g. another live wallpaper app's player."""
        if not self.is_alive():
            return []
        windows, child = [], FindWindowExW(self.parent, None, None, None)
        while child:
            if child != self.hwnd and IsWindowVisible(child) and window_pid(child) != os.getpid():
                windows.append(child)
            child = FindWindowExW(self.parent, child, None, None)
        return windows

    def fit_to_screen(self) -> None:
        """Follow resolution changes of the primary monitor."""
        if not self.is_alive():
            return
        rect = primary_screen_rect(self.parent)
        if rect != self.rect:
            SetWindowPos(self.hwnd, None, *rect, SWP_NOZORDER | SWP_NOACTIVATE)
            self.rect = rect
            log.info("Live wallpaper window resized to %s", rect)

    def close(self) -> None:
        if self.hwnd and IsWindow(self.hwnd):
            PostMessageW(self.hwnd, WM_CLOSE, 0, 0)
        if self._thread:
            self._thread.join(timeout=3)
        self.hwnd = None
