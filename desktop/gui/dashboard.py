"""今日概览页 — Fluent 风格统计卡 + 时间线 + AI 总结。"""
from __future__ import annotations

import time
from datetime import date, datetime, timedelta
from itertools import groupby
from typing import Optional

import requests
from PySide6.QtCore import QThread, QTimer, Qt, Signal
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QSizePolicy,
    QSpacerItem,
    QVBoxLayout,
    QWidget,
)
from qfluentwidgets import (
    BodyLabel,
    CaptionLabel,
    CardWidget,
    InfoBar,
    InfoBarPosition,
    PrimaryPushButton,
    ScrollArea,
    StrongBodyLabel,
    SubtitleLabel,
    TitleLabel,
)

from core.config import get_config
from core.database import (
    AppRecordMobile,
    AppRecordWindows,
    DailySummary,
    LocationRecord,
    SessionLocal,
)
from core.scheduler import generate_summary

from .common import format_duration, source_icon


REFRESH_MS = 30_000


class _SummaryWorker(QThread):
    done = Signal(str)
    failed = Signal(str)

    def run(self) -> None:
        try:
            rec = generate_summary(date.today())
            self.done.emit(rec.summary_text)
        except Exception as e:
            self.failed.emit(str(e))


class _StatCard(CardWidget):
    """顶部 4 张统计卡之一。"""

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.setFixedHeight(96)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 12, 16, 12)
        lay.setSpacing(2)
        self.title_lbl = BodyLabel(title)
        self.title_lbl.setStyleSheet("color: #666;")
        self.value_lbl = TitleLabel("--")
        self.sub_lbl = CaptionLabel("")
        self.sub_lbl.setStyleSheet("color: #888;")
        lay.addWidget(self.title_lbl)
        lay.addWidget(self.value_lbl)
        lay.addWidget(self.sub_lbl)

    def set_value(self, value: str, sub: str = "") -> None:
        self.value_lbl.setText(value)
        self.sub_lbl.setText(sub)


class _EntryCard(CardWidget):
    """时间线里的一条记录。"""

    def __init__(self, ts: datetime, source: str, content: str, duration: str, parent=None):
        super().__init__(parent)
        self.setFixedHeight(52)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(14, 8, 14, 8)
        lay.setSpacing(12)

        time_lbl = CaptionLabel(ts.strftime("%H:%M"))
        time_lbl.setFixedWidth(44)
        icon_lbl = BodyLabel(source_icon(source))
        icon_lbl.setFixedWidth(22)
        content_lbl = BodyLabel(content)
        content_lbl.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        dur_lbl = CaptionLabel(duration)
        dur_lbl.setStyleSheet("color: #666;")
        dur_lbl.setAlignment(Qt.AlignRight | Qt.AlignVCenter)

        lay.addWidget(time_lbl)
        lay.addWidget(icon_lbl)
        lay.addWidget(content_lbl, 1)
        lay.addWidget(dur_lbl)


class DashboardInterface(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("dashboardInterface")

        outer = QVBoxLayout(self)
        outer.setContentsMargins(24, 24, 24, 24)
        outer.setSpacing(16)

        outer.addWidget(TitleLabel("今日概览"))

        # ---- 顶部统计卡 ----
        cards_row = QHBoxLayout()
        cards_row.setSpacing(12)
        self.card_phone = _StatCard("📱 手机")
        self.card_screen = _StatCard("⏱️ 屏幕时间")
        self.card_loc = _StatCard("📍 位置")
        self.card_task = _StatCard("✅ 任务")
        for c in (self.card_phone, self.card_screen, self.card_loc, self.card_task):
            cards_row.addWidget(c, 1)
        outer.addLayout(cards_row)

        # ---- 时间线 ----
        timeline_header = QHBoxLayout()
        timeline_header.addWidget(SubtitleLabel("今日时间线"))
        timeline_header.addStretch(1)
        refresh_btn = PrimaryPushButton("手动刷新")
        refresh_btn.clicked.connect(self.refresh)
        timeline_header.addWidget(refresh_btn)
        outer.addLayout(timeline_header)

        self.scroll = ScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.NoFrame)
        self.scroll.setStyleSheet("QScrollArea { background: transparent; }")
        self.timeline_container = QWidget()
        self.timeline_container.setStyleSheet("background: transparent;")
        self.timeline_layout = QVBoxLayout(self.timeline_container)
        self.timeline_layout.setContentsMargins(0, 0, 0, 0)
        self.timeline_layout.setSpacing(6)
        self.timeline_layout.addStretch(1)
        self.scroll.setWidget(self.timeline_container)
        outer.addWidget(self.scroll, 1)

        # ---- 底部总结卡 ----
        self.summary_card = CardWidget()
        sc_lay = QVBoxLayout(self.summary_card)
        sc_lay.setContentsMargins(18, 14, 18, 14)
        sc_lay.setSpacing(8)
        sum_header = QHBoxLayout()
        sum_header.addWidget(StrongBodyLabel("🤖 今日总结"))
        sum_header.addStretch(1)
        self.gen_btn = PrimaryPushButton("立即生成")
        self.gen_btn.clicked.connect(self._generate_now)
        sum_header.addWidget(self.gen_btn)
        sc_lay.addLayout(sum_header)
        self.summary_lbl = BodyLabel("今日总结将在23:30自动生成")
        self.summary_lbl.setWordWrap(True)
        self.summary_lbl.setMinimumHeight(60)
        sc_lay.addWidget(self.summary_lbl)
        outer.addWidget(self.summary_card)

        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self._timer.start(REFRESH_MS)
        self.refresh()

    # ---------- 加载 ----------

    def refresh(self) -> None:
        try:
            self._refresh_inner()
        except Exception as e:
            InfoBar.error(
                title="刷新失败",
                content=str(e),
                duration=3000,
                position=InfoBarPosition.TOP,
                parent=self,
            )

    def _refresh_inner(self) -> None:
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

        # 顶部卡片
        total = sum(int((w.end_time - w.start_time).total_seconds()) for w in windows) \
              + sum(int((m.end_time - m.start_time).total_seconds()) for m in mobiles)
        self.card_screen.set_value(format_duration(total))
        self.card_loc.set_value(str(len(locs)), "条记录")
        self.card_task.set_value("0/0", "暂未实现")

        # 手机卡：用 status 接口探测
        cfg = get_config()
        ip = cfg.get("phone_ip", "").strip()
        if not ip:
            self.card_phone.set_value("未配置", "请到设置页填写IP")
        else:
            try:
                t0 = time.perf_counter()
                r = requests.get(f"http://{ip}:{cfg.get('phone_port', 8000)}/ping", timeout=3)
                r.raise_for_status()
                dt = int((time.perf_counter() - t0) * 1000)
                self.card_phone.set_value("✅ 在线", f"{dt}ms")
            except requests.RequestException:
                self.card_phone.set_value("● 离线", "未连接到手机")

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
            self.summary_lbl.setText(summary.summary_text)
        else:
            self.summary_lbl.setText("今日总结将在23:30自动生成")

    def _render_timeline(self, entries: list[tuple[datetime, str, str, str]]) -> None:
        while self.timeline_layout.count() > 0:
            item = self.timeline_layout.takeAt(0)
            w = item.widget()
            if w is not None:
                w.deleteLater()

        if not entries:
            empty = SubtitleLabel("📭 今天还没有记录")
            empty.setAlignment(Qt.AlignCenter)
            empty.setStyleSheet("color: #999; padding: 40px;")
            self.timeline_layout.addWidget(empty)
            self.timeline_layout.addStretch(1)
            return

        for hour, group in groupby(entries, key=lambda e: e[0].hour):
            hdr = StrongBodyLabel(f"{hour:02d}:00")
            hdr.setStyleSheet("color: #555; padding: 8px 2px 4px 2px;")
            self.timeline_layout.addWidget(hdr)
            for ts, source, content, duration in group:
                self.timeline_layout.addWidget(_EntryCard(ts, source, content, duration))
        self.timeline_layout.addStretch(1)

    # ---------- 生成总结 ----------

    def _generate_now(self) -> None:
        self.gen_btn.setEnabled(False)
        self.gen_btn.setText("生成中…")
        self.summary_lbl.setText("正在调用 Ollama 生成总结，请稍候…")
        worker = _SummaryWorker(self)
        worker.done.connect(self._on_done)
        worker.failed.connect(self._on_failed)
        worker.finished.connect(worker.deleteLater)
        self._worker = worker
        worker.start()

    def _on_done(self, text: str) -> None:
        self.summary_lbl.setText(text)
        self.gen_btn.setEnabled(True)
        self.gen_btn.setText("立即生成")
        InfoBar.success("已生成", "今日总结已更新", duration=2500,
                        position=InfoBarPosition.TOP, parent=self)

    def _on_failed(self, err: str) -> None:
        self.gen_btn.setEnabled(True)
        self.gen_btn.setText("立即生成")
        InfoBar.error("生成失败", err, duration=4000,
                      position=InfoBarPosition.TOP, parent=self)
