"""设置页。"""
from __future__ import annotations

import time
from datetime import date, datetime, timedelta

import requests
from PySide6.QtCore import QThread, Signal, Qt
from PySide6.QtWidgets import (
    QFormLayout,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from core.config import get_config, update_config
from core.database import (
    AppRecordMobile,
    AppRecordWindows,
    LocationRecord,
    SessionLocal,
)
from core.scheduler import generate_summary

from .theme import TEXT, TEXT_DIM


class _SummaryWorker(QThread):
    done = Signal(str)
    failed = Signal(str)

    def run(self) -> None:
        try:
            rec = generate_summary(date.today())
            self.done.emit(rec.summary_text)
        except Exception as e:
            self.failed.emit(str(e))


class SettingsPage(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(18, 18, 18, 18)
        outer.setSpacing(14)

        title = QLabel("设置")
        title.setStyleSheet(f"color: {TEXT}; font-size: 15px; font-weight: bold;")
        outer.addWidget(title)

        cfg = get_config()

        form_card = QFrame(); form_card.setObjectName("Card")
        form = QFormLayout(form_card)
        form.setContentsMargins(16, 16, 16, 16)
        form.setSpacing(10)

        # 手机 IP + 测试
        ip_row = QHBoxLayout()
        self.phone_ip_edit = QLineEdit(cfg.get("phone_ip", ""))
        self.phone_ip_edit.setPlaceholderText("100.x.y.z")
        self.test_btn = QPushButton("测试连接")
        self.test_btn.clicked.connect(self._test_phone)
        ip_row.addWidget(self.phone_ip_edit, 1)
        ip_row.addWidget(self.test_btn)
        ip_widget = QWidget(); ip_widget.setLayout(ip_row)
        form.addRow("手机IP地址：", ip_widget)

        # Ollama 地址
        self.ollama_edit = QLineEdit(cfg.get("ollama_url", "http://localhost:11434"))
        form.addRow("Ollama地址：", self.ollama_edit)

        # Ollama 模型
        self.ollama_model_edit = QLineEdit(cfg.get("ollama_model", "qwen2.5:3b"))
        form.addRow("Ollama模型：", self.ollama_model_edit)

        # 数据保留天数
        self.retention_spin = QSpinBox()
        self.retention_spin.setRange(1, 3650)
        self.retention_spin.setValue(int(cfg.get("retention_days", 30)))
        self.retention_spin.setSuffix(" 天")
        form.addRow("数据保留天数：", self.retention_spin)

        outer.addWidget(form_card)

        # 按钮区
        btn_row = QHBoxLayout()
        save_btn = QPushButton("保存设置")
        save_btn.setProperty("accent", True)
        save_btn.clicked.connect(self._save)
        clear_btn = QPushButton("清除今日数据")
        clear_btn.clicked.connect(self._clear_today)
        gen_btn = QPushButton("立即生成总结")
        gen_btn.clicked.connect(self._generate_summary)
        btn_row.addWidget(save_btn)
        btn_row.addWidget(clear_btn)
        btn_row.addWidget(gen_btn)
        btn_row.addStretch(1)
        outer.addLayout(btn_row)

        # 测试结果提示
        self.status_lbl = QLabel("")
        self.status_lbl.setStyleSheet(f"color: {TEXT_DIM};")
        outer.addWidget(self.status_lbl)

        outer.addStretch(1)

    # ---------- 操作 ----------

    def _save(self) -> None:
        update_config(
            phone_ip=self.phone_ip_edit.text().strip(),
            ollama_url=self.ollama_edit.text().strip() or "http://localhost:11434",
            ollama_model=self.ollama_model_edit.text().strip() or "qwen2.5:3b",
            retention_days=int(self.retention_spin.value()),
        )
        QMessageBox.information(self, "已保存", "设置已保存。")

    def _test_phone(self) -> None:
        ip = self.phone_ip_edit.text().strip()
        if not ip:
            self.status_lbl.setText("请先填写手机IP")
            return
        cfg = get_config()
        port = cfg.get("phone_port", 8000)
        url = f"http://{ip}:{port}/ping"
        self.status_lbl.setText("正在测试…")
        t0 = time.perf_counter()
        try:
            r = requests.get(url, timeout=3)
            r.raise_for_status()
            dt = int((time.perf_counter() - t0) * 1000)
            self.status_lbl.setText(f"✅ 连接成功 ({dt}ms) — {r.json()}")
        except requests.RequestException as e:
            tip = (
                "❌ 连接失败：\n"
                "  1. 确认手机端App正在运行\n"
                "  2. 确认两台设备都开启了Tailscale\n"
                "  3. 确认IP地址填写正确\n"
                "  4. 检查手机防火墙是否放行端口\n"
                f"  错误信息：{e}"
            )
            self.status_lbl.setText(tip)

    def _clear_today(self) -> None:
        ret = QMessageBox.question(
            self, "确认", "确定要清除今日所有数据吗？此操作不可撤销。",
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No,
        )
        if ret != QMessageBox.Yes:
            return
        today = date.today()
        start = datetime.combine(today, datetime.min.time())
        end = start + timedelta(days=1)
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
        QMessageBox.information(self, "完成", "今日数据已清除。")

    def _generate_summary(self) -> None:
        self.status_lbl.setText("正在生成今日总结，请稍候…")
        worker = _SummaryWorker(self)
        worker.done.connect(lambda txt: self.status_lbl.setText("✅ 总结已生成"))
        worker.failed.connect(lambda err: self.status_lbl.setText(f"❌ 生成失败：{err}"))
        worker.finished.connect(worker.deleteLater)
        self._worker = worker
        worker.start()
