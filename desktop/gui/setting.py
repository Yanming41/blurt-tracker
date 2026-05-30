"""设置页 — Fluent 风格 SettingCardGroup 分组。"""
from __future__ import annotations

import time
from datetime import date, datetime, timedelta

import requests
from PySide6.QtCore import QThread, Signal, Qt
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)
from qfluentwidgets import (
    BodyLabel,
    CaptionLabel,
    HeaderCardWidget,
    InfoBar,
    InfoBarPosition,
    LineEdit,
    PrimaryPushButton,
    PushButton,
    ScrollArea,
    SettingCardGroup,
    SpinBox,
    StrongBodyLabel,
    SubtitleLabel,
    TitleLabel,
)

from core.api import get_lan_ip, get_tailscale_ip, monitor
from core.config import get_config, update_config
from core.database import (
    AppRecordMobile,
    AppRecordWindows,
    LocationRecord,
    SessionLocal,
)
from core.scheduler import generate_summary


class _SummaryWorker(QThread):
    done = Signal(str)
    failed = Signal(str)

    def run(self) -> None:
        try:
            rec = generate_summary(date.today())
            self.done.emit(rec.summary_text)
        except Exception as e:
            self.failed.emit(str(e))


class _LabeledRow(QWidget):
    """简易的 label + 控件 + (可选)按钮 行。"""

    def __init__(self, label: str, widget: QWidget, btn: QWidget | None = None, parent=None):
        super().__init__(parent)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(16, 10, 16, 10)
        lay.setSpacing(12)
        lbl = BodyLabel(label)
        lbl.setFixedWidth(120)
        lay.addWidget(lbl)
        lay.addWidget(widget, 1)
        if btn is not None:
            lay.addWidget(btn)


class SettingInterface(ScrollArea):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("settingInterface")
        self.setWidgetResizable(True)
        self.setFrameShape(QFrame.NoFrame)
        self.setStyleSheet("QScrollArea { background: transparent; }")

        root = QWidget()
        root.setStyleSheet("background: transparent;")
        self.setWidget(root)

        outer = QVBoxLayout(root)
        outer.setContentsMargins(24, 24, 24, 24)
        outer.setSpacing(18)

        outer.addWidget(TitleLabel("设置"))

        cfg = get_config()

        # ----- 设备连接 -----
        conn_card = HeaderCardWidget()
        conn_card.setTitle("📱 设备连接")
        conn_layout = QVBoxLayout()
        conn_layout.setContentsMargins(0, 0, 0, 0)
        conn_layout.setSpacing(2)

        self.phone_ip_edit = LineEdit()
        self.phone_ip_edit.setText(cfg.get("phone_ip", ""))
        self.phone_ip_edit.setPlaceholderText("100.x.y.z")
        self.test_btn = PrimaryPushButton("测试连接")
        self.test_btn.clicked.connect(self._test_phone)
        conn_layout.addWidget(_LabeledRow("手机 IP：", self.phone_ip_edit, self.test_btn))

        self.test_status_lbl = CaptionLabel("")
        self.test_status_lbl.setContentsMargins(16, 0, 16, 8)
        conn_layout.addWidget(self.test_status_lbl)

        self.ollama_edit = LineEdit()
        self.ollama_edit.setText(cfg.get("ollama_url", "http://localhost:11434"))
        conn_layout.addWidget(_LabeledRow("Ollama 地址：", self.ollama_edit))

        self.ollama_model_edit = LineEdit()
        self.ollama_model_edit.setText(cfg.get("ollama_model", "qwen2.5:3b"))
        conn_layout.addWidget(_LabeledRow("Ollama 模型：", self.ollama_model_edit))

        conn_card.viewLayout.addLayout(conn_layout)
        outer.addWidget(conn_card)

        # ----- 数据管理 -----
        data_card = HeaderCardWidget()
        data_card.setTitle("💾 数据管理")
        data_layout = QVBoxLayout()
        data_layout.setContentsMargins(0, 0, 0, 0)
        data_layout.setSpacing(2)

        self.retention_spin = SpinBox()
        self.retention_spin.setRange(1, 3650)
        self.retention_spin.setValue(int(cfg.get("retention_days", 30)))
        self.retention_spin.setSuffix(" 天")
        data_layout.addWidget(_LabeledRow("数据保留天数：", self.retention_spin))

        btn_row = QHBoxLayout()
        btn_row.setContentsMargins(16, 8, 16, 8)
        btn_row.setSpacing(10)
        clear_btn = PushButton("清除今日数据")
        clear_btn.clicked.connect(self._clear_today)
        gen_btn = PrimaryPushButton("立即生成总结")
        gen_btn.clicked.connect(self._generate_summary)
        save_btn = PrimaryPushButton("保存设置")
        save_btn.clicked.connect(self._save)
        btn_row.addWidget(save_btn)
        btn_row.addWidget(clear_btn)
        btn_row.addWidget(gen_btn)
        btn_row.addStretch(1)
        wrapper = QWidget(); wrapper.setLayout(btn_row)
        data_layout.addWidget(wrapper)

        data_card.viewLayout.addLayout(data_layout)
        outer.addWidget(data_card)

        # ----- 系统信息 -----
        info_card = HeaderCardWidget()
        info_card.setTitle("ℹ️ 系统信息")
        info_layout = QVBoxLayout()
        info_layout.setContentsMargins(0, 0, 0, 0)
        info_layout.setSpacing(2)

        ts_ip = get_tailscale_ip() or "（未检测到 Tailscale）"
        lan_ip = get_lan_ip()
        info_layout.addWidget(_LabeledRow("Tailscale IP：", BodyLabel(ts_ip)))
        info_layout.addWidget(_LabeledRow("局域网 IP：", BodyLabel(lan_ip)))
        self.svc_status_lbl = BodyLabel("--")
        info_layout.addWidget(_LabeledRow("服务状态：", self.svc_status_lbl))
        info_card.viewLayout.addLayout(info_layout)
        outer.addWidget(info_card)

        outer.addStretch(1)

        self._refresh_status()

    # ---------- helpers ----------

    def _refresh_status(self) -> None:
        running = monitor.is_running()
        self.svc_status_lbl.setText(
            "✅ FastAPI + 监控线程运行中" if running else "⚠️ 监控线程未运行"
        )

    def _save(self) -> None:
        update_config(
            phone_ip=self.phone_ip_edit.text().strip(),
            ollama_url=self.ollama_edit.text().strip() or "http://localhost:11434",
            ollama_model=self.ollama_model_edit.text().strip() or "qwen2.5:3b",
            retention_days=int(self.retention_spin.value()),
        )
        InfoBar.success("已保存", "设置已写入 config.json",
                        duration=2500, position=InfoBarPosition.TOP, parent=self)

    def _test_phone(self) -> None:
        ip = self.phone_ip_edit.text().strip()
        if not ip:
            self.test_status_lbl.setText("请先填写手机 IP")
            return
        cfg = get_config()
        port = cfg.get("phone_port", 8000)
        url = f"http://{ip}:{port}/ping"
        self.test_status_lbl.setText("正在测试…")
        t0 = time.perf_counter()
        try:
            r = requests.get(url, timeout=3)
            r.raise_for_status()
            dt = int((time.perf_counter() - t0) * 1000)
            self.test_status_lbl.setText(f"✅ 连接成功 ({dt}ms)")
            InfoBar.success("连接成功", f"手机响应 {dt}ms",
                            duration=2500, position=InfoBarPosition.TOP, parent=self)
        except requests.RequestException as e:
            tip = "❌ 连接失败 — 请检查：手机程序在运行、两端Tailscale开启、IP填写正确"
            self.test_status_lbl.setText(tip)
            InfoBar.error("连接失败", str(e), duration=4000,
                          position=InfoBarPosition.TOP, parent=self)

    def _clear_today(self) -> None:
        today = date.today()
        start = datetime.combine(today, datetime.min.time())
        end = start + timedelta(days=1)
        try:
            with SessionLocal() as db:
                db.query(AppRecordWindows).filter(
                    AppRecordWindows.start_time >= start, AppRecordWindows.start_time < end
                ).delete(synchronize_session=False)
                db.query(AppRecordMobile).filter(
                    AppRecordMobile.start_time >= start, AppRecordMobile.start_time < end
                ).delete(synchronize_session=False)
                db.query(LocationRecord).filter(
                    LocationRecord.timestamp >= start, LocationRecord.timestamp < end
                ).delete(synchronize_session=False)
                db.commit()
            InfoBar.success("已清除", "今日数据已删除", duration=2500,
                            position=InfoBarPosition.TOP, parent=self)
        except Exception as e:
            InfoBar.error("清除失败", str(e), duration=4000,
                          position=InfoBarPosition.TOP, parent=self)

    def _generate_summary(self) -> None:
        InfoBar.info("生成中", "正在调用 Ollama，请稍候…",
                     duration=2000, position=InfoBarPosition.TOP, parent=self)
        worker = _SummaryWorker(self)
        worker.done.connect(lambda _: InfoBar.success(
            "已生成", "今日总结已更新", duration=2500,
            position=InfoBarPosition.TOP, parent=self
        ))
        worker.failed.connect(lambda err: InfoBar.error(
            "生成失败", err, duration=4000,
            position=InfoBarPosition.TOP, parent=self
        ))
        worker.finished.connect(worker.deleteLater)
        self._worker = worker
        worker.start()
