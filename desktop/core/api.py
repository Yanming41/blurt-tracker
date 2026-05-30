"""FastAPI 应用 + 可在后台线程运行的 uvicorn 服务包装。"""
from __future__ import annotations

import logging
import shutil
import socket
import subprocess
import threading
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Optional

import requests
import uvicorn
from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import func
from sqlalchemy.orm import Session

from .config import get_config
from .database import (
    AppRecordMobile,
    AppRecordWindows,
    DailySummary,
    LocationRecord,
    SessionLocal,
    get_session,
)
from .monitor import WindowMonitor
from .scheduler import generate_summary

logger = logging.getLogger(__name__)

APP_VERSION = "0.1.0"

# 全局监控对象 — main.py 启动它，路由 /status 读它的状态
monitor = WindowMonitor()

app = FastAPI(title="Blurt Desktop API", version=APP_VERSION)


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


# ---------- Network helpers ----------

def get_tailscale_ip() -> Optional[str]:
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
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def ollama_available(timeout: float = 3.0) -> bool:
    base = get_config().get("ollama_url", "http://localhost:11434").rstrip("/")
    try:
        r = requests.get(f"{base}/api/tags", timeout=timeout)
        return r.ok
    except requests.RequestException:
        return False


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
    cfg = get_config()
    return {
        "status": "online",
        "device": cfg.get("device_name", "游戏本"),
        "timestamp": int(time.time()),
        "version": APP_VERSION,
    }


@app.get("/status")
def status():
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


# ---------- Uvicorn server in a thread ----------

class ApiServerThread:
    """uvicorn 在后台线程跑，主线程留给 Qt。"""

    def __init__(self, host: str = "0.0.0.0", port: int = 8000):
        self.host = host
        self.port = port
        config = uvicorn.Config(
            app,
            host=host,
            port=port,
            log_level="info",
            access_log=False,
        )
        self._server = uvicorn.Server(config)
        self._thread = threading.Thread(
            target=self._server.run, daemon=True, name="UvicornServer"
        )

    def start(self) -> None:
        if self._thread.is_alive():
            return
        self._thread.start()
        logger.info("Uvicorn started on %s:%s", self.host, self.port)

    def stop(self) -> None:
        self._server.should_exit = True

    def is_running(self) -> bool:
        return self._thread.is_alive() and self._server.started
