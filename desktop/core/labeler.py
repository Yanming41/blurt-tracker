"""活动块标签流水线 — 调本地 Ollama 给 ActivityBlock 打活动标签。

工作流程：
  1. 拉今日 (或指定日) 未打标签的 blocks
  2. 拉最近 N 条用户修正作为 few-shot
  3. 构造 prompt，调 Ollama
  4. 解析 JSON，验证字段
  5. 写回 blocks 表
"""
from __future__ import annotations

import json
import logging
import re
import time
from datetime import date as date_cls, datetime, timedelta
from typing import Any, Optional

import requests
from sqlalchemy import and_
from sqlalchemy.orm import Session

from .config import get_config
from .database import (
    ActivityBlockRecord,
    LlmCorrectionExample,
    SessionLocal,
)

logger = logging.getLogger(__name__)

VALID_CATEGORIES = {
    "work", "learn", "social", "entertainment",
    "commute", "admin", "other",
}

BATCH_SIZE = 5  # 一次给 LLM 喂几个块
FEWSHOT_LIMIT = 5  # 最多塞几个 few-shot 例子


# ============================================================
#  Prompt 构造
# ============================================================

SYSTEM_PROMPT = """你是个人时间追踪助手。基于结构化的活动块数据，推断用户在那段时间做了什么。

类别字典（必须从这里选一个）：
- work          上班/写代码/写文档/邮件
- learn         读书/上课/教程
- social        微信/QQ 主导的聊天
- entertainment 抖音/视频/游戏/音乐
- commute       通勤/出行
- admin         银行/政务/医疗/账单
- other         不确定，放这里

规则：
1. 输出**严格 JSON**，不要任何额外解释、不要 markdown 代码块
2. 每个块对应一条结果，按输入顺序
3. activity 必须是 1-5 个汉字的活动名（如：写代码、刷视频、通勤、午休）
4. confidence: 0-1 浮点。低于 0.7 时**必须**填 ask_user
5. ask_user: 不确定时写一个问题让用户选；确定时填 null
6. reasoning: 一句话解释为什么这么判断（用户能看到）

返回格式（JSON 数组）：
[
  {
    "block_index": 0,
    "activity": "写代码",
    "category": "work",
    "confidence": 0.92,
    "ask_user": null,
    "reasoning": "Android Studio 占 75%，工作时段+常用地点。"
  },
  ...
]
"""


def _format_duration(ms: int) -> str:
    total_min = ms // 60_000
    h, m = divmod(total_min, 60)
    if h == 0:
        return f"{m}m"
    if m == 0:
        return f"{h}h"
    return f"{h}h{m}m"


def _block_to_summary(block: ActivityBlockRecord, idx: int) -> dict[str, Any]:
    """把 DB 块拍成 LLM 易消化的 JSON 摘要。"""
    weekday = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"][block.start_time.weekday()]
    time_str = f"{weekday} {block.start_time.strftime('%H:%M')}-{block.end_time.strftime('%H:%M')}"
    return {
        "block_index": idx,
        "time": time_str,
        "duration": _format_duration(block.duration_ms),
        "category_hint": block.category,
        "dominant_app": block.dominant_app_name,
        "dominant_pkg": block.dominant_app_package,
        "total_app_time": _format_duration(block.total_app_time_ms),
        "interruption_count": block.interruption_count,
        "location": block.location_address or "无",
    }


def _fewshot_examples(session: Session) -> list[dict[str, str]]:
    """拉最近 N 条用户修正示例。"""
    rows = (
        session.query(LlmCorrectionExample)
        .order_by(LlmCorrectionExample.created_at.desc())
        .limit(FEWSHOT_LIMIT)
        .all()
    )
    out = []
    for r in rows:
        out.append({
            "input": r.input_summary,
            "user_label": r.user_correction_label,
            "user_category": r.user_correction_category,
        })
    return list(reversed(out))  # 最老的在前，最新的在后


def _build_user_prompt(blocks: list[ActivityBlockRecord], fewshot: list[dict[str, str]]) -> str:
    parts: list[str] = []

    if fewshot:
        parts.append("# 用户偏好示例（最近修正过的标签，作为参考）")
        for i, ex in enumerate(fewshot):
            parts.append(f"## 示例 {i + 1}")
            parts.append(f"输入: {ex['input']}")
            parts.append(f"用户选择: activity={ex['user_label']}, category={ex['user_category']}")
            parts.append("")

    parts.append("# 待标记的活动块（共 %d 个）" % len(blocks))
    summaries = [_block_to_summary(b, i) for i, b in enumerate(blocks)]
    parts.append(json.dumps(summaries, ensure_ascii=False, indent=2))
    parts.append("")
    parts.append("请返回 JSON 数组，每个对象对应一个块。")
    return "\n".join(parts)


# ============================================================
#  Ollama 调用 + JSON 验证
# ============================================================

def _call_ollama(prompt_system: str, prompt_user: str, timeout: int = 120) -> str:
    cfg = get_config()
    url = f"{cfg['ollama_url'].rstrip('/')}/api/chat"
    payload = {
        "model": cfg["ollama_model"],
        "messages": [
            {"role": "system", "content": prompt_system},
            {"role": "user", "content": prompt_user},
        ],
        "stream": False,
        "options": {"temperature": 0.2},
        "format": "json",  # Ollama 会强制 JSON 输出
    }
    r = requests.post(url, json=payload, timeout=timeout)
    r.raise_for_status()
    data = r.json()
    return data.get("message", {}).get("content", "")


def _extract_json_array(text: str) -> list[dict[str, Any]]:
    """容忍 markdown 包裹，提取第一个 JSON 数组。"""
    # 去掉 markdown 包裹
    text = re.sub(r"^```(?:json)?\s*", "", text.strip(), flags=re.MULTILINE)
    text = re.sub(r"\s*```$", "", text.strip(), flags=re.MULTILINE)
    # 直接解析
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        # 找第一个数组
        m = re.search(r"\[\s*\{.*?\}\s*\]", text, re.DOTALL)
        if not m:
            raise
        data = json.loads(m.group(0))
    # 单对象 -> 包成数组
    if isinstance(data, dict):
        # Ollama format=json 有时返回 {"results": [...]} 这种
        if "results" in data and isinstance(data["results"], list):
            return data["results"]
        return [data]
    if not isinstance(data, list):
        raise ValueError(f"Expected list, got {type(data)}")
    return data


def _validate_label_obj(o: dict[str, Any]) -> dict[str, Any]:
    """规范化字段，缺啥补啥。"""
    activity = str(o.get("activity") or "").strip()[:30] or "未知"
    category = str(o.get("category") or "other").strip().lower()
    if category not in VALID_CATEGORIES:
        category = "other"
    try:
        confidence = float(o.get("confidence") or 0.5)
    except (TypeError, ValueError):
        confidence = 0.5
    confidence = max(0.0, min(1.0, confidence))
    ask_user = o.get("ask_user")
    if confidence < 0.7 and not ask_user:
        ask_user = f"这段时间你是在做什么？（{activity}？）"
    if confidence >= 0.7:
        ask_user = None
    reasoning = str(o.get("reasoning") or "")[:500]
    return {
        "activity": activity,
        "category": category,
        "confidence": confidence,
        "ask_user": ask_user,
        "reasoning": reasoning,
    }


# ============================================================
#  主流程
# ============================================================

def label_blocks_for_date(target_date: date_cls, force: bool = False) -> dict[str, Any]:
    """对某一天的未标签块跑一次 LLM。返回统计。"""
    day_start = datetime.combine(target_date, datetime.min.time())
    day_end = day_start + timedelta(days=1)
    session: Session = SessionLocal()
    started = time.time()
    try:
        q = session.query(ActivityBlockRecord).filter(
            and_(
                ActivityBlockRecord.start_time >= day_start,
                ActivityBlockRecord.start_time < day_end,
                ActivityBlockRecord.manually_corrected == False,  # noqa: E712
            )
        )
        if not force:
            q = q.filter(ActivityBlockRecord.activity_label.is_(None))
        blocks = q.order_by(ActivityBlockRecord.start_time.asc()).all()

        if not blocks:
            return {"date": str(target_date), "labeled": 0, "skipped": 0, "elapsed_s": 0}

        fewshot = _fewshot_examples(session)
        labeled = 0
        skipped = 0

        for i in range(0, len(blocks), BATCH_SIZE):
            batch = blocks[i:i + BATCH_SIZE]
            user_prompt = _build_user_prompt(batch, fewshot)
            try:
                raw = _call_ollama(SYSTEM_PROMPT, user_prompt)
                items = _extract_json_array(raw)
            except Exception:
                logger.exception("Ollama call/parse failed; skipping batch")
                skipped += len(batch)
                continue

            # 按 block_index 对齐
            by_idx = {int(o.get("block_index", i)): o for i, o in enumerate(items)}
            for j, blk in enumerate(batch):
                obj = by_idx.get(j) or (items[j] if j < len(items) else None)
                if obj is None:
                    skipped += 1
                    continue
                v = _validate_label_obj(obj)
                blk.activity_label = v["activity"]
                blk.sub_label = None
                # 仅在 LLM 给的 category 跟规则推断不同时才覆盖
                if v["category"] != "other" and v["category"] != blk.category:
                    blk.category = v["category"]
                blk.confidence = v["confidence"]
                blk.reasoning = v["reasoning"]
                blk.ask_user = v["ask_user"]
                blk.labeled_at = datetime.utcnow()
                blk.updated_at = datetime.utcnow()
                labeled += 1

            session.commit()

        elapsed = time.time() - started
        logger.info("labeled %d blocks for %s in %.1fs", labeled, target_date, elapsed)
        return {
            "date": str(target_date),
            "labeled": labeled,
            "skipped": skipped,
            "elapsed_s": round(elapsed, 1),
        }
    finally:
        session.close()


def record_correction(
    block_id: int,
    user_label: str,
    user_category: str,
) -> bool:
    """用户修正一个块的标签 -> 写库 + 生成 few-shot 示例。"""
    session: Session = SessionLocal()
    try:
        blk = session.get(ActivityBlockRecord, block_id)
        if blk is None:
            return False
        # 生成 input_summary（修正时的快照）
        snapshot = _block_to_summary(blk, 0)
        # 标记修正
        blk.activity_label = user_label
        blk.category = user_category if user_category in VALID_CATEGORIES else blk.category
        blk.manually_corrected = True
        blk.ask_user = None
        blk.confidence = 1.0
        blk.updated_at = datetime.utcnow()
        # 写 few-shot 示例
        ex = LlmCorrectionExample(
            block_id=blk.id,
            input_summary=json.dumps(snapshot, ensure_ascii=False),
            llm_output=json.dumps({
                "activity": blk.activity_label,
                "category": blk.category,
                "confidence": blk.confidence,
            }, ensure_ascii=False),
            user_correction_label=user_label,
            user_correction_category=user_category,
        )
        session.add(ex)
        session.commit()
        return True
    finally:
        session.close()
