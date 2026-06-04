"""统一的通知/错误弹窗 helper —— 错误气泡点击可复制内容到剪切板。"""
from __future__ import annotations

from PySide6.QtCore import QEvent, QObject
from PySide6.QtGui import QGuiApplication
from qfluentwidgets import InfoBar, InfoBarPosition


class _CopyOnClick(QObject):
    """事件过滤器：捕获点击，把文本复制到剪切板并提示。"""

    def __init__(self, text: str, parent_widget):
        super().__init__(parent_widget)
        self.text = text
        self.parent_widget = parent_widget

    def eventFilter(self, obj, event) -> bool:
        if event.type() == QEvent.MouseButtonPress:
            try:
                QGuiApplication.clipboard().setText(self.text)
                InfoBar.success(
                    "已复制",
                    "报错信息已复制",
                    duration=1500,
                    position=InfoBarPosition.TOP,
                    parent=self.parent_widget,
                )
            except Exception:
                pass
        return False  # 不消费事件，让 InfoBar 正常工作


def error(parent, title: str, content: str, duration: int = 4000) -> None:
    """显示错误气泡，点击会复制 "标题: 正文" 到剪切板。"""
    bar = InfoBar.error(
        title=title,
        content=content,
        duration=duration,
        position=InfoBarPosition.TOP,
        parent=parent,
    )
    if bar is None:
        return
    text = f"{title}: {content}" if title else content
    flt = _CopyOnClick(text, parent)
    bar.installEventFilter(flt)
    # 也给内部子控件装上 — 点击 label/icon 都能触发
    for child in bar.findChildren(QObject):
        try:
            child.installEventFilter(flt)
        except Exception:
            pass
    # 给鼠标一个手指光标提示"这里可以点"
    try:
        from PySide6.QtCore import Qt
        bar.setCursor(Qt.PointingHandCursor)
    except Exception:
        pass


def success(parent, title: str, content: str = "", duration: int = 2500) -> None:
    InfoBar.success(title=title, content=content, duration=duration,
                    position=InfoBarPosition.TOP, parent=parent)


def info(parent, title: str, content: str = "", duration: int = 2000) -> None:
    InfoBar.info(title=title, content=content, duration=duration,
                 position=InfoBarPosition.TOP, parent=parent)


def warning(parent, title: str, content: str = "", duration: int = 3000) -> None:
    InfoBar.warning(title=title, content=content, duration=duration,
                    position=InfoBarPosition.TOP, parent=parent)
