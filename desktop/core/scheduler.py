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
    MoodRecord,
    ScreenEventMobile,
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
    """Pull all records for `target`, return (prompt_body, total_screen_seconds)."""
    start = datetime.combine(target, datetime.min.time())
    end = start + timedelta(days=1)
    start_ts = int(start.timestamp())
    end_ts = int(end.timestamp())

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
        screen_events = (
            db.query(ScreenEventMobile)
            .filter(ScreenEventMobile.timestamp >= start_ts, ScreenEventMobile.timestamp < end_ts)
            .order_by(ScreenEventMobile.timestamp)
            .all()
        )
        moods = (
            db.query(MoodRecord)
            .filter(MoodRecord.timestamp >= start, MoodRecord.timestamp < end)
            .order_by(MoodRecord.timestamp)
            .all()
        )

    total_screen = sum(int((w.end_time - w.start_time).total_seconds()) for w in windows) \
                 + sum(int((m.end_time - m.start_time).total_seconds()) for m in mobiles)

    screen_on_count = sum(1 for e in screen_events if e.event_type == "亮屏")
    total_hours = round(total_screen / 3600, 1)

    lines: list[str] = []
    lines.append(f"今天是 {target.isoformat()}，以下是用户的完整活动记录：")

    lines.append("\n手机使用：")
    if mobiles:
        for m in mobiles:
            dur_min = int((m.end_time - m.start_time).total_seconds() // 60)
            lines.append(f"- {m.start_time:%H:%M}-{m.end_time:%H:%M} {m.app_name} ({dur_min}分钟)")
    else:
        lines.append("- （无）")

    lines.append("\n电脑使用：")
    if windows:
        for w in windows:
            dur_min = int((w.end_time - w.start_time).total_seconds() // 60)
            lines.append(f"- {w.start_time:%H:%M}-{w.end_time:%H:%M} {w.app_name} | {w.window_title[:60]} ({dur_min}分钟)")
    else:
        lines.append("- （无）")

    lines.append("\n位置轨迹：")
    if locations:
        for loc in locations:
            addr = loc.address or f"({loc.latitude:.4f}, {loc.longitude:.4f})"
            lines.append(f"- {loc.timestamp:%H:%M} {addr}")
    else:
        lines.append("- （无）")

    lines.append("\n屏幕状态：")
    lines.append(f"亮屏 {screen_on_count} 次，总屏幕时间 {total_hours} 小时")

    lines.append("\n情绪记录：")
    if moods:
        for mood in moods:
            lines.append(f"- {mood.timestamp:%H:%M} {mood.content}")
    else:
        lines.append("- （无）")

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
        data_text
        + "\n\n请用轻松的语气总结今天这个人干了什么，"
        "不超过150字，不要说教，像朋友聊天一样。"
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
