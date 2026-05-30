"""APScheduler setup — generates a daily summary via local Ollama."""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta

import requests
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger

from .database import (
    AppRecordMobile,
    AppRecordWindows,
    DailySummary,
    LocationRecord,
    SessionLocal,
)
from .config import get_config

logger = logging.getLogger(__name__)

OLLAMA_MODEL = "qwen2.5:3b"


def _ollama_generate_url() -> str:
    base = get_config().get("ollama_url", "http://localhost:11434").rstrip("/")
    return f"{base}/api/generate"


# Kept for backward compatibility with imports elsewhere.
OLLAMA_URL = _ollama_generate_url()


def _gather_day_data(target: date) -> tuple[str, int]:
    """Pull all records for `target`, return (prompt_text, total_screen_seconds)."""
    start = datetime.combine(target, datetime.min.time())
    end = start + timedelta(days=1)

    with SessionLocal() as db:
        windows = (
            db.query(AppRecordWindows)
            .filter(AppRecordWindows.start_time >= start, AppRecordWindows.start_time < end)
            .order_by(AppRecordWindows.start_time)
            .all()
        )
        mobiles = (
            db.query(AppRecordMobile)
            .filter(AppRecordMobile.start_time >= start, AppRecordMobile.start_time < end)
            .order_by(AppRecordMobile.start_time)
            .all()
        )
        locations = (
            db.query(LocationRecord)
            .filter(LocationRecord.timestamp >= start, LocationRecord.timestamp < end)
            .order_by(LocationRecord.timestamp)
            .all()
        )

    total_screen = 0
    lines: list[str] = []
    lines.append(f"# 今日数据 ({target.isoformat()})")
    lines.append("\n## 电脑窗口活动")
    for w in windows:
        dur = int((w.end_time - w.start_time).total_seconds())
        total_screen += dur
        lines.append(
            f"- [{w.start_time:%H:%M}-{w.end_time:%H:%M}] {w.app_name} | {w.window_title[:80]}"
        )

    lines.append("\n## 手机App使用")
    for m in mobiles:
        dur = int((m.end_time - m.start_time).total_seconds())
        total_screen += dur
        lines.append(
            f"- [{m.start_time:%H:%M}-{m.end_time:%H:%M}] {m.app_name} ({m.package_name})"
        )

    lines.append("\n## 位置轨迹")
    for loc in locations:
        addr = loc.address or f"({loc.latitude:.4f}, {loc.longitude:.4f})"
        lines.append(f"- {loc.timestamp:%H:%M} {addr}")

    return "\n".join(lines), total_screen


def _call_ollama(prompt: str) -> str:
    resp = requests.post(
        _ollama_generate_url(),
        json={"model": OLLAMA_MODEL, "prompt": prompt, "stream": False},
        timeout=120,
    )
    resp.raise_for_status()
    return resp.json().get("response", "").strip()


def generate_summary(target: date | None = None) -> DailySummary:
    target = target or date.today()
    data_text, total_screen = _gather_day_data(target)

    prompt = (
        "你是一个日常追踪助手。下面是用户一天的电脑、手机和位置数据，"
        "请用中文写一段150字左右的总结，包含主要活动、时间分配观察、"
        "以及一个温和的建议。\n\n" + data_text
    )

    try:
        summary_text = _call_ollama(prompt)
    except Exception as e:
        logger.exception("Ollama call failed")
        summary_text = f"[生成失败：{e}]\n\n原始数据：\n{data_text}"

    iso = target.isoformat()
    with SessionLocal() as db:
        existing = db.query(DailySummary).filter_by(date=iso).one_or_none()
        if existing:
            existing.summary_text = summary_text
            existing.total_screen_time = total_screen
            db.commit()
            db.refresh(existing)
            return existing
        record = DailySummary(
            date=iso,
            summary_text=summary_text,
            total_screen_time=total_screen,
        )
        db.add(record)
        db.commit()
        db.refresh(record)
        return record


def start_scheduler() -> BackgroundScheduler:
    sched = BackgroundScheduler(timezone="Asia/Shanghai")
    sched.add_job(
        generate_summary,
        CronTrigger(hour=23, minute=30),
        id="daily_summary",
        replace_existing=True,
    )
    sched.start()
    logger.info("Scheduler started (daily summary at 23:30)")
    return sched
