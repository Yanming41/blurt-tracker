"""GUI 通用小工具。"""
from __future__ import annotations


def format_duration(seconds: int) -> str:
    if seconds < 60:
        return f"{seconds}秒"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes}分钟"
    hours = minutes // 60
    rem = minutes % 60
    if rem == 0:
        return f"{hours}小时"
    return f"{hours}小时{rem}分"


def source_icon(source: str) -> str:
    return {
        "windows": "💻",
        "mobile": "📱",
        "location": "📍",
    }.get(source, "•")


def source_label(source: str) -> tuple[str, str]:
    """返回 (标签文字, 颜色) — 给 InfoBadge 用。"""
    return {
        "mobile":   ("手机", "#0078d4"),
        "windows":  ("电脑", "#107c10"),
        "location": ("位置", "#8a8a8a"),
    }.get(source, (source, "#8a8a8a"))
