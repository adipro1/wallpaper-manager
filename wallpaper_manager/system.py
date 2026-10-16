"""Windows system state: fullscreen apps, battery power, single instance, start with Windows."""

import ctypes
import logging
import os
import sys
import winreg
from ctypes import wintypes
from pathlib import Path

log = logging.getLogger(__name__)

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
shell32 = ctypes.WinDLL("shell32", use_last_error=True)


def _fn(func, restype, *argtypes):
    func.restype, func.argtypes = restype, argtypes
    return func


GetForegroundWindow = _fn(user32.GetForegroundWindow, wintypes.HWND)
GetWindowRect = _fn(user32.GetWindowRect, wintypes.BOOL, wintypes.HWND, ctypes.POINTER(wintypes.RECT))
GetClassNameW = _fn(user32.GetClassNameW, ctypes.c_int, wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
GetWindowLongW = _fn(user32.GetWindowLongW, ctypes.c_long, wintypes.HWND, ctypes.c_int)
IsZoomed = _fn(user32.IsZoomed, wintypes.BOOL, wintypes.HWND)
GetWindowThreadProcessId = _fn(user32.GetWindowThreadProcessId, wintypes.DWORD, wintypes.HWND,
                               ctypes.POINTER(wintypes.DWORD))
MonitorFromWindow = _fn(user32.MonitorFromWindow, wintypes.HMONITOR, wintypes.HWND, wintypes.DWORD)
SHQueryUserNotificationState = _fn(shell32.SHQueryUserNotificationState, ctypes.c_long,
                                   ctypes.POINTER(ctypes.c_int))


class MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT), ("rcWork", wintypes.RECT),
                ("dwFlags", wintypes.DWORD)]


GetMonitorInfoW = _fn(user32.GetMonitorInfoW, wintypes.BOOL, wintypes.HMONITOR, ctypes.POINTER(MONITORINFO))

MONITOR_DEFAULTTONEAREST = 2
GWL_STYLE = -16
WS_CAPTION = 0x00C00000
# Values of SHQueryUserNotificationState that mean "the user is busy with something full screen"
QUNS_BUSY, QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE = 2, 3, 4
# The desktop and the taskbar cover the screen too, but they are not "fullscreen apps".
SHELL_WINDOW_CLASSES = {"Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd"}


# --- fullscreen detection ---------------------------------------------------------

def fullscreen_app_running() -> bool:
    """True when a game, video player or presentation is running full screen."""
    state = ctypes.c_int()
    if SHQueryUserNotificationState(ctypes.byref(state)) == 0 and state.value in (
            QUNS_BUSY, QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE):
        return True
    return _foreground_covers_monitor()


def _foreground_covers_monitor() -> bool:
    """Catches "borderless fullscreen" games, which Windows doesn't always report as busy."""
    hwnd = GetForegroundWindow()
    if not hwnd:
        return False
    name = ctypes.create_unicode_buffer(64)
    GetClassNameW(hwnd, name, 64)
    if name.value in SHELL_WINDOW_CLASSES:
        return False
    pid = wintypes.DWORD()
    GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    if pid.value == os.getpid():
        return False  # our own window
    # A normal maximized window (title bar) is not fullscreen, even when the taskbar is
    # set to auto-hide and the window therefore covers the whole screen.
    if IsZoomed(hwnd) and GetWindowLongW(hwnd, GWL_STYLE) & WS_CAPTION == WS_CAPTION:
        return False
    rect = wintypes.RECT()
    info = MONITORINFO(cbSize=ctypes.sizeof(MONITORINFO))
    if not GetWindowRect(hwnd, ctypes.byref(rect)) or not GetMonitorInfoW(
            MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), ctypes.byref(info)):
        return False
    mon = info.rcMonitor
    return rect.left <= mon.left and rect.top <= mon.top and rect.right >= mon.right and rect.bottom >= mon.bottom


# --- power -----------------------------------------------------------------------------

class SYSTEM_POWER_STATUS(ctypes.Structure):
    _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte),
                ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_ubyte),
                ("BatteryLifeTime", wintypes.DWORD), ("BatteryFullLifeTime", wintypes.DWORD)]


GetSystemPowerStatus = _fn(kernel32.GetSystemPowerStatus, wintypes.BOOL, ctypes.POINTER(SYSTEM_POWER_STATUS))


def on_battery() -> bool:
    """True when running on battery (ACLineStatus 0). Desktops without a battery report 1."""
    status = SYSTEM_POWER_STATUS()
    return bool(GetSystemPowerStatus(ctypes.byref(status))) and status.ACLineStatus == 0


# --- single instance -------------------------------------------------------------------

CreateMutexW = _fn(kernel32.CreateMutexW, wintypes.HANDLE, wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR)
CreateEventW = _fn(kernel32.CreateEventW, wintypes.HANDLE, wintypes.LPVOID, wintypes.BOOL, wintypes.BOOL,
                   wintypes.LPCWSTR)
SetEvent = _fn(kernel32.SetEvent, wintypes.BOOL, wintypes.HANDLE)
WaitForSingleObject = _fn(kernel32.WaitForSingleObject, wintypes.DWORD, wintypes.HANDLE, wintypes.DWORD)
CloseHandle = _fn(kernel32.CloseHandle, wintypes.BOOL, wintypes.HANDLE)
ERROR_ALREADY_EXISTS = 183
WAIT_OBJECT_0 = 0


class SingleInstance:
    """Makes sure only one copy of the app runs (two would fight over the live wallpaper).

    The first copy owns a named mutex. A second copy notices the mutex already exists,
    signals a named event to ask the first copy to show its window, and exits.
    """

    def __init__(self, name: str):
        self._mutex = CreateMutexW(None, False, f"Local\\{name}.Instance")
        self.already_running = ctypes.get_last_error() == ERROR_ALREADY_EXISTS
        self._show_event = CreateEventW(None, False, False, f"Local\\{name}.Show")  # auto-reset

    def ask_existing_to_show(self) -> None:
        SetEvent(self._show_event)

    def show_requested(self) -> bool:
        """Non-blocking check, polled by the running app."""
        return WaitForSingleObject(self._show_event, 0) == WAIT_OBJECT_0

    def close(self) -> None:
        for handle in (self._show_event, self._mutex):
            if handle:
                CloseHandle(handle)
        self._show_event = self._mutex = None


# --- start with Windows ---------------------------------------------------------------------

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "WallpaperManager"


def startup_command() -> str:
    """Command Windows runs at sign-in: the app without a console window, hidden in the tray."""
    if getattr(sys, "frozen", False):  # packaged .exe
        return f'"{sys.executable}" --minimized'
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    main_py = Path(__file__).resolve().parent.parent / "main.py"
    return f'"{pythonw}" "{main_py}" --minimized'


def startup_enabled() -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            winreg.QueryValueEx(key, RUN_VALUE)
            return True
    except OSError:
        return False


def set_startup(enabled: bool) -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, RUN_VALUE, 0, winreg.REG_SZ, startup_command())
        else:
            try:
                winreg.DeleteValue(key, RUN_VALUE)
            except FileNotFoundError:
                pass
    log.info("Start with Windows %s", "enabled" if enabled else "disabled")
