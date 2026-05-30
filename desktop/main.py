"""Blurt desktop backend entrypoint — FastAPI + monitor + scheduler."""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from datetime import date, datetime, timedelta
from typing import Optional

import uvicorn
from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from database import (
    AppRecordMobile,
    AppRecordWindows,
    DailySummary,
    LocationRecord,
    get_session,
    init_db,
)
from monitor import WindowMonitor
from scheduler import generate_summary, start_scheduler

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


app = FastAPI(title="Blurt Desktop API", version="0.1.0", lifespan=lifespan)


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
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
