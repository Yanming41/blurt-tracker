"""左侧边栏：标题、手机连接、系统状态、Ollama、导航。"""
from __future__ import annotations

import time

import psutil
import requests
from PySide6.QtCore import QTimer, Qt, Signal
from PySide6.QtGui import QInputMethodEvent
from PySide6.QtWidgets import (
    QButtonGroup,
    QFrame,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QProgressBar,
    QPushButton,
    QSizePolicy,
    QSpacerItem,
    QVBoxLayout,
    QWidget,
)

from core.api import ollama_available
from core.config import get_config, update_config

from .theme import DANGER, SUCCESS, TEXT_DIM


SIDEBAR_WIDTH = 250


def _section_title(text: str) -> QLabel:
    lbl = QLabel(text)
    lbl.setProperty("class", "section-title")
    lbl.setStyleSheet(f"color: {TEXT_DIM}; font-size: 11px; font-weight: bold;")
    return lbl


def _card() -> QFrame:
    f = QFrame()
    f.setObjectName("SidebarCard")
    return f


class Sidebar(QWidget):
    nav_changed = Signal(str)  # 发出页面 key: dashboard/timeline/location/settings

    NAV_ITEMS = [
        ("dashboard", "📊 今日概览"),
        ("timeline",  "📅 时间线"),
        ("location",  "📍 位置记录"),
        ("settings",  "⚙️  设置"),
    ]

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("Sidebar")
        self.setFixedWidth(SIDEBAR_WIDTH)

        outer = QVBoxLayout(self)
        outer.setContentsMargins(14, 14, 14, 14)
        outer.setSpacing(12)

        # ----- 标题 -----
        title = QLabel("🗑️ 烂摊子")
        title.setObjectName("SidebarTitle")
        subtitle = QLabel("控制台 v0.1")
        subtitle.setObjectName("SidebarSubtitle")
        outer.addWidget(title)
        outer.addWidget(subtitle)

        # ----- 手机连接卡片 -----
        outer.addWidget(_section_title("📱 手机连接"))
        phone_card = _card()
        phone_lay = QVBoxLayout(phone_card)
        phone_lay.setContentsMargins(10, 10, 10, 10)
        phone_lay.setSpacing(4)
        self.phone_status_lbl = QLabel("● 未连接")
        self.phone_status_lbl.setStyleSheet(f"color: {DANGER};")
        self.phone_ip_lbl = QLabel("未配置")
        self.phone_ip_lbl.setStyleSheet(f"color: {TEXT_DIM}; font-size: 11px;")
        edit_btn = QPushButton("修改IP")
        edit_btn.clicked.connect(self._edit_phone_ip)
        phone_lay.addWidget(self.phone_status_lbl)
        phone_lay.addWidget(self.phone_ip_lbl)
        phone_lay.addWidget(edit_btn)
        outer.addWidget(phone_card)

        # ----- 系统状态卡片 -----
        outer.addWidget(_section_title("🖥️ 系统状态"))
        sys_card = _card()
        sys_lay = QVBoxLayout(sys_card)
        sys_lay.setContentsMargins(10, 10, 10, 10)
        sys_lay.setSpacing(6)
        self.cpu_lbl = QLabel("CPU    --%")
        self.cpu_bar = QProgressBar(); self.cpu_bar.setRange(0, 100); self.cpu_bar.setTextVisible(False)
        self.mem_lbl = QLabel("内存   --%")
        self.mem_bar = QProgressBar(); self.mem_bar.setRange(0, 100); self.mem_bar.setTextVisible(False)
        self.disk_lbl = QLabel("磁盘   -- GB")
        for w in (self.cpu_lbl, self.cpu_bar, self.mem_lbl, self.mem_bar, self.disk_lbl):
            sys_lay.addWidget(w)
        outer.addWidget(sys_card)

        # ----- Ollama 卡片 -----
        outer.addWidget(_section_title("🤖 Ollama"))
        ol_card = _card()
        ol_lay = QVBoxLayout(ol_card)
        ol_lay.setContentsMargins(10, 10, 10, 10)
        ol_lay.setSpacing(4)
        self.ollama_status_lbl = QLabel("● 检查中…")
        self.ollama_status_lbl.setStyleSheet(f"color: {TEXT_DIM};")
        self.ollama_model_lbl = QLabel(get_config().get("ollama_model", ""))
        self.ollama_model_lbl.setStyleSheet(f"color: {TEXT_DIM}; font-size: 11px;")
        ol_lay.addWidget(self.ollama_status_lbl)
        ol_lay.addWidget(self.ollama_model_lbl)
        outer.addWidget(ol_card)

        # ----- 导航 -----
        outer.addWidget(_section_title("导航"))
        self._nav_group = QButtonGroup(self)
        self._nav_group.setExclusive(True)
        for key, label in self.NAV_ITEMS:
            btn = QPushButton(label)
            btn.setObjectName("NavButton")
            btn.setCheckable(True)
            btn.setProperty("nav_key", key)
            btn.clicked.connect(lambda _checked=False, k=key: self.nav_changed.emit(k))
            self._nav_group.addButton(btn)
            outer.addWidget(btn)
        # 默认选中第一个
        first_btn = self._nav_group.buttons()[0]
        first_btn.setChecked(True)

        outer.addItem(QSpacerItem(0, 0, QSizePolicy.Minimum, QSizePolicy.Expanding))

        # ----- 数据初始化 + 定时刷新 -----
        self._refresh_phone_ip_label()
        self._tick_system()
        self._tick_phone()
        self._tick_ollama()

        self._sys_timer = QTimer(self); self._sys_timer.timeout.connect(self._tick_system); self._sys_timer.start(5000)
        self._phone_timer = QTimer(self); self._phone_timer.timeout.connect(self._tick_phone); self._phone_timer.start(30000)
        self._ollama_timer = QTimer(self); self._ollama_timer.timeout.connect(self._tick_ollama); self._ollama_timer.start(30000)

    # ------- 手机 -------

    def _refresh_phone_ip_label(self) -> None:
        cfg = get_config()
        ip = cfg.get("phone_ip", "")
        self.phone_ip_lbl.setText(ip if ip else "未配置")

    def _edit_phone_ip(self) -> None:
        cfg = get_config()
        current = cfg.get("phone_ip", "")
        text, ok = QInputDialog.getText(self, "修改手机IP", "手机IP地址（Tailscale）：", text=current)
        if ok:
            update_config(phone_ip=text.strip())
            self._refresh_phone_ip_label()
            self._tick_phone()

    def _tick_phone(self) -> None:
        cfg = get_config()
        ip = cfg.get("phone_ip", "").strip()
        port = cfg.get("phone_port", 8000)
        if not ip:
            self.phone_status_lbl.setText("● 未配置")
            self.phone_status_lbl.setStyleSheet(f"color: {DANGER};")
            return
        url = f"http://{ip}:{port}/ping"
        t0 = time.perf_counter()
        try:
            r = requests.get(url, timeout=3)
            r.raise_for_status()
            dt_ms = int((time.perf_counter() - t0) * 1000)
            self.phone_status_lbl.setText(f"● 在线 {dt_ms}ms")
            self.phone_status_lbl.setStyleSheet(f"color: {SUCCESS};")
        except requests.RequestException:
            self.phone_status_lbl.setText("● 离线")
            self.phone_status_lbl.setStyleSheet(f"color: {DANGER};")

    # ------- 系统监控 -------

    def _tick_system(self) -> None:
        try:
            cpu = psutil.cpu_percent(interval=None)
            mem = psutil.virtual_memory().percent
            disk_free = psutil.disk_usage("C:\\" if hasattr(psutil, "WINDOWS") or True else "/").free
            disk_gb = disk_free / (1024 ** 3)
        except Exception:
            return
        self.cpu_lbl.setText(f"CPU    {cpu:.0f}%")
        self.cpu_bar.setValue(int(cpu))
        self.cpu_bar.setProperty("warn", cpu > 80)
        self.cpu_bar.style().unpolish(self.cpu_bar); self.cpu_bar.style().polish(self.cpu_bar)

        self.mem_lbl.setText(f"内存   {mem:.0f}%")
        self.mem_bar.setValue(int(mem))
        self.mem_bar.setProperty("warn", mem > 80)
        self.mem_bar.style().unpolish(self.mem_bar); self.mem_bar.style().polish(self.mem_bar)

        self.disk_lbl.setText(f"磁盘   {disk_gb:.0f}GB 可用")

    # ------- Ollama -------

    def _tick_ollama(self) -> None:
        ok = ollama_available()
        if ok:
            self.ollama_status_lbl.setText("● 在线")
            self.ollama_status_lbl.setStyleSheet(f"color: {SUCCESS};")
        else:
            self.ollama_status_lbl.setText("● 离线")
            self.ollama_status_lbl.setStyleSheet(f"color: {DANGER};")
        self.ollama_model_lbl.setText(get_config().get("ollama_model", ""))
