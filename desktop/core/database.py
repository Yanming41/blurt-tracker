"""SQLAlchemy models and session setup for Blurt desktop backend."""
from __future__ import annotations

from datetime import datetime
from pathlib import Path

from sqlalchemy import (
    Boolean,
    Column,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
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


class ScreenEventMobile(Base):
    __tablename__ = "screen_events_mobile"

    id = Column(Integer, primary_key=True, autoincrement=True)
    event_type = Column(String(16), nullable=False)  # 亮屏 / 息屏 / 解锁
    timestamp = Column(Integer, nullable=False, index=True)  # unix epoch seconds
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)


class MoodRecord(Base):
    """烂摊子情绪记录 — 用户随手扔进去的小情绪。"""
    __tablename__ = "mood_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    content = Column(Text, nullable=False)
    timestamp = Column(DateTime, nullable=False, index=True)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)


class ActivityBlockRecord(Base):
    """手机端切好的活动块。LLM 标签由本端跑批回填。

    自然键：(start_time, dominant_app_package)。手机重复上传会 upsert，
    LLM 已填的标签不会被擦掉（除非手动 force=true）。
    """
    __tablename__ = "activity_blocks"

    id = Column(Integer, primary_key=True, autoincrement=True)
    # 手机本地 id，仅作参考。重灌后会变，不能当主键。
    phone_block_id = Column(Integer, nullable=False, default=0)

    start_time = Column(DateTime, nullable=False, index=True)
    end_time = Column(DateTime, nullable=False)
    category = Column(String(32), nullable=False, default="other")
    dominant_app_package = Column(String(255), nullable=False, default="")
    dominant_app_name = Column(String(255), nullable=False, default="")
    total_app_time_ms = Column(Integer, nullable=False, default=0)
    duration_ms = Column(Integer, nullable=False, default=0)
    interruption_count = Column(Integer, nullable=False, default=0)
    location_address = Column(String(512), nullable=True)

    # ----- LLM 填的部分 -----
    activity_label = Column(String(64), nullable=True)
    sub_label = Column(String(255), nullable=True)
    confidence = Column(Float, nullable=True)
    reasoning = Column(Text, nullable=True)
    ask_user = Column(Text, nullable=True)
    manually_corrected = Column(Boolean, default=False, nullable=False)
    labeled_at = Column(DateTime, nullable=True)

    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)
    updated_at = Column(DateTime, default=datetime.utcnow, nullable=False)

    __table_args__ = (
        UniqueConstraint("start_time", "dominant_app_package", name="uq_block_start_dom"),
        Index("ix_blocks_label_status", "labeled_at", "manually_corrected"),
    )


class LlmCorrectionExample(Base):
    """用户修正历史 — 自动作为未来 prompt 的 few-shot 示例。"""
    __tablename__ = "llm_correction_examples"

    id = Column(Integer, primary_key=True, autoincrement=True)
    block_id = Column(Integer, ForeignKey("activity_blocks.id"), nullable=True)
    # LLM 当时看到的输入摘要（JSON 文本）
    input_summary = Column(Text, nullable=False)
    # LLM 当时给的输出（JSON 文本）
    llm_output = Column(Text, nullable=True)
    # 用户改成的最终标签
    user_correction_label = Column(String(64), nullable=False)
    user_correction_category = Column(String(32), nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False, index=True)


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
