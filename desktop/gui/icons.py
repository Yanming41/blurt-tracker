"""自定义导航图标工具。

两种用法：
1) 用 emoji 当图标 — emoji_icon("📊")，开箱即用
2) 用本地 PNG/SVG 文件 — file_icon("dashboard.png")，放在 desktop/assets/icons/ 下
"""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont, QIcon, QPainter, QPixmap

ICONS_DIR = Path(__file__).resolve().parent.parent / "assets" / "icons"


def emoji_icon(emoji: str, size: int = 32) -> QIcon:
    """把一个 emoji/字符渲染成 QIcon — 系统会用彩色 emoji 字体。"""
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setRenderHint(QPainter.TextAntialiasing)
    # Windows 11 上 "Segoe UI Emoji" 是彩色字体
    f = QFont("Segoe UI Emoji")
    f.setPixelSize(int(size * 0.78))
    p.setFont(f)
    p.setPen(QColor("#000000"))
    p.drawText(pm.rect(), Qt.AlignCenter, emoji)
    p.end()
    return QIcon(pm)


def file_icon(filename: str) -> QIcon:
    """从 desktop/assets/icons/ 加载图标。"""
    path = ICONS_DIR / filename
    if not path.exists():
        # 找不到就返回空图标，不崩
        return QIcon()
    return QIcon(str(path))
