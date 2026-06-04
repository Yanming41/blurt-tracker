"""今日时间轴页 — 双列时间轴（左手机 / 中时间 / 右电脑）+ 4 张统计卡 + 工具栏。"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from typing import Optional

from PySide6.QtCore import QDate, QThread, QTimer, Qt, Signal
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (
    QFrame,
    QGridLayout,
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
    Dialog,
    HeaderCardWidget,
    PrimaryPushButton,
    PushButton,
    ScrollArea,
    StrongBodyLabel,
    SubtitleLabel,
    TitleLabel,
)

from core.database import (
    AppRecordMobile,
    AppRecordWindows,
    DailySummary,
    LocationRecord,
    MoodRecord,
    ScreenEventMobile,
    SessionLocal,
)
from core.scheduler import generate_summary

from . import notify


REFRESH_MS = 60_000


# ---------- 事件数据结构 ----------

@dataclass
class _Event:
    ts: datetime
    end_ts: Optional[datetime]   # None 表示瞬时事件
    kind: str                    # mobile / windows / location / screen_off / unlock / mood
    title: str
    subtitle: str = ""
    extra: dict | None = None    # 详情对话框用

    @property
    def duration_sec(self) -> int:
        if self.end_ts is None:
            return 0
        return int((self.end_ts - self.ts).total_seconds())


# ---------- 样式 ----------

_PALETTE = {
    "mobile":     ("📱", "#0078d4", "left"),    # 蓝色
    "windows":    ("💻", "#8b5cf6", "right"),   # 紫色
    "location":   ("📍", "#10b981", "full"),    # 绿色
    "screen_off": ("💤", "#9aa0a6", "left"),    # 灰色
    "unlock":     ("🔓", "#f59e0b", "left"),    # 黄色
    "mood":       ("🗑️", "#ef4444", "full"),    # 红色
}


def _format_duration(seconds: int) -> str:
    if seconds < 60:
        return f"{seconds}秒"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes}分钟"
    h, m = divmod(minutes, 60)
    return f"{h}小时{m}分" if m else f"{h}小时"


# ---------- 单个事件卡片 ----------

class _EventCard(CardWidget):
    """时间轴上的一条事件卡片。"""

    clicked_sig = Signal(object)  # 自带 _Event 对象

    def __init__(self, event: _Event, parent=None):
        super().__init__(parent)
        self.event = event
        emoji, color, _side = _PALETTE.get(event.kind, ("•", "#888", "left"))
        # 整体上色 — 左侧加一道彩色边
        self.setStyleSheet(
            f"_EventCard {{ border-left: 4px solid {color}; }}"
        )

        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 8, 12, 8)
        lay.setSpacing(8)

        icon = BodyLabel(emoji)
        icon.setFixedWidth(20)
        lay.addWidget(icon)

        text_col = QVBoxLayout()
        text_col.setContentsMargins(0, 0, 0, 0)
        text_col.setSpacing(0)

        title_lbl = StrongBodyLabel(event.title)
        text_col.addWidget(title_lbl)

        # 副标题：时长 / 子内容
        meta_parts = []
        if event.end_ts:
            meta_parts.append(_format_duration(event.duration_sec))
        if event.subtitle:
            meta_parts.append(event.subtitle[:60])
        if meta_parts:
            sub_lbl = CaptionLabel(" · ".join(meta_parts))
            sub_lbl.setStyleSheet("color: #888;")
            text_col.addWidget(sub_lbl)
        lay.addLayout(text_col, 1)

        self.setCursor(Qt.PointingHandCursor)

    def mousePressEvent(self, ev) -> None:
        self.clicked_sig.emit(self.event)
        super().mousePressEvent(ev)


class _SleepBlock(QFrame):
    """息屏占位块 — 高度按时长拉伸。"""

    def __init__(self, event: _Event, parent=None):
        super().__init__(parent)
        # 时长每分钟 1.5 px，限 40~240
        h = max(40, min(240, int(event.duration_sec / 60 * 1.5)))
        self.setFixedHeight(h)
        self.setStyleSheet(
            "background-color: rgba(154,160,166,40);"
            "border-radius: 8px;"
            "border-left: 4px solid #9aa0a6;"
        )
        lay = QVBoxLayout(self)
        lay.setContentsMargins(12, 8, 12, 8)
        lay.setSpacing(2)
        title = StrongBodyLabel(f"💤 息屏 {_format_duration(event.duration_sec)}")
        title.setStyleSheet("color: #666;")
        sub = CaptionLabel(f"{event.ts:%H:%M} - {event.end_ts:%H:%M}")
        sub.setStyleSheet("color: #888;")
        lay.addWidget(title)
        lay.addWidget(sub)
        lay.addStretch(1)


# ---------- 统计卡 ----------

class _StatCard(CardWidget):
    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.setFixedHeight(86)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 10, 14, 10)
        lay.setSpacing(2)
        self.title_lbl = BodyLabel(title)
        self.title_lbl.setStyleSheet("color: #666;")
        self.value_lbl = TitleLabel("--")
        lay.addWidget(self.title_lbl)
        lay.addWidget(self.value_lbl)

    def set_value(self, value: str) -> None:
        self.value_lbl.setText(value)


# ---------- AI 总结后台线程 ----------

class _SummaryWorker(QThread):
    done = Signal(str)
    failed = Signal(str)

    def __init__(self, target_date: date, parent=None):
        super().__init__(parent)
        self.target_date = target_date

    def run(self) -> None:
        try:
            rec = generate_summary(self.target_date)
            self.done.emit(rec.summary_text)
        except Exception as e:
            self.failed.emit(str(e))


# ---------- 主页面 ----------

class TimelineInterface(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("timelineInterface")

        outer = QVBoxLayout(self)
        outer.setContentsMargins(24, 24, 24, 24)
        outer.setSpacing(14)

        # ===== 标题 =====
        outer.addWidget(TitleLabel("今日时间轴"))

        # ===== 工具栏：日期 + 三按钮 =====
        toolbar = QHBoxLayout()
        toolbar.setSpacing(10)
        toolbar.addWidget(BodyLabel("日期："))
        self.picker = CalendarPicker()
        self.picker.setDate(QDate.currentDate())
        self.picker.dateChanged.connect(lambda *_: self.refresh())
        toolbar.addWidget(self.picker)
        toolbar.addStretch(1)

        self.export_btn = PushButton("导出今日报告")
        self.export_btn.clicked.connect(self._export_report)
        self.gen_btn = PrimaryPushButton("立即生成总结")
        self.gen_btn.clicked.connect(self._generate_summary)
        self.refresh_btn = PushButton("刷新")
        self.refresh_btn.clicked.connect(self.refresh)
        toolbar.addWidget(self.export_btn)
        toolbar.addWidget(self.gen_btn)
        toolbar.addWidget(self.refresh_btn)
        outer.addLayout(toolbar)

        # ===== 4 张统计卡 =====
        cards_row = QHBoxLayout()
        cards_row.setSpacing(12)
        self.card_screen_on = _StatCard("🔓 亮屏次数")
        self.card_screen_time = _StatCard("⏱️ 总屏幕时间")
        self.card_pc_time = _StatCard("💻 电脑使用时长")
        self.card_loc = _StatCard("📍 位置变化次数")
        for c in (self.card_screen_on, self.card_screen_time, self.card_pc_time, self.card_loc):
            cards_row.addWidget(c, 1)
        outer.addLayout(cards_row)

        # ===== 双列时间轴（ScrollArea） =====
        self.scroll = ScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.NoFrame)
        self.scroll.setStyleSheet("QScrollArea { background: transparent; }")

        self.timeline_widget = QWidget()
        self.timeline_widget.setStyleSheet("background: transparent;")
        self.grid = QGridLayout(self.timeline_widget)
        self.grid.setContentsMargins(8, 8, 8, 8)
        self.grid.setHorizontalSpacing(14)
        self.grid.setVerticalSpacing(8)
        self.grid.setColumnStretch(0, 1)   # 左：手机
        self.grid.setColumnMinimumWidth(1, 56)
        self.grid.setColumnStretch(2, 1)   # 右：电脑
        self.scroll.setWidget(self.timeline_widget)
        outer.addWidget(self.scroll, 1)

        # ===== 定时刷新 =====
        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self._timer.start(REFRESH_MS)
        self.refresh()

    # ----------------- 取数 -----------------

    def _selected_date(self) -> date:
        d = self.picker.getDate()
        if d is None or not d.isValid():
            return date.today()
        return date(d.year(), d.month(), d.day())

    def _collect_events(self, target: date) -> tuple[list[_Event], dict]:
        start = datetime.combine(target, time.min)
        end = start + timedelta(days=1)
        start_ts = int(start.timestamp())
        end_ts = int(end.timestamp())

        events: list[_Event] = []
        stats = {"screen_on": 0, "total_screen": 0, "pc_time": 0, "locations": 0}

        with SessionLocal() as db:
            # 手机 App
            for m in db.query(AppRecordMobile).filter(
                AppRecordMobile.start_time >= start, AppRecordMobile.start_time < end
            ).all():
                dur = int((m.end_time - m.start_time).total_seconds())
                stats["total_screen"] += dur
                events.append(_Event(
                    ts=m.start_time, end_ts=m.end_time, kind="mobile",
                    title=m.app_name, subtitle=m.package_name,
                    extra={"包名": m.package_name},
                ))

            # 电脑窗口
            for w in db.query(AppRecordWindows).filter(
                AppRecordWindows.start_time >= start, AppRecordWindows.start_time < end
            ).all():
                dur = int((w.end_time - w.start_time).total_seconds())
                stats["total_screen"] += dur
                stats["pc_time"] += dur
                events.append(_Event(
                    ts=w.start_time, end_ts=w.end_time, kind="windows",
                    title=w.app_name, subtitle=w.window_title,
                    extra={"窗口标题": w.window_title},
                ))

            # 位置
            for loc in db.query(LocationRecord).filter(
                LocationRecord.timestamp >= start, LocationRecord.timestamp < end
            ).all():
                stats["locations"] += 1
                addr = loc.address or f"({loc.latitude:.4f},{loc.longitude:.4f})"
                events.append(_Event(
                    ts=loc.timestamp, end_ts=None, kind="location",
                    title=addr, subtitle="",
                    extra={"经纬度": f"{loc.latitude:.5f}, {loc.longitude:.5f}"},
                ))

            # 屏幕事件 → 配对成息屏区间
            screen_rows = db.query(ScreenEventMobile).filter(
                ScreenEventMobile.timestamp >= start_ts,
                ScreenEventMobile.timestamp < end_ts,
            ).order_by(ScreenEventMobile.timestamp).all()

            off_at: Optional[datetime] = None
            for ev in screen_rows:
                t = datetime.fromtimestamp(ev.timestamp)
                if ev.event_type == "亮屏":
                    stats["screen_on"] += 1
                if ev.event_type == "息屏":
                    off_at = t
                elif ev.event_type in ("亮屏", "解锁"):
                    if off_at is not None and t > off_at:
                        # 形成一个息屏区间
                        events.append(_Event(
                            ts=off_at, end_ts=t, kind="screen_off",
                            title="息屏",
                        ))
                        off_at = None
                    if ev.event_type == "解锁":
                        events.append(_Event(
                            ts=t, end_ts=None, kind="unlock",
                            title="解锁",
                        ))
            # 如果当前还在息屏 — 拉到现在
            if off_at is not None:
                tail = min(datetime.now(), end)
                if tail > off_at:
                    events.append(_Event(
                        ts=off_at, end_ts=tail, kind="screen_off",
                        title="息屏（持续中）",
                    ))

            # 情绪记录
            for mood in db.query(MoodRecord).filter(
                MoodRecord.timestamp >= start, MoodRecord.timestamp < end
            ).all():
                events.append(_Event(
                    ts=mood.timestamp, end_ts=None, kind="mood",
                    title=mood.content[:40], subtitle="",
                    extra={"完整内容": mood.content},
                ))

        events.sort(key=lambda e: e.ts)
        return events, stats

    # ----------------- 渲染 -----------------

    def refresh(self) -> None:
        try:
            target = self._selected_date()
            events, stats = self._collect_events(target)
            self._render_stats(stats)
            self._render_timeline(events)
        except Exception as e:
            notify.error(self, "时间轴刷新失败", str(e), duration=5000)

    def _render_stats(self, stats: dict) -> None:
        self.card_screen_on.set_value(str(stats["screen_on"]))
        self.card_screen_time.set_value(_format_duration(stats["total_screen"]))
        self.card_pc_time.set_value(_format_duration(stats["pc_time"]))
        self.card_loc.set_value(str(stats["locations"]))

    def _render_timeline(self, events: list[_Event]) -> None:
        # 清掉旧 widgets
        while self.grid.count() > 0:
            item = self.grid.takeAt(0)
            w = item.widget()
            if w is not None:
                w.deleteLater()

        if not events:
            empty = SubtitleLabel("📭 这一天还没有任何记录")
            empty.setAlignment(Qt.AlignCenter)
            empty.setStyleSheet("color: #999; padding: 40px;")
            self.grid.addWidget(empty, 0, 0, 1, 3)
            return

        row = 0
        last_hour = -1
        for ev in events:
            hour = ev.ts.hour
            # 整点小标题
            if hour != last_hour:
                hour_lbl = StrongBodyLabel(f"{hour:02d}:00")
                hour_lbl.setAlignment(Qt.AlignCenter)
                hour_lbl.setStyleSheet(
                    "color: #555; padding: 6px 0 2px 0; border-top: 1px solid #eee;"
                )
                self.grid.addWidget(hour_lbl, row, 0, 1, 3)
                row += 1
                last_hour = hour

            # 时间标签（中间）
            time_lbl = CaptionLabel(ev.ts.strftime("%H:%M"))
            time_lbl.setAlignment(Qt.AlignCenter)
            time_lbl.setStyleSheet("color: #888;")

            # 事件 widget
            if ev.kind == "screen_off":
                widget = _SleepBlock(ev)
            else:
                widget = _EventCard(ev)
                widget.clicked_sig.connect(self._show_detail)

            _, _, side = _PALETTE.get(ev.kind, (None, None, "left"))
            if side == "full":
                self.grid.addWidget(widget, row, 0, 1, 3)
            elif side == "left":
                self.grid.addWidget(widget, row, 0)
                self.grid.addWidget(time_lbl, row, 1)
                # 右侧空占位
                placeholder = QWidget(); placeholder.setMinimumHeight(1)
                self.grid.addWidget(placeholder, row, 2)
            else:  # right
                placeholder = QWidget(); placeholder.setMinimumHeight(1)
                self.grid.addWidget(placeholder, row, 0)
                self.grid.addWidget(time_lbl, row, 1)
                self.grid.addWidget(widget, row, 2)
            row += 1

    # ----------------- 详情对话框 -----------------

    def _show_detail(self, ev: _Event) -> None:
        lines = [
            f"时间：{ev.ts:%Y-%m-%d %H:%M:%S}",
        ]
        if ev.end_ts:
            lines.append(f"结束：{ev.end_ts:%H:%M:%S}")
            lines.append(f"时长：{_format_duration(ev.duration_sec)}")
        lines.append(f"类型：{ev.kind}")
        lines.append(f"内容：{ev.title}")
        if ev.subtitle:
            lines.append(f"备注：{ev.subtitle}")
        if ev.extra:
            for k, v in ev.extra.items():
                lines.append(f"{k}：{v}")
        Dialog("事件详情", "\n".join(lines), self).exec()

    # ----------------- 导出 / 生成 -----------------

    def _export_report(self) -> None:
        target = self._selected_date()
        try:
            events, stats = self._collect_events(target)
        except Exception as e:
            notify.error(self, "导出失败", str(e))
            return

        desktop = Path.home() / "Desktop"
        if not desktop.exists():
            desktop = Path.home()
        out = desktop / f"blurt-report-{target.isoformat()}.txt"

        lines = [
            f"# 烂摊子今日报告 — {target.isoformat()}",
            "",
            "## 统计",
            f"- 亮屏次数：{stats['screen_on']}",
            f"- 总屏幕时间：{_format_duration(stats['total_screen'])}",
            f"- 电脑使用时长：{_format_duration(stats['pc_time'])}",
            f"- 位置变化次数：{stats['locations']}",
            "",
            "## 时间轴",
        ]
        for ev in events:
            tail = f" → {ev.end_ts:%H:%M} ({_format_duration(ev.duration_sec)})" if ev.end_ts else ""
            line = f"- {ev.ts:%H:%M}{tail}  [{ev.kind}] {ev.title}"
            if ev.subtitle:
                line += f"  — {ev.subtitle[:80]}"
            lines.append(line)

        try:
            out.write_text("\n".join(lines), encoding="utf-8")
            notify.success(self, "导出完成", f"已保存到：{out}")
        except Exception as e:
            notify.error(self, "导出失败", str(e))

    def _generate_summary(self) -> None:
        target = self._selected_date()
        self.gen_btn.setEnabled(False)
        self.gen_btn.setText("生成中…")
        worker = _SummaryWorker(target, self)
        worker.done.connect(self._on_summary_done)
        worker.failed.connect(self._on_summary_failed)
        worker.finished.connect(worker.deleteLater)
        self._summary_worker = worker
        worker.start()

    def _on_summary_done(self, text: str) -> None:
        self.gen_btn.setEnabled(True)
        self.gen_btn.setText("立即生成总结")
        notify.success(self, "已生成", "今日总结已更新")
        Dialog("今日总结", text, self).exec()

    def _on_summary_failed(self, err: str) -> None:
        self.gen_btn.setEnabled(True)
        self.gen_btn.setText("立即生成总结")
        notify.error(self, "生成失败", err, duration=6000)
