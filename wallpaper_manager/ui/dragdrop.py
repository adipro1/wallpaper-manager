"""Drag files and folders from Explorer onto the window (Windows WM_DROPFILES).

Tkinter has no drag-and-drop support of its own. Windows can deliver dropped
files to any window that registers with DragAcceptFiles; the message then has
to be handled in the window's message procedure. We "subclass" the main
window: our procedure handles WM_DROPFILES and passes every other message on to
Tk's original procedure unchanged.
"""

import ctypes
import logging
import queue
from ctypes import wintypes

log = logging.getLogger(__name__)

user32 = ctypes.WinDLL("user32", use_last_error=True)
shell32 = ctypes.WinDLL("shell32", use_last_error=True)

LRESULT = ctypes.c_ssize_t
WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
WM_DROPFILES = 0x0233
GWLP_WNDPROC = -4

DragAcceptFiles = shell32.DragAcceptFiles
DragAcceptFiles.argtypes, DragAcceptFiles.restype = [wintypes.HWND, wintypes.BOOL], None
DragQueryFileW = shell32.DragQueryFileW
DragQueryFileW.argtypes = [wintypes.HANDLE, wintypes.UINT, wintypes.LPWSTR, wintypes.UINT]
DragQueryFileW.restype = wintypes.UINT
DragFinish = shell32.DragFinish
DragFinish.argtypes, DragFinish.restype = [wintypes.HANDLE], None
SetWindowLongPtrW = user32.SetWindowLongPtrW
SetWindowLongPtrW.argtypes, SetWindowLongPtrW.restype = [wintypes.HWND, ctypes.c_int, ctypes.c_void_p], ctypes.c_void_p
CallWindowProcW = user32.CallWindowProcW
CallWindowProcW.argtypes = [ctypes.c_void_p, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
CallWindowProcW.restype = LRESULT


def dropped_paths(hdrop) -> list[str]:
    """The file and folder paths contained in a WM_DROPFILES message."""
    count = DragQueryFileW(hdrop, 0xFFFFFFFF, None, 0)
    paths = []
    for index in range(count):
        length = DragQueryFileW(hdrop, index, None, 0)
        buffer = ctypes.create_unicode_buffer(length + 1)
        DragQueryFileW(hdrop, index, buffer, length + 1)
        paths.append(buffer.value)
    return paths


class FileDropTarget:
    """Collects paths dropped on `window` in `self.drops`; the app picks them up on its Tk loop.

    The window procedure runs *inside* Tk's own event processing, where calling back
    into Tkinter would crash Python, so it only puts the paths in a queue.
    """

    def __init__(self, window):
        self.drops: queue.Queue[list[str]] = queue.Queue()
        self.hwnd = int(window.wm_frame(), 16)  # the real top-level window around Tk's content
        self._proc = WNDPROC(self._window_proc)  # keep a reference, or it would be garbage-collected
        self._old_proc = SetWindowLongPtrW(self.hwnd, GWLP_WNDPROC,
                                           ctypes.cast(self._proc, ctypes.c_void_p).value)
        DragAcceptFiles(self.hwnd, True)

    def _window_proc(self, hwnd, msg, wparam, lparam):
        if msg == WM_DROPFILES:
            try:
                self.drops.put(dropped_paths(wparam))
            except Exception:  # noqa: BLE001 - never let a drop crash the window procedure
                log.exception("Reading dropped files failed")
            finally:
                DragFinish(wparam)
            return 0
        return CallWindowProcW(self._old_proc, hwnd, msg, wparam, lparam)
