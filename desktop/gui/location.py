"""位置记录页：今日所有位置记录列表。"""
from __future__ import annotations

from datetime import date, datetime, timedelta

from PySide6.QtCore import QTimer, Qt
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)

from core.database import LocationRecord, SessionLocal

from .theme import BORDER, TEXT, TEXT_DIM


REFRESH_INTERVAL_MS = 30_000


class LocationPage(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(18, 18, 18, 18)
        outer.setSpacing(12)

        header = QHBoxLayout()
        title = QLabel("今日位置记录")
        title.setStyleSheet(f"color: {TEXT}; font-size: 15px; font-weight: bold;")
        refresh_btn = QPushButton("手动刷新")
        refresh_btn.clicked.connect(self.refresh)
        header.addWidget(title)
        header.addStretch(1)
        header.addWidget(refresh_btn)
        outer.addLayout(header)

        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.NoFrame)
        self.container = QWidget()
        self.layout_v = QVBoxLayout(self.container)
        self.layout_v.setContentsMargins(0, 0, 0, 0)
        self.layout_v.setSpacing(0)
        self.layout_v.addStretch(1)
        self.scroll.setWidget(self.container)
        outer.addWidget(self.scroll, 1)

        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self._timer.start(REFRESH_INTERVAL_MS)
        self.refresh()

    def refresh(self) -> None:
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

        while self.layout_v.count() > 0:
            item = self.layout_v.takeAt(0)
            w = item.widget()
            if w is not None:
                w.deleteLater()

        if not locs:
            empty = QLabel("今天还没有位置记录")
            empty.setStyleSheet(f"color: {TEXT_DIM}; padding: 24px;")
            empty.setAlignment(Qt.AlignCenter)
            self.layout_v.addWidget(empty)
            self.layout_v.addStretch(1)
            return

        for loc in locs:
            self.layout_v.addWidget(self._row(loc.timestamp, loc.address, loc.latitude, loc.longitude))
        self.layout_v.addStretch(1)

    @staticmethod
    def _row(ts: datetime, address: str | None, lat: float, lng: float) -> QWidget:
        w = QWidget()
        lay = QHBoxLayout(w)
        lay.setContentsMargins(8, 8, 8, 8)
        lay.setSpacing(10)

        time_lbl = QLabel(ts.strftime("%H:%M"))
        time_lbl.setFixedWidth(60)
        time_lbl.setStyleSheet(f"color: {TEXT_DIM};")

        addr_lbl = QLabel(address or "—")
        addr_lbl.setStyleSheet(f"color: {TEXT};")
        addr_lbl.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)

        coord_lbl = QLabel(f"{lat:.5f}, {lng:.5f}")
        coord_lbl.setStyleSheet(f"color: {TEXT_DIM}; font-family: monospace;")
        coord_lbl.setAlignment(Qt.AlignRight | Qt.AlignVCenter)

        lay.addWidget(time_lbl)
        lay.addWidget(addr_lbl, 1)
        lay.addWidget(coord_lbl)
        return w
