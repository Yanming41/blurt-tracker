"""时间线页：日期选择 + 手机/电脑混合时间线（带彩色标签）。"""
from __future__ import annotations

from datetime import date, datetime, timedelta

from PySide6.QtCore import QDate, QTimer, Qt
from PySide6.QtWidgets import (
    QDateEdit,
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)

from core.database import (
    AppRecordMobile,
    AppRecordWindows,
    LocationRecord,
    SessionLocal,
)

from .common import source_icon, source_tag_html
from .theme import BORDER, TEXT, TEXT_DIM


REFRESH_INTERVAL_MS = 30_000


class TimelinePage(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(18, 18, 18, 18)
        outer.setSpacing(12)

        header = QHBoxLayout()
        title = QLabel("完整时间线")
        title.setStyleSheet(f"color: {TEXT}; font-size: 15px; font-weight: bold;")
        self.date_edit = QDateEdit()
        self.date_edit.setCalendarPopup(True)
        self.date_edit.setDate(QDate.currentDate())
        self.date_edit.dateChanged.connect(self.refresh)
        refresh_btn = QPushButton("手动刷新")
        refresh_btn.clicked.connect(self.refresh)
        header.addWidget(title)
        header.addStretch(1)
        header.addWidget(QLabel("日期："))
        header.addWidget(self.date_edit)
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

    def _selected_date(self) -> date:
        d = self.date_edit.date()
        return date(d.year(), d.month(), d.day())

    def refresh(self) -> None:
        target = self._selected_date()
        start = datetime.combine(target, datetime.min.time())
        end = start + timedelta(days=1)

        entries: list[tuple[datetime, str, str, str]] = []
        with SessionLocal() as db:
            for w in db.query(AppRecordWindows).filter(
                AppRecordWindows.start_time >= start, AppRecordWindows.start_time < end
            ).all():
                entries.append((w.start_time, "windows", w.app_name, w.window_title))
            for m in db.query(AppRecordMobile).filter(
                AppRecordMobile.start_time >= start, AppRecordMobile.start_time < end
            ).all():
                entries.append((m.start_time, "mobile", m.app_name, m.package_name))
            for loc in db.query(LocationRecord).filter(
                LocationRecord.timestamp >= start, LocationRecord.timestamp < end
            ).all():
                addr = loc.address or f"({loc.latitude:.4f},{loc.longitude:.4f})"
                entries.append((loc.timestamp, "location", addr, ""))

        entries.sort(key=lambda e: e[0])

        # 清空
        while self.layout_v.count() > 0:
            item = self.layout_v.takeAt(0)
            w = item.widget()
            if w is not None:
                w.deleteLater()

        if not entries:
            empty = QLabel("这一天没有记录")
            empty.setStyleSheet(f"color: {TEXT_DIM}; padding: 24px;")
            empty.setAlignment(Qt.AlignCenter)
            self.layout_v.addWidget(empty)
            self.layout_v.addStretch(1)
            return

        for ts, source, content, sub in entries:
            self.layout_v.addWidget(self._row(ts, source, content, sub))
        self.layout_v.addStretch(1)

    @staticmethod
    def _row(ts: datetime, source: str, content: str, sub: str) -> QWidget:
        w = QWidget()
        lay = QHBoxLayout(w)
        lay.setContentsMargins(8, 6, 8, 6)
        lay.setSpacing(10)

        time_lbl = QLabel(ts.strftime("%H:%M"))
        time_lbl.setFixedWidth(50)
        time_lbl.setStyleSheet(f"color: {TEXT_DIM};")

        icon_lbl = QLabel(source_icon(source))
        icon_lbl.setFixedWidth(24)

        tag_lbl = QLabel()
        tag_lbl.setText(source_tag_html(source))
        tag_lbl.setTextFormat(Qt.RichText)
        tag_lbl.setFixedWidth(56)

        text = content if not sub else f"{content}  —  {sub[:80]}"
        content_lbl = QLabel(text)
        content_lbl.setStyleSheet(f"color: {TEXT};")
        content_lbl.setWordWrap(False)
        content_lbl.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)

        lay.addWidget(time_lbl)
        lay.addWidget(icon_lbl)
        lay.addWidget(tag_lbl)
        lay.addWidget(content_lbl, 1)

        # 分割线
        line = QFrame(w)
        line.setFrameShape(QFrame.HLine)
        line.setStyleSheet(f"color: {BORDER};")
        return w
