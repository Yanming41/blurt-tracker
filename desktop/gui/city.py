"""我的城市页 — 占位。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QVBoxLayout, QWidget
from qfluentwidgets import BodyLabel, SubtitleLabel, TitleLabel


class CityInterface(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("cityInterface")

        outer = QVBoxLayout(self)
        outer.setContentsMargins(24, 24, 24, 24)
        outer.setSpacing(20)

        outer.addWidget(TitleLabel("我的城市"))
        outer.addStretch(1)

        hint_big = SubtitleLabel("🏙️ 即将推出")
        hint_big.setAlignment(Qt.AlignCenter)
        hint_big.setStyleSheet("font-size: 28px; color: #555;")
        outer.addWidget(hint_big)

        hint_sub = BodyLabel(
            "城市探索功能正在开发中，敬请期待。\n"
            "未来这里会展示你常去的地点、新发现的地方、以及城市热力图。"
        )
        hint_sub.setAlignment(Qt.AlignCenter)
        hint_sub.setStyleSheet("color: #888;")
        outer.addWidget(hint_sub)

        outer.addStretch(2)
