"""左侧导航栏底部的常驻状态面板：CPU/内存 + 手机连接。"""
from __future__ import annotations

import time

import psutil
import requests
from PySide6.QtCore import QTimer, Qt
from PySide6.QtWidgets import QFrame, QVBoxLayout, QWidget
from qfluentwidgets import BodyLabel, CaptionLabel, ProgressBar, StrongBodyLabel

from core.config import get_config


class StatusPanel(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("StatusPanel")
        self.setFixedWidth(220)

        outer = QVBoxLayout(self)
        outer.setContentsMargins(12, 10, 12, 12)
        outer.setSpacing(8)

        # ---- 系统状态 ----
        outer.addWidget(StrongBodyLabel("🖥️ 系统状态"))
        self.cpu_lbl = CaptionLabel("CPU --%")
        self.cpu_bar = ProgressBar()
        self.cpu_bar.setRange(0, 100)
        self.cpu_bar.setTextVisible(False)
        self.cpu_bar.setFixedHeight(6)
        self.mem_lbl = CaptionLabel("内存 --%")
        self.mem_bar = ProgressBar()
        self.mem_bar.setRange(0, 100)
        self.mem_bar.setTextVisible(False)
        self.mem_bar.setFixedHeight(6)
        outer.addWidget(self.cpu_lbl)
        outer.addWidget(self.cpu_bar)
        outer.addWidget(self.mem_lbl)
        outer.addWidget(self.mem_bar)

        outer.addSpacing(6)

        # ---- 手机连接 ----
        outer.addWidget(StrongBodyLabel("📱 手机连接"))
        self.phone_ip_lbl = CaptionLabel("未配置")
        self.phone_status_lbl = CaptionLabel("● 离线")
        self.phone_status_lbl.setStyleSheet("color: #c42b1c;")
        outer.addWidget(self.phone_ip_lbl)
        outer.addWidget(self.phone_status_lbl)

        # 定时器
        self._sys_timer = QTimer(self)
        self._sys_timer.timeout.connect(self._tick_system)
        self._sys_timer.start(5000)

        self._phone_timer = QTimer(self)
        self._phone_timer.timeout.connect(self._tick_phone)
        self._phone_timer.start(30000)

        self._tick_system()
        self._tick_phone()

    def _tick_system(self) -> None:
        try:
            cpu = psutil.cpu_percent(interval=None)
            mem = psutil.virtual_memory().percent
        except Exception:
            return
        self.cpu_lbl.setText(f"CPU {cpu:.0f}%")
        self.cpu_bar.setValue(int(cpu))
        self.mem_lbl.setText(f"内存 {mem:.0f}%")
        self.mem_bar.setValue(int(mem))

    def _tick_phone(self) -> None:
        cfg = get_config()
        ip = cfg.get("phone_ip", "").strip()
        port = cfg.get("phone_port", 8000)
        if not ip:
            self.phone_ip_lbl.setText("未配置")
            self.phone_status_lbl.setText("● 离线")
            self.phone_status_lbl.setStyleSheet("color: #c42b1c;")
            return
        self.phone_ip_lbl.setText(ip)
        url = f"http://{ip}:{port}/ping"
        t0 = time.perf_counter()
        try:
            r = requests.get(url, timeout=3)
            r.raise_for_status()
            dt = int((time.perf_counter() - t0) * 1000)
            self.phone_status_lbl.setText(f"● 在线 {dt}ms")
            self.phone_status_lbl.setStyleSheet("color: #0f7b0f;")
        except requests.RequestException:
            self.phone_status_lbl.setText("● 离线")
            self.phone_status_lbl.setStyleSheet("color: #c42b1c;")
