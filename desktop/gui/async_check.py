"""把网络检查甩到后台线程，避免阻塞 Qt 主循环。

用法：
    self.checker = PingChecker(self)
    self.checker.result.connect(self.on_ping_result)
    self.checker.check("http://100.x.y.z:8000/ping")
"""
from __future__ import annotations

import threading
import time

import requests
from PySide6.QtCore import QObject, Signal


class PingChecker(QObject):
    """对一个 URL 发 GET，结果通过 result 信号回到主线程。"""

    # online (bool), latency_ms (int)
    result = Signal(bool, int)

    def check(self, url: str, timeout: float = 3.0) -> None:
        def _worker():
            try:
                t0 = time.perf_counter()
                r = requests.get(url, timeout=timeout)
                r.raise_for_status()
                dt = int((time.perf_counter() - t0) * 1000)
                self.result.emit(True, dt)
            except Exception:
                self.result.emit(False, 0)
        threading.Thread(target=_worker, daemon=True).start()


class OllamaChecker(QObject):
    """异步探测 Ollama 是否在线。"""

    result = Signal(bool)  # available

    def check(self, base_url: str, timeout: float = 3.0) -> None:
        url = base_url.rstrip("/") + "/api/tags"

        def _worker():
            try:
                r = requests.get(url, timeout=timeout)
                self.result.emit(bool(r.ok))
            except Exception:
                self.result.emit(False)

        threading.Thread(target=_worker, daemon=True).start()
