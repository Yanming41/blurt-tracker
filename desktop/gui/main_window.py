"""主窗口 — FluentWindow + Mica + 左侧导航 + 状态面板 + 系统托盘。"""
from __future__ import annotations

import sys

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QColor, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon
from qfluentwidgets import (
    FluentIcon as FIF,
    FluentWindow,
    NavigationItemPosition,
)

from .city import CityInterface
from .dashboard import DashboardInterface
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
        self.setMinimumSize(1200, 800)
        self.resize(1400, 900)

        # 启用 Windows 11 Mica
        try:
            self.setMicaEffectEnabled(True)
        except Exception:
            # 老版本 qfluentwidgets 或非 Win11 — 忽略
            pass

        # 创建各页面
        self.dashboard = DashboardInterface(self)
        self.timeline = TimelineInterface(self)
        self.location = LocationInterface(self)
        self.city = CityInterface(self)
        self.setting = SettingInterface(self)

        # 主导航
        self.addSubInterface(self.dashboard, FIF.HOME, "今日概览")
        self.addSubInterface(self.timeline, FIF.CALENDAR, "时间线")
        self.addSubInterface(self.location, FIF.PIN, "位置记录")
        self.addSubInterface(self.city, FIF.GLOBE, "我的城市")

        # 设置放底部
        self.addSubInterface(
            self.setting, FIF.SETTING, "设置",
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

    def _center_on_screen(self) -> None:
        screen = self.screen()
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
