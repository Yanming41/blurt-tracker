"""左侧导航栏底部的常驻状态面板：CPU/内存 + 手机连接。"""
from __future__ import annotations

import psutil
from PySide6.QtCore import QTimer, Qt
from PySide6.QtWidgets import QFrame, QVBoxLayout, QWidget
from qfluentwidgets import BodyLabel, CaptionLabel, ProgressBar, StrongBodyLabel

from core.config import get_config
from core.idle import get_idle_seconds

from .async_check import PingChecker


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

        self.idle_lbl = CaptionLabel("活跃中")
        self.idle_lbl.setStyleSheet("color: #0f7b0f;")
        outer.addWidget(self.idle_lbl)

        outer.addSpacing(6)

        # ---- 手机连接 ----
        outer.addWidget(StrongBodyLabel("📱 手机连接"))
        self.phone_ip_lbl = CaptionLabel("未配置")
        self.phone_status_lbl = CaptionLabel("● 离线")
        self.phone_status_lbl.setStyleSheet("color: #c42b1c;")
        outer.addWidget(self.phone_ip_lbl)
        outer.addWidget(self.phone_status_lbl)

        # 异步 ping 检查器
        self._pinger = PingChecker(self)
        self._pinger.result.connect(self._on_phone_result)

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

        # 闲置时长
        idle = get_idle_seconds()
        if idle < 60:
            self.idle_lbl.setText("活跃中")
            self.idle_lbl.setStyleSheet("color: #0f7b0f;")
        elif idle < 300:
            self.idle_lbl.setText(f"闲置 {int(idle/60)}分钟")
            self.idle_lbl.setStyleSheet("color: #8a8a8a;")
        else:
            self.idle_lbl.setText(f"已离开 {int(idle/60)}分钟")
            self.idle_lbl.setStyleSheet("color: #c42b1c;")

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
        # 不阻塞主线程，结果回到 _on_phone_result
        self._pinger.check(f"http://{ip}:{port}/ping", timeout=3)

    def _on_phone_result(self, online: bool, ms: int) -> None:
        if online:
            self.phone_status_lbl.setText(f"● 在线 {ms}ms")
            self.phone_status_lbl.setStyleSheet("color: #0f7b0f;")
        else:
            self.phone_status_lbl.setText("● 离线")
            self.phone_status_lbl.setStyleSheet("color: #c42b1c;")
