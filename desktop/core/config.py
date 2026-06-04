"""配置文件读写 — config.json 位于 desktop/ 根目录。"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

CONFIG_PATH = Path(__file__).resolve().parent.parent / "config.json"

DEFAULT_CONFIG: dict[str, Any] = {
    "phone_ip": "",
    "phone_port": 8000,
    "ollama_url": "http://localhost:11434",
    "ollama_model": "qwen2.5:3b",
    "retention_days": 30,
    "device_name": "游戏本",
    "api_host": "0.0.0.0",
    "api_port": 8000,
    "mica_enabled": False,
}


def get_config() -> dict[str, Any]:
    if not CONFIG_PATH.exists():
        save_config(DEFAULT_CONFIG)
        return dict(DEFAULT_CONFIG)
    try:
        with CONFIG_PATH.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError):
        logger.exception("Failed to read config.json, falling back to defaults")
        return dict(DEFAULT_CONFIG)
    merged = dict(DEFAULT_CONFIG)
    merged.update(data)
    return merged


def save_config(data: dict[str, Any]) -> None:
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    with CONFIG_PATH.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def update_config(**fields: Any) -> dict[str, Any]:
    data = get_config()
    data.update(fields)
    save_config(data)
    return data
