"""payload 안에서 되풀이되는 중첩 블록 빌더."""

from __future__ import annotations

from .codec import _enum, _optional_enum, _percent, _ratio
from .contract import (
    BATTERY_OPTIONAL_MEMBERS,
    BATTERY_SOURCES,
    BATTERY_STATES,
    HEALTH_MEMBERS,
    HEALTH_STATES,
)


def health(**members) -> dict:
    """state.health 블록. 인자를 주지 않은 장치는 UNKNOWN.

    UNKNOWN 은 '부팅 직후 검사 전이거나 현재 하드웨어로 판단 불가' 를 뜻한다.
    """
    unknown = set(members) - set(HEALTH_MEMBERS)
    if unknown:
        raise ValueError(f"health: unknown member {sorted(unknown)}")
    return {
        name: _enum(members.get(name, "UNKNOWN"), HEALTH_STATES, f"health.{name}")
        for name in HEALTH_MEMBERS
    }


def _position(valid: bool, lat, lon, heading_deg) -> dict:
    """GPS Fix 가 없으면 과거 위치를 복사하지 않는다 (계약 §6.2)."""
    if not valid:
        return {"valid": False, "lat": None, "lon": None, "heading_deg": None}
    if lat is None or lon is None:
        raise ValueError("position: valid=True requires both lat and lon")
    if heading_deg is not None and not 0.0 <= heading_deg < 360.0:
        raise ValueError(f"position.heading_deg: {heading_deg} out of range [0.0, 360.0)")
    return {
        "valid": True,
        "lat": float(lat),
        "lon": float(lon),
        # RMC COG 가 유효할 때만. 정지·저속·Course 미제공이면 null.
        "heading_deg": None if heading_deg is None else float(heading_deg),
    }


def _battery(
    valid: bool, percent, state: str, *, with_source: bool = False, source=None, extra=None
) -> dict:
    """battery 블록. `source` 는 telemetry 에서만 쓰고 state·event 에서는 생략한다."""
    block = {"valid": bool(valid)}
    if valid:
        if percent is None:
            raise ValueError("battery: valid=True requires percent")
        block["percent"] = _percent(percent, "battery.percent")
        state = _enum(state, BATTERY_STATES, "battery.state")
        if state == "UNKNOWN":
            # 서버 DTO 는 valid=true 면 state != UNKNOWN 을 요구한다. 여기서 안 막으면
            # MQTT_PAYLOAD_VALIDATION_FAILED 로 조용히 버려진다 — 측정값을 믿는다면
            # 등급도 나와야 하고, 못 믿으면 valid=false 다.
            raise ValueError("battery: valid=True requires a graded state, not UNKNOWN")
        block["state"] = state
    else:
        # 측정값을 못 믿으면 퍼센트는 null, 등급은 UNKNOWN. 이전 값 복사 금지.
        _enum(state, BATTERY_STATES, "battery.state")  # 오타는 여기서 걸러낸다
        block["percent"] = None
        block["state"] = "UNKNOWN"
    if with_source:
        if valid and source is None:
            raise ValueError("battery.source: valid=True requires source")
        block["source"] = (
            _optional_enum(source, BATTERY_SOURCES, "battery.source") if valid else None
        )
    if extra:
        unknown = set(extra) - BATTERY_OPTIONAL_MEMBERS
        if unknown:
            raise ValueError(f"battery: unsupported optional member {sorted(unknown)}")
        block.update(extra)
    return block


def _bbox(bbox_norm) -> list:
    """[x_min, y_min, x_max, y_max], 각 값 0.0~1.0.

    서버 DTO 는 `x_min < x_max` 이고 `y_min < y_max` 인 강한 부등식을 요구한다 —
    폭이나 높이가 0 인 상자는 MQTT_PAYLOAD_VALIDATION_FAILED 로 조용히 버려진다.
    거부는 서버 로그에만 남고 로봇 Outbox 는 PUBACK 을 받아 지워지므로, 여기서
    먼저 막아야 유실을 알아챌 수 있다.
    """
    values = list(bbox_norm)
    if len(values) != 4:
        raise ValueError("bbox_norm: [x_min, y_min, x_max, y_max] required")
    x_min, y_min, x_max, y_max = (
        _ratio(v, f"bbox_norm[{i}]") for i, v in enumerate(values)
    )
    if x_min >= x_max or y_min >= y_max:
        raise ValueError(f"bbox_norm: min must be strictly less than max, got {values}")
    return [x_min, y_min, x_max, y_max]
