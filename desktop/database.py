"""SQLAlchemy models and session setup for Blurt desktop backend."""
from __future__ import annotations

from datetime import datetime
from pathlib import Path

from sqlalchemy import (
    Column,
    DateTime,
    Float,
    Integer,
    String,
    Text,
    create_engine,
)
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DB_PATH = Path(__file__).parent / "blurt.db"
DB_URL = f"sqlite:///{DB_PATH}"

engine = create_engine(
    DB_URL,
    connect_args={"check_same_thread": False},
    future=True,
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


class AppRecordMobile(Base):
    __tablename__ = "app_records_mobile"

    id = Column(Integer, primary_key=True, autoincrement=True)
    app_name = Column(String(255), nullable=False)
    package_name = Column(String(255), nullable=False, index=True)
    start_time = Column(DateTime, nullable=False, index=True)
    end_time = Column(DateTime, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)


class AppRecordWindows(Base):
    __tablename__ = "app_records_windows"

    id = Column(Integer, primary_key=True, autoincrement=True)
    app_name = Column(String(255), nullable=False, index=True)
    window_title = Column(String(512), nullable=False)
    start_time = Column(DateTime, nullable=False, index=True)
    end_time = Column(DateTime, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)


class LocationRecord(Base):
    __tablename__ = "location_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    latitude = Column(Float, nullable=False)
    longitude = Column(Float, nullable=False)
    address = Column(String(512), nullable=True)
    timestamp = Column(DateTime, nullable=False, index=True)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)


class DailySummary(Base):
    __tablename__ = "daily_summary"

    id = Column(Integer, primary_key=True, autoincrement=True)
    date = Column(String(10), nullable=False, unique=True, index=True)  # YYYY-MM-DD
    summary_text = Column(Text, nullable=False)
    total_screen_time = Column(Integer, nullable=False, default=0)  # seconds
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)


def init_db() -> None:
    Base.metadata.create_all(engine)


def get_session():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
