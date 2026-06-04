"""主窗口 — FluentWindow + Mica + 左侧导航 + 状态面板 + 系统托盘。"""
from __future__ import annotations

import sys

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QColor, QGuiApplication, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon
from qfluentwidgets import (
    FluentIcon as FIF,
    FluentWindow,
    NavigationItemPosition,
)

from core.config import get_config

from .city import CityInterface
from .dashboard import DashboardInterface
from .icons import emoji_icon, file_icon
from .location import LocationInterface
from .setting import SettingInterface
from .status_panel import StatusPanel
from .timeline import TimelineInterface


def _build_tray_icon() -> QIcon:
    pm = QPixmap(32, 32)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setBrush(QColor("#0078d4"))
    p.setPen(Qt.NoPen)
    p.drawEllipse(2, 2, 28, 28)
    p.end()
    return QIcon(pm)


class MainWindow(FluentWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("烂摊子控制台")
        self._apply_initial_size()

        # 根据配置决定 Mica 毛玻璃 — 默认关，可在设置页打开
        self.set_mica(bool(get_config().get("mica_enabled", False)))

        # 创建各页面
        self.dashboard = DashboardInterface(self)
        self.timeline = TimelineInterface(self)
        self.location = LocationInterface(self)
        self.city = CityInterface(self)
        self.setting = SettingInterface(self)

        # 主导航 — 默认用 emoji 图标（彩色）；
        # 想换成自定义 PNG，把 emoji_icon("📊") 换成 file_icon("dashboard.png")
        # 并把图标文件放到 desktop/assets/icons/ 即可
        self.addSubInterface(self.dashboard, emoji_icon("📊"), "今日概览")
        self.addSubInterface(self.timeline, emoji_icon("📅"), "今日时间轴")
        self.addSubInterface(self.location, emoji_icon("📍"), "位置记录")
        self.addSubInterface(self.city, emoji_icon("🏙️"), "我的城市")

        # 设置放底部
        self.addSubInterface(
            self.setting, emoji_icon("⚙️"), "设置",
            position=NavigationItemPosition.BOTTOM,
        )

        # 状态面板嵌入导航栏底部
        self.status_panel = StatusPanel(self)
        try:
            self.navigationInterface.addWidget(
                routeKey="statusPanel",
                widget=self.status_panel,
                onClick=None,
                position=NavigationItemPosition.BOTTOM,
            )
        except Exception:
            pass

        # 默认进入概览页
        self.switchTo(self.dashboard)

        # 系统托盘
        self._force_quit = False
        self._init_tray()
        self._center_on_screen()

    # ---------- Mica 切换 ----------

    def set_mica(self, enabled: bool) -> None:
        """切换 Windows 11 毛玻璃效果。"""
        try:
            self.setMicaEffectEnabled(bool(enabled))
        except Exception:
            # 老版本 qfluentwidgets 或非 Win11 — 忽略
            pass

    # ---------- 托盘 ----------

    def _init_tray(self) -> None:
        self.tray = QSystemTrayIcon(_build_tray_icon(), self)
        self.tray.setToolTip("烂摊子控制台 🗑️")

        menu = QMenu()
        show_act = QAction("显示主窗口", self)
        show_act.triggered.connect(self._show_from_tray)
        quit_act = QAction("退出烂摊子", self)
        quit_act.triggered.connect(self._quit_app)
        menu.addAction(show_act)
        menu.addSeparator()
        menu.addAction(quit_act)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(self._on_tray_activated)
        self.tray.show()
        self.tray.showMessage(
            "烂摊子", "烂摊子已启动，正在记录中 🗑️",
            QSystemTrayIcon.Information, 4000,
        )

    def _on_tray_activated(self, reason) -> None:
        if reason == QSystemTrayIcon.Trigger:
            self._show_from_tray()

    def _show_from_tray(self) -> None:
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def _quit_app(self) -> None:
        self._force_quit = True
        self.tray.hide()
        QApplication.instance().quit()

    # ---------- 窗口行为 ----------

    def _apply_initial_size(self) -> None:
        """根据屏幕可用区域和缩放倍率自适应初始窗口大小。"""
        screen = QGuiApplication.primaryScreen()
        if screen is None:
            self.setMinimumSize(900, 650)
            self.resize(1100, 750)
            return
        # availableGeometry 已经是逻辑像素，会自动除以 DPR
        avail = screen.availableGeometry()
        # 理想大小取屏幕的 85%，并 cap 在 1400×900
        w = min(1400, int(avail.width() * 0.85))
        h = min(900, int(avail.height() * 0.85))
        # 最小尺寸不能比窗口大
        min_w = min(900, w)
        min_h = min(650, h)
        self.setMinimumSize(min_w, min_h)
        self.resize(w, h)

    def _center_on_screen(self) -> None:
        screen = QGuiApplication.primaryScreen()
        if screen is None:
            return
        geom = screen.availableGeometry()
        fg = self.frameGeometry()
        fg.moveCenter(geom.center())
        self.move(fg.topLeft())

    def closeEvent(self, event) -> None:
        if self._force_quit:
            event.accept()
            return
        event.ignore()
        self.hide()
        self.tray.showMessage(
            "烂摊子", "已最小化到托盘，继续在后台记录",
            QSystemTrayIcon.Information, 2000,
        )
