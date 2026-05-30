"""Blurt desktop backend entrypoint — FastAPI + monitor + scheduler."""
from __future__ import annotations

import logging
import shutil
import socket
import subprocess
import time
from contextlib import asynccontextmanager
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Optional

import requests
import uvicorn
from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import func
from sqlalchemy.orm import Session

from database import (
    AppRecordMobile,
    AppRecordWindows,
    DailySummary,
    LocationRecord,
    SessionLocal,
    get_session,
    init_db,
)
from monitor import WindowMonitor
from scheduler import OLLAMA_URL, generate_summary, start_scheduler

APP_VERSION = "0.1.0"
DEVICE_NAME = "游戏本"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("blurt")

monitor = WindowMonitor()


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    monitor.start()
    sched = start_scheduler()
    try:
        yield
    finally:
        monitor.stop()
        sched.shutdown(wait=False)


app = FastAPI(title="Blurt Desktop API", version=APP_VERSION, lifespan=lifespan)


# ---------- Network helpers ----------

def get_tailscale_ip() -> Optional[str]:
    """Return the local Tailscale IPv4 address, or None if unavailable."""
    exe = shutil.which("tailscale")
    if not exe:
        return None
    try:
        out = subprocess.run(
            [exe, "ip", "-4"],
            capture_output=True,
            text=True,
            timeout=3,
        )
        if out.returncode != 0:
            return None
        for line in out.stdout.splitlines():
            line = line.strip()
            if line:
                return line
    except (subprocess.TimeoutExpired, OSError):
        return None
    return None


def get_lan_ip() -> str:
    """Best-effort local LAN IP — does not actually send packets."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def ollama_available(timeout: float = 3.0) -> bool:
    try:
        r = requests.get(OLLAMA_URL.replace("/api/generate", "/api/tags"), timeout=timeout)
        return r.ok
    except requests.RequestException:
        return False


def print_startup_banner() -> None:
    ts_ip = get_tailscale_ip()
    if ts_ip:
        label = "本机Tailscale IP"
        ip = ts_ip
    else:
        label = "本机局域网IP（Tailscale未检测到）"
        ip = get_lan_ip()

    bar = "━" * 28
    print("\n🗑️  烂摊子后端已启动")
    print(bar)
    print(f"📡 {label}：{ip}")
    print(f"🌐 服务地址：http://{ip}:8000")
    print("📱 手机填写此IP即可连接")
    print(bar + "\n")


# ---------- Pydantic schemas ----------

class MobileAppRecordIn(BaseModel):
    app_name: str
    package_name: str
    start_time: datetime
    end_time: datetime


class MobileLocationIn(BaseModel):
    latitude: float
    longitude: float
    address: Optional[str] = None
    timestamp: datetime


class TimelineEntry(BaseModel):
    source: str  # "windows" | "mobile" | "location"
    start_time: datetime
    end_time: Optional[datetime] = None
    app_name: Optional[str] = None
    title_or_address: Optional[str] = None
    package_name: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None


class TimelineOut(BaseModel):
    date: str
    entries: list[TimelineEntry]


class SummaryOut(BaseModel):
    date: str
    summary_text: str
    total_screen_time: int
    created_at: datetime


class GenerateSummaryIn(BaseModel):
    date: Optional[str] = Field(default=None, description="YYYY-MM-DD; defaults to today")


# ---------- Helpers ----------

def _parse_date(s: str) -> date:
    try:
        return date.fromisoformat(s)
    except ValueError:
        raise HTTPException(status_code=400, detail=f"Invalid date: {s}")


def _build_timeline(db: Session, target: date) -> TimelineOut:
    start = datetime.combine(target, datetime.min.time())
    end = start + timedelta(days=1)
    entries: list[TimelineEntry] = []

    for w in db.query(AppRecordWindows).filter(
        AppRecordWindows.start_time >= start, AppRecordWindows.start_time < end
    ).all():
        entries.append(TimelineEntry(
            source="windows",
            start_time=w.start_time,
            end_time=w.end_time,
            app_name=w.app_name,
            title_or_address=w.window_title,
        ))

    for m in db.query(AppRecordMobile).filter(
        AppRecordMobile.start_time >= start, AppRecordMobile.start_time < end
    ).all():
        entries.append(TimelineEntry(
            source="mobile",
            start_time=m.start_time,
            end_time=m.end_time,
            app_name=m.app_name,
            package_name=m.package_name,
        ))

    for loc in db.query(LocationRecord).filter(
        LocationRecord.timestamp >= start, LocationRecord.timestamp < end
    ).all():
        entries.append(TimelineEntry(
            source="location",
            start_time=loc.timestamp,
            title_or_address=loc.address,
            latitude=loc.latitude,
            longitude=loc.longitude,
        ))

    entries.sort(key=lambda e: e.start_time)
    return TimelineOut(date=target.isoformat(), entries=entries)


# ---------- Routes ----------

@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/ping")
def ping():
    """轻量探活接口，手机端用于检测后端是否在线。"""
    return {
        "status": "online",
        "device": DEVICE_NAME,
        "timestamp": int(time.time()),
        "version": APP_VERSION,
    }


@app.get("/status")
def status():
    """详细设备状态，供手机端状态卡片展示。"""
    # 数据库与今日记录
    database_ok = True
    records_today = 0
    last_record_time: Optional[str] = None
    try:
        today_start = datetime.combine(date.today(), datetime.min.time())
        today_end = today_start + timedelta(days=1)
        with SessionLocal() as db:
            win_count = db.query(func.count(AppRecordWindows.id)).filter(
                AppRecordWindows.start_time >= today_start,
                AppRecordWindows.start_time < today_end,
            ).scalar() or 0
            mob_count = db.query(func.count(AppRecordMobile.id)).filter(
                AppRecordMobile.start_time >= today_start,
                AppRecordMobile.start_time < today_end,
            ).scalar() or 0
            loc_count = db.query(func.count(LocationRecord.id)).filter(
                LocationRecord.timestamp >= today_start,
                LocationRecord.timestamp < today_end,
            ).scalar() or 0
            records_today = int(win_count + mob_count + loc_count)

            last_win = db.query(func.max(AppRecordWindows.end_time)).scalar()
            last_mob = db.query(func.max(AppRecordMobile.end_time)).scalar()
            last_loc = db.query(func.max(LocationRecord.timestamp)).scalar()
            candidates = [t for t in (last_win, last_mob, last_loc) if t is not None]
            if candidates:
                last_record_time = max(candidates).strftime("%H:%M")
    except Exception:
        logger.exception("status: database query failed")
        database_ok = False

    # 磁盘剩余空间（数据库所在盘）
    try:
        usage = shutil.disk_usage(str(Path(__file__).parent))
        disk_free_gb = round(usage.free / (1024 ** 3), 1)
    except OSError:
        disk_free_gb = 0.0

    return {
        "status": "online",
        "monitor_running": monitor.is_running(),
        "database_ok": database_ok,
        "records_today": records_today,
        "last_record_time": last_record_time,
        "ollama_available": ollama_available(),
        "disk_free_gb": disk_free_gb,
    }


@app.post("/mobile/app-record", status_code=201)
def post_mobile_app(record: MobileAppRecordIn, db: Session = Depends(get_session)):
    row = AppRecordMobile(**record.model_dump())
    db.add(row)
    db.commit()
    db.refresh(row)
    return {"id": row.id}


@app.post("/mobile/location", status_code=201)
def post_mobile_location(record: MobileLocationIn, db: Session = Depends(get_session)):
    row = LocationRecord(**record.model_dump())
    db.add(row)
    db.commit()
    db.refresh(row)
    return {"id": row.id}


@app.get("/timeline/today", response_model=TimelineOut)
def timeline_today(db: Session = Depends(get_session)):
    return _build_timeline(db, date.today())


@app.get("/timeline/{date_str}", response_model=TimelineOut)
def timeline_date(date_str: str, db: Session = Depends(get_session)):
    return _build_timeline(db, _parse_date(date_str))


@app.get("/summary/today", response_model=SummaryOut)
def summary_today(db: Session = Depends(get_session)):
    iso = date.today().isoformat()
    row = db.query(DailySummary).filter_by(date=iso).one_or_none()
    if not row:
        raise HTTPException(status_code=404, detail="No summary for today yet")
    return SummaryOut(
        date=row.date,
        summary_text=row.summary_text,
        total_screen_time=row.total_screen_time,
        created_at=row.created_at,
    )


@app.post("/summary/generate", response_model=SummaryOut)
def summary_generate(payload: GenerateSummaryIn):
    target = _parse_date(payload.date) if payload.date else date.today()
    row = generate_summary(target)
    return SummaryOut(
        date=row.date,
        summary_text=row.summary_text,
        total_screen_time=row.total_screen_time,
        created_at=row.created_at,
    )


if __name__ == "__main__":
    print_startup_banner()
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
