"""位置记录页 — 今日位置表格。"""
from __future__ import annotations

from datetime import date, datetime, timedelta

from PySide6.QtCore import QTimer, Qt
from PySide6.QtWidgets import QHBoxLayout, QHeaderView, QTableWidgetItem, QVBoxLayout, QWidget
from qfluentwidgets import (
    PrimaryPushButton,
    SubtitleLabel,
    TableWidget,
    TitleLabel,
)

from core.database import LocationRecord, SessionLocal


REFRESH_MS = 30_000


class LocationInterface(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("locationInterface")

        outer = QVBoxLayout(self)
        outer.setContentsMargins(24, 24, 24, 24)
        outer.setSpacing(14)

        outer.addWidget(TitleLabel("位置记录"))

        header = QHBoxLayout()
        header.addWidget(SubtitleLabel("今日位置"))
        header.addStretch(1)
        refresh_btn = PrimaryPushButton("手动刷新")
        refresh_btn.clicked.connect(self.refresh)
        header.addWidget(refresh_btn)
        outer.addLayout(header)

        self.table = TableWidget()
        self.table.setColumnCount(3)
        self.table.setHorizontalHeaderLabels(["时间", "地区", "经纬度"])
        self.table.verticalHeader().hide()
        self.table.setEditTriggers(TableWidget.NoEditTriggers)
        self.table.setSelectionBehavior(TableWidget.SelectRows)
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(0, QHeaderView.ResizeToContents)
        hh.setSectionResizeMode(1, QHeaderView.Stretch)
        hh.setSectionResizeMode(2, QHeaderView.ResizeToContents)
        outer.addWidget(self.table, 1)

        self.empty_lbl = SubtitleLabel("📭 今天还没有位置记录")
        self.empty_lbl.setAlignment(Qt.AlignCenter)
        self.empty_lbl.setStyleSheet("color: #999; padding: 40px;")
        self.empty_lbl.hide()
        outer.addWidget(self.empty_lbl)

        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self._timer.start(REFRESH_MS)
        self.refresh()

    def refresh(self) -> None:
        try:
            self._refresh_inner()
        except Exception:
            pass

    def _refresh_inner(self) -> None:
        today = date.today()
        start = datetime.combine(today, datetime.min.time())
        end = start + timedelta(days=1)
        with SessionLocal() as db:
            locs = (
                db.query(LocationRecord)
                .filter(LocationRecord.timestamp >= start, LocationRecord.timestamp < end)
                .order_by(LocationRecord.timestamp)
                .all()
            )

        if not locs:
            self.table.setRowCount(0)
            self.table.hide()
            self.empty_lbl.show()
            return

        self.empty_lbl.hide()
        self.table.show()
        self.table.setRowCount(len(locs))
        for row, loc in enumerate(locs):
            self.table.setItem(row, 0, QTableWidgetItem(loc.timestamp.strftime("%H:%M")))
            self.table.setItem(row, 1, QTableWidgetItem(loc.address or "—"))
            self.table.setItem(row, 2, QTableWidgetItem(f"{loc.latitude:.5f}, {loc.longitude:.5f}"))
