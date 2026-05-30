"""时间线页 — 日期选择 + 完整记录（含手机/电脑彩色徽标）。"""
from __future__ import annotations

from datetime import date, datetime, timedelta

from PySide6.QtCore import QDate, QTimer, Qt
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)
from qfluentwidgets import (
    BodyLabel,
    CalendarPicker,
    CaptionLabel,
    CardWidget,
    InfoBadge,
    InfoLevel,
    PrimaryPushButton,
    ScrollArea,
    SubtitleLabel,
    TitleLabel,
)

from core.database import (
    AppRecordMobile,
    AppRecordWindows,
    LocationRecord,
    SessionLocal,
)

from .common import source_icon


REFRESH_MS = 30_000


def _badge_for(source: str) -> InfoBadge:
    if source == "mobile":
        return InfoBadge.info("手机")
    if source == "windows":
        return InfoBadge.success("电脑")
    return InfoBadge.attension("位置") if hasattr(InfoBadge, "attension") else InfoBadge.info("位置")


class _Row(CardWidget):
    def __init__(self, ts: datetime, source: str, content: str, sub: str, parent=None):
        super().__init__(parent)
        self.setFixedHeight(58)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(14, 8, 14, 8)
        lay.setSpacing(10)

        time_lbl = CaptionLabel(ts.strftime("%H:%M"))
        time_lbl.setFixedWidth(48)
        icon_lbl = BodyLabel(source_icon(source))
        icon_lbl.setFixedWidth(22)

        badge = _badge_for(source)

        text = content if not sub else f"{content}  —  {sub[:80]}"
        content_lbl = BodyLabel(text)
        content_lbl.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)

        lay.addWidget(time_lbl)
        lay.addWidget(icon_lbl)
        lay.addWidget(badge)
        lay.addWidget(content_lbl, 1)


class TimelineInterface(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("timelineInterface")

        outer = QVBoxLayout(self)
        outer.setContentsMargins(24, 24, 24, 24)
        outer.setSpacing(14)

        outer.addWidget(TitleLabel("时间线"))

        header = QHBoxLayout()
        header.addWidget(BodyLabel("选择日期："))
        self.picker = CalendarPicker()
        self.picker.setDate(QDate.currentDate())
        self.picker.dateChanged.connect(lambda *_: self.refresh())
        header.addWidget(self.picker)
        header.addStretch(1)
        refresh_btn = PrimaryPushButton("手动刷新")
        refresh_btn.clicked.connect(self.refresh)
        header.addWidget(refresh_btn)
        outer.addLayout(header)

        self.scroll = ScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.NoFrame)
        self.scroll.setStyleSheet("QScrollArea { background: transparent; }")
        self.container = QWidget()
        self.container.setStyleSheet("background: transparent;")
        self.layout_v = QVBoxLayout(self.container)
        self.layout_v.setContentsMargins(0, 0, 0, 0)
        self.layout_v.setSpacing(6)
        self.layout_v.addStretch(1)
        self.scroll.setWidget(self.container)
        outer.addWidget(self.scroll, 1)

        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self._timer.start(REFRESH_MS)
        self.refresh()

    def _selected_date(self) -> date:
        d = self.picker.getDate()
        if d is None or not d.isValid():
            return date.today()
        return date(d.year(), d.month(), d.day())

    def refresh(self) -> None:
        try:
            self._refresh_inner()
        except Exception:
            # 不让 GUI 因为查库失败而崩
            pass

    def _refresh_inner(self) -> None:
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

        while self.layout_v.count() > 0:
            item = self.layout_v.takeAt(0)
            w = item.widget()
            if w is not None:
                w.deleteLater()

        if not entries:
            empty = SubtitleLabel("📭 这一天还没有记录")
            empty.setAlignment(Qt.AlignCenter)
            empty.setStyleSheet("color: #999; padding: 40px;")
            self.layout_v.addWidget(empty)
            self.layout_v.addStretch(1)
            return

        for ts, source, content, sub in entries:
            self.layout_v.addWidget(_Row(ts, source, content, sub))
        self.layout_v.addStretch(1)
