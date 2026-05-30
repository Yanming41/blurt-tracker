"""主窗口：左侧 Sidebar + 右侧 QStackedWidget，含系统托盘。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QIcon, QPixmap
from PySide6.QtWidgets import (
    QHBoxLayout,
    QMainWindow,
    QMenu,
    QStackedWidget,
    QSystemTrayIcon,
    QWidget,
)

from .dashboard import DashboardPage
from .location import LocationPage
from .settings import SettingsPage
from .sidebar import Sidebar
from .timeline import TimelinePage


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("烂摊子控制台")
        self.setMinimumSize(1200, 800)

        # 中央布局
        central = QWidget()
        root = QHBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        self.sidebar = Sidebar()
        self.stack = QStackedWidget()
        self.pages: dict[str, QWidget] = {
            "dashboard": DashboardPage(),
            "timeline": TimelinePage(),
            "location": LocationPage(),
            "settings": SettingsPage(),
        }
        for w in self.pages.values():
            self.stack.addWidget(w)

        self.sidebar.nav_changed.connect(self._switch_page)
        root.addWidget(self.sidebar)
        root.addWidget(self.stack, 1)
        self.setCentralWidget(central)

        # 系统托盘
        self._init_tray()
        self._force_quit = False
        self._center_on_screen()

    def _init_tray(self) -> None:
        icon = self._build_tray_icon()
        self.tray = QSystemTrayIcon(icon, self)
        self.tray.setToolTip("烂摊子控制台")
        menu = QMenu()
        show_act = QAction("显示窗口", self)
        show_act.triggered.connect(self._show_from_tray)
        quit_act = QAction("退出", self)
        quit_act.triggered.connect(self._quit_app)
        menu.addAction(show_act)
        menu.addSeparator()
        menu.addAction(quit_act)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(self._on_tray_activated)
        self.tray.show()
        self.tray.showMessage("烂摊子", "烂摊子已启动，正在记录中 🗑️",
                              QSystemTrayIcon.Information, 4000)

    @staticmethod
    def _build_tray_icon() -> QIcon:
        # 用一个简单的红色圆形作为占位图标
        pm = QPixmap(32, 32)
        pm.fill(Qt.transparent)
        from PySide6.QtGui import QPainter, QColor
        p = QPainter(pm)
        p.setRenderHint(QPainter.Antialiasing)
        p.setBrush(QColor("#e94560"))
        p.setPen(Qt.NoPen)
        p.drawEllipse(2, 2, 28, 28)
        p.end()
        return QIcon(pm)

    def _on_tray_activated(self, reason) -> None:
        if reason == QSystemTrayIcon.Trigger:
            self._show_from_tray()

    def _show_from_tray(self) -> None:
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def _switch_page(self, key: str) -> None:
        if key in self.pages:
            self.stack.setCurrentWidget(self.pages[key])

    def _center_on_screen(self) -> None:
        screen = self.screen() or self.windowHandle().screen()
        if screen is None:
            return
        geom = screen.availableGeometry()
        self.resize(min(1400, geom.width() - 80), min(900, geom.height() - 80))
        fg = self.frameGeometry()
        fg.moveCenter(geom.center())
        self.move(fg.topLeft())

    def _quit_app(self) -> None:
        from PySide6.QtWidgets import QApplication
        self._force_quit = True
        self.tray.hide()
        QApplication.instance().quit()

    def closeEvent(self, event) -> None:
        if self._force_quit:
            event.accept()
            return
        # 关闭按钮 → 隐藏到托盘
        event.ignore()
        self.hide()
        self.tray.showMessage("烂摊子", "已最小化到托盘，继续在后台记录",
                              QSystemTrayIcon.Information, 2000)
