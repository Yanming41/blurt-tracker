"""今日概览页：4 张统计卡 + 按小时分组的时间线 + AI 总结。"""
from __future__ import annotations

from datetime import date, datetime, timedelta
from itertools import groupby
from typing import Iterable

from PySide6.QtCore import QThread, QTimer, Qt, Signal
from PySide6.QtWidgets import (
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)
from sqlalchemy import func

from core.database import (
    AppRecordMobile,
    AppRecordWindows,
    DailySummary,
    LocationRecord,
    SessionLocal,
)
from core.scheduler import generate_summary

from .common import StatCard, format_duration, source_icon, source_tag_html
from .theme import BORDER, CARD_BG, TEXT, TEXT_DIM


REFRESH_INTERVAL_MS = 30_000


class _SummaryWorker(QThread):
    done = Signal(str)
    failed = Signal(str)

    def run(self) -> None:
        try:
            rec = generate_summary(date.today())
            self.done.emit(rec.summary_text)
        except Exception as e:
            self.failed.emit(str(e))


class DashboardPage(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(18, 18, 18, 18)
        outer.setSpacing(14)

        # ----- 顶部统计卡片 -----
        cards_row = QHBoxLayout()
        cards_row.setSpacing(12)
        self.card_screen = StatCard("今日屏幕时间")
        self.card_apps = StatCard("使用App数量")
        self.card_locs = StatCard("位置记录条数")
        self.card_tasks = StatCard("任务完成数")
        for c in (self.card_screen, self.card_apps, self.card_locs, self.card_tasks):
            cards_row.addWidget(c, 1)
        outer.addLayout(cards_row)

        # ----- 中间时间线 -----
        timeline_header = QHBoxLayout()
        title = QLabel("今日时间线")
        title.setStyleSheet(f"color: {TEXT}; font-size: 15px; font-weight: bold;")
        refresh_btn = QPushButton("手动刷新")
        refresh_btn.clicked.connect(self.refresh)
        timeline_header.addWidget(title)
        timeline_header.addStretch(1)
        timeline_header.addWidget(refresh_btn)
        outer.addLayout(timeline_header)

        self.timeline_scroll = QScrollArea()
        self.timeline_scroll.setWidgetResizable(True)
        self.timeline_scroll.setFrameShape(QFrame.NoFrame)
        self.timeline_container = QWidget()
        self.timeline_layout = QVBoxLayout(self.timeline_container)
        self.timeline_layout.setContentsMargins(0, 0, 0, 0)
        self.timeline_layout.setSpacing(0)
        self.timeline_layout.addStretch(1)
        self.timeline_scroll.setWidget(self.timeline_container)
        outer.addWidget(self.timeline_scroll, 1)

        # ----- 底部总结 -----
        sum_header = QHBoxLayout()
        sum_title = QLabel("今日总结")
        sum_title.setStyleSheet(f"color: {TEXT}; font-size: 15px; font-weight: bold;")
        self.gen_btn = QPushButton("立即生成")
        self.gen_btn.setProperty("accent", True)
        self.gen_btn.clicked.connect(self._generate_now)
        sum_header.addWidget(sum_title)
        sum_header.addStretch(1)
        sum_header.addWidget(self.gen_btn)
        outer.addLayout(sum_header)

        self.summary_view = QTextEdit()
        self.summary_view.setReadOnly(True)
        self.summary_view.setFixedHeight(140)
        outer.addWidget(self.summary_view)

        # ----- 定时刷新 -----
        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self._timer.start(REFRESH_INTERVAL_MS)
        self.refresh()

    # ---------- 数据加载 ----------

    def refresh(self) -> None:
        today = date.today()
        start = datetime.combine(today, datetime.min.time())
        end = start + timedelta(days=1)

        with SessionLocal() as db:
            windows = db.query(AppRecordWindows).filter(
                AppRecordWindows.start_time >= start, AppRecordWindows.start_time < end
            ).all()
            mobiles = db.query(AppRecordMobile).filter(
                AppRecordMobile.start_time >= start, AppRecordMobile.start_time < end
            ).all()
            locs = db.query(LocationRecord).filter(
                LocationRecord.timestamp >= start, LocationRecord.timestamp < end
            ).all()
            summary = db.query(DailySummary).filter_by(date=today.isoformat()).one_or_none()

        # 卡片数据
        total_secs = sum(int((w.end_time - w.start_time).total_seconds()) for w in windows) \
                   + sum(int((m.end_time - m.start_time).total_seconds()) for m in mobiles)
        self.card_screen.set_value(format_duration(total_secs))
        unique_apps = {w.app_name for w in windows} | {m.package_name for m in mobiles}
        self.card_apps.set_value(str(len(unique_apps)))
        self.card_locs.set_value(str(len(locs)))
        self.card_tasks.set_value("--")  # 任务模块尚未实现

        # 时间线
        entries = []
        for w in windows:
            entries.append((w.start_time, "windows", w.app_name,
                            format_duration(int((w.end_time - w.start_time).total_seconds()))))
        for m in mobiles:
            entries.append((m.start_time, "mobile", m.app_name,
                            format_duration(int((m.end_time - m.start_time).total_seconds()))))
        for loc in locs:
            entries.append((loc.timestamp, "location",
                            loc.address or f"({loc.latitude:.3f},{loc.longitude:.3f})", ""))
        entries.sort(key=lambda e: e[0])
        self._render_timeline(entries)

        # 总结
        if summary:
            self.summary_view.setPlainText(summary.summary_text)
        else:
            self.summary_view.setPlainText("今日总结将在23:30生成")

    def _render_timeline(self, entries: list[tuple[datetime, str, str, str]]) -> None:
        # 清掉旧内容
        while self.timeline_layout.count() > 0:
            item = self.timeline_layout.takeAt(0)
            w = item.widget()
            if w is not None:
                w.deleteLater()

        if not entries:
            empty = QLabel("今天还没有记录")
            empty.setStyleSheet(f"color: {TEXT_DIM}; padding: 24px;")
            empty.setAlignment(Qt.AlignCenter)
            self.timeline_layout.addWidget(empty)
            self.timeline_layout.addStretch(1)
            return

        for hour, group in groupby(entries, key=lambda e: e[0].hour):
            header = QLabel(f"{hour:02d}:00")
            header.setStyleSheet(
                f"color: {TEXT_DIM}; font-size: 12px; font-weight: bold;"
                f"padding: 8px 4px 4px 4px; border-top: 1px solid {BORDER};"
            )
            self.timeline_layout.addWidget(header)
            for ts, source, content, duration in group:
                self.timeline_layout.addWidget(self._entry_row(ts, source, content, duration))
        self.timeline_layout.addStretch(1)

    @staticmethod
    def _entry_row(ts: datetime, source: str, content: str, duration: str) -> QWidget:
        w = QWidget()
        lay = QHBoxLayout(w)
        lay.setContentsMargins(8, 6, 8, 6)
        lay.setSpacing(10)

        time_lbl = QLabel(ts.strftime("%H:%M"))
        time_lbl.setFixedWidth(48)
        time_lbl.setStyleSheet(f"color: {TEXT_DIM};")

        icon_lbl = QLabel(source_icon(source))
        icon_lbl.setFixedWidth(24)

        content_lbl = QLabel(content)
        content_lbl.setStyleSheet(f"color: {TEXT};")
        content_lbl.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)

        dur_lbl = QLabel(duration)
        dur_lbl.setStyleSheet(f"color: {TEXT_DIM};")
        dur_lbl.setAlignment(Qt.AlignRight | Qt.AlignVCenter)

        lay.addWidget(time_lbl)
        lay.addWidget(icon_lbl)
        lay.addWidget(content_lbl, 1)
        lay.addWidget(dur_lbl)
        return w

    # ---------- 生成总结 ----------

    def _generate_now(self) -> None:
        self.gen_btn.setEnabled(False)
        self.gen_btn.setText("生成中…")
        self.summary_view.setPlainText("正在调用 Ollama 生成总结，请稍候…")
        worker = _SummaryWorker(self)
        worker.done.connect(self._on_summary_done)
        worker.failed.connect(self._on_summary_failed)
        worker.finished.connect(worker.deleteLater)
        self._summary_worker = worker  # 保持引用
        worker.start()

    def _on_summary_done(self, text: str) -> None:
        self.summary_view.setPlainText(text)
        self.gen_btn.setEnabled(True)
        self.gen_btn.setText("立即生成")

    def _on_summary_failed(self, err: str) -> None:
        QMessageBox.warning(self, "生成失败", err)
        self.gen_btn.setEnabled(True)
        self.gen_btn.setText("立即生成")
