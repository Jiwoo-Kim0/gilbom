"""식별자·시각·JSON 직렬화와 값 검사 헬퍼."""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone


def new_uuid() -> str:
    """하이픈을 포함한 UUIDv4 문자열."""
    return str(uuid.uuid4())


def utc_now() -> str:
    return to_iso(datetime.now(timezone.utc))


def to_iso(dt: datetime) -> str:
    """datetime → `2026-07-26T05:15:35.000Z` (밀리초 3자리 고정)."""
    if dt.tzinfo is None:
        raise ValueError("naive datetime is not allowed; use timezone-aware UTC")
    dt = dt.astimezone(timezone.utc)
    return f"{dt.strftime('%Y-%m-%dT%H:%M:%S')}.{dt.microsecond // 1000:03d}Z"


def encode(payload: dict) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def decode(raw: bytes) -> dict:
    """수신 payload 파싱. JSON Object 가 아니면 ValueError."""
    parsed = json.loads(raw.decode("utf-8"))
    if not isinstance(parsed, dict):
        raise ValueError("payload must be a JSON object")
    return parsed


def _enum(value, allowed: frozenset, field: str) -> str:
    if value not in allowed:
        raise ValueError(f"{field}: {value!r} is not one of {sorted(allowed)}")
    return value


def _optional_enum(value, allowed: frozenset, field: str):
    return None if value is None else _enum(value, allowed, field)


def _in_range(value, low: float, high: float, field: str):
    if value is None:
        return None
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"{field}: number required, got {value!r}")
    if not low <= value <= high:
        raise ValueError(f"{field}: {value} out of range {low}~{high}")
    return float(value)


def _ratio(value, field: str):
    return _in_range(value, 0.0, 1.0, field)


def _percent(value, field: str):
    return _in_range(value, 0.0, 100.0, field)
