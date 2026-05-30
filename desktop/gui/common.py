"""GUI 通用小部件。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QFrame, QLabel, QVBoxLayout

from .theme import ACCENT, TEXT, TEXT_DIM


class StatCard(QFrame):
    """顶部 4 列统计卡片之一。"""

    def __init__(self, title: str, value: str = "--", parent=None):
        super().__init__(parent)
        self.setObjectName("Card")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 12, 14, 12)
        lay.setSpacing(4)
        self._title_lbl = QLabel(title)
        self._title_lbl.setStyleSheet(f"color: {TEXT_DIM}; font-size: 12px;")
        self._value_lbl = QLabel(value)
        self._value_lbl.setStyleSheet(f"color: {TEXT}; font-size: 22px; font-weight: bold;")
        lay.addWidget(self._title_lbl)
        lay.addWidget(self._value_lbl)

    def set_value(self, value: str) -> None:
        self._value_lbl.setText(value)


def format_duration(seconds: int) -> str:
    if seconds < 60:
        return f"{seconds}秒"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes}分钟"
    hours = minutes // 60
    rem = minutes % 60
    if rem == 0:
        return f"{hours}小时"
    return f"{hours}小时{rem}分"


def source_icon(source: str) -> str:
    return {
        "windows": "🖥️",
        "mobile": "📱",
        "location": "📍",
    }.get(source, "•")


def source_tag_html(source: str) -> str:
    """蓝色 = 手机；绿色 = 电脑；灰色 = 位置。"""
    color, label = {
        "mobile":   ("#4a90e2", "手机"),
        "windows":  ("#4caf50", "电脑"),
        "location": ("#9e9e9e", "位置"),
    }.get(source, ("#9e9e9e", source))
    return (
        f'<span style="background:{color};color:white;padding:2px 8px;'
        f'border-radius:6px;font-size:11px;">{label}</span>'
    )
