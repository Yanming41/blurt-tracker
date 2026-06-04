"""Foreground-window monitor for Windows.

Samples the active window every 60 seconds, merges consecutive identical
windows into time ranges, and persists them to app_records_windows.
"""
from __future__ import annotations

import logging
import threading
import time
from datetime import datetime
from typing import Optional

import psutil

try:
    import win32gui
    import win32process
except ImportError:  # non-Windows dev environments
    win32gui = None
    win32process = None

from .database import AppRecordWindows, SessionLocal
from .idle import get_idle_seconds

# 闲置超过这个秒数就停止把当前窗口算作"在用"
IDLE_THRESHOLD_SECONDS = 300  # 5 分钟

logger = logging.getLogger(__name__)

SAMPLE_INTERVAL_SECONDS = 60

# 过滤掉这些系统/外壳进程，避免在时间线上刷屏
IGNORED_PROCESSES = {
    "explorer.exe",
    "searchhost.exe",
    "shellexperiencehost.exe",
    "startmenuexperiencehost.exe",
    "textinputhost.exe",
    "lockapp.exe",
    "applicationframehost.exe",
    "dwm.exe",
    "sihost.exe",
}


def _get_active_window() -> Optional[tuple[str, str]]:
    """Return (app_name, window_title) for the current foreground window."""
    if win32gui is None:
        return None
    hwnd = win32gui.GetForegroundWindow()
    if not hwnd:
        return None
    title = win32gui.GetWindowText(hwnd) or ""
    try:
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        proc = psutil.Process(pid)
        app_name = proc.name()
    except (psutil.NoSuchProcess, psutil.AccessDenied, ValueError):
        app_name = "unknown"
    return app_name, title


class WindowMonitor:
    def __init__(self, interval: int = SAMPLE_INTERVAL_SECONDS):
        self.interval = interval
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True, name="WindowMonitor")
        self._thread.start()
        logger.info("WindowMonitor started (interval=%ss)", self.interval)

    def stop(self) -> None:
        self._stop.set()

    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive() and not self._stop.is_set()

    def _run(self) -> None:
        current_app: Optional[str] = None
        current_title: Optional[str] = None
        segment_start: Optional[datetime] = None
        last_seen: Optional[datetime] = None

        while not self._stop.is_set():
            now = datetime.utcnow()

            # 闲置太久 → 封掉当前段，并暂停采样直到用户回来
            idle = get_idle_seconds()
            if idle >= IDLE_THRESHOLD_SECONDS:
                if current_app is not None and segment_start and last_seen:
                    self._persist(current_app, current_title or "", segment_start, last_seen)
                    current_app = None
                    current_title = None
                    segment_start = None
                    last_seen = None
                self._stop.wait(self.interval)
                continue

            sample = _get_active_window()
            if sample is None:
                self._stop.wait(self.interval)
                continue
            app_name, title = sample

            # 跳过系统外壳进程和空标题
            if app_name.lower() in IGNORED_PROCESSES or not title.strip():
                self._stop.wait(self.interval)
                continue

            if app_name == current_app and title == current_title:
                last_seen = now
            else:
                if current_app is not None and segment_start and last_seen:
                    self._persist(current_app, current_title or "", segment_start, last_seen)
                current_app = app_name
                current_title = title
                segment_start = now
                last_seen = now

            self._stop.wait(self.interval)

        # flush trailing segment on shutdown
        if current_app is not None and segment_start and last_seen:
            self._persist(current_app, current_title or "", segment_start, last_seen)

    @staticmethod
    def _persist(app_name: str, title: str, start: datetime, end: datetime) -> None:
        try:
            with SessionLocal() as db:
                db.add(AppRecordWindows(
                    app_name=app_name,
                    window_title=title,
                    start_time=start,
                    end_time=end,
                ))
                db.commit()
        except Exception:
            logger.exception("Failed to persist window record")
