"""检测用户键鼠闲置时长（Windows API）。"""
from __future__ import annotations

import ctypes
from ctypes import wintypes


class _LASTINPUTINFO(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.UINT),
        ("dwTime", wintypes.DWORD),
    ]


def get_idle_seconds() -> float:
    """返回距离上一次键盘/鼠标输入的秒数。

    非 Windows 平台返回 0.0（永远视为活跃）。
    """
    if not hasattr(ctypes, "windll"):
        return 0.0
    lii = _LASTINPUTINFO()
    lii.cbSize = ctypes.sizeof(_LASTINPUTINFO)
    if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(lii)):
        return 0.0
    millis = ctypes.windll.kernel32.GetTickCount() - lii.dwTime
    return max(0.0, millis / 1000.0)
