"""factory 단위 테스트 — 발행 topic 8종의 필드명·enum·null 처리. (DoD 4, 5)

기준: web/docs/final-mqtt-topic-schema-v2.md
"""

import json
import re
import uuid
from pathlib import Path

import pytest

from comms import (
    PUBLISH_KINDS,
    MessageFactory,
    TopicKind,
    decode,
    encode,
    health,
    to_iso,
    topic,
    utc_now,
)
from comms.contract import (
    EVENT_TTL_DEFAULT_SEC,
    EVENT_TTL_MAX_SEC,
    EVENT_TTL_MIN_SEC,
    NAMESPACE,
)

ROBOT_ID = "RBT-001"
ISO_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")


@pytest.fixture
def factory():
    return MessageFactory(ROBOT_ID)


def is_uuid4(value):
    parsed = uuid.UUID(value)
    return str(parsed) == value and parsed.version == 4


def health_ok():
    return health(
        camera="OK", lidar="OK", gps="OK", battery="OK", motor="UNKNOWN", storage="OK"
    )


# --------------------------------------------------------------------------
# topic
# --------------------------------------------------------------------------


def test_topics_use_v2_namespace():
    expected = {
        TopicKind.PRESENCE: "tbs/v2/robots/RBT-001/presence",
        TopicKind.STATE: "tbs/v2/robots/RBT-001/state",
        TopicKind.TELEMETRY: "tbs/v2/robots/RBT-001/telemetry",
        TopicKind.EVENT_INCIDENT: "tbs/v2/robots/RBT-001/events/incident",
        TopicKind.EVENT_BATTERY: "tbs/v2/robots/RBT-001/events/battery",
        TopicKind.EVENT_SAFETY: "tbs/v2/robots/RBT-001/events/safety",
        TopicKind.EVENT_MISSION: "tbs/v2/robots/RBT-001/events/mission",
        TopicKind.COMMAND_RESULT: "tbs/v2/robots/RBT-001/commands/result",
        TopicKind.COMMAND_REQUEST: "tbs/v2/robots/RBT-001/commands/request",
    }
    for kind, expected_topic in expected.items():
        assert topic(ROBOT_ID, kind) == expected_topic
    assert len(PUBLISH_KINDS) == 8
    assert TopicKind.COMMAND_REQUEST not in PUBLISH_KINDS


def test_no_v1_namespace_anywhere():
    assert NAMESPACE == "tbs/v2"
    for kind in TopicKind:
        assert "tbs/v1" not in topic(ROBOT_ID, kind)


#: 계약 계보상 「MQTT 통신 스웨거 명세서」는 v1 이다. 이걸 "신버전" 으로 적어 둔 주석이
#: 남아 있으면 다음 사람이 v1 문서를 정본으로 읽는다 — 실제로 uploader 에서 한 번 났다.
_STALE_SPEC_MARKERS = ("tbs/v1", "스웨거", "schema_version \"1.0\"")


def test_source_never_points_at_the_v1_contract():
    """구현 파일이 v1 문서를 기준으로 지목하지 않는지. 정본은 v2 하나다."""
    root = Path(__file__).resolve().parents[1]
    # 테스트 자신은 제외한다 — 이 파일이 마커 문자열을 들고 있으므로 스캔에 걸린다.
    # 디렉터리 이름이 test/tests 둘 다 있었으므로 정확 일치가 아니라 접두어로 본다.
    sources = [
        path
        for path in root.rglob("*.py")
        if "__pycache__" not in path.parts
        and not any(part.startswith("test") for part in path.parts)
    ]
    assert sources, "스캔할 소스를 못 찾았다 — 경로 가정이 깨졌다"
    offenders = []
    for path in sources:
        text = path.read_text(encoding="utf-8")
        for marker in _STALE_SPEC_MARKERS:
            if marker in text:
                offenders.append(f"{path.relative_to(root)}: {marker!r}")
    assert not offenders, "v1 계약을 가리키는 참조가 남았다: " + ", ".join(offenders)


# --------------------------------------------------------------------------
# presence (§4)
# --------------------------------------------------------------------------


def test_presence_online(factory):
    payload = factory.presence_online()
    assert payload == {
        "schema_version": "2.0",
        "mqtt_id": ROBOT_ID,
        "boot_id": factory.boot_id,
        "status": "ONLINE",
        "connected_at": payload["connected_at"],
    }
    assert ISO_PATTERN.match(payload["connected_at"])
    # presence 는 seq 를 쓰지 않는다.
    assert "seq" not in payload
    assert factory.seq == 0


@pytest.mark.parametrize(
    "reason",
    ["GRACEFUL_SHUTDOWN", "UNGRACEFUL_DISCONNECT", "CRITICAL_BATTERY_SHUTDOWN"],
)
def test_presence_offline(factory, reason):
    payload = factory.presence_offline(reason)
    assert payload["status"] == "OFFLINE"
    assert payload["reason"] == reason
    assert "connected_at" not in payload


def test_presence_offline_rejects_unknown_reason(factory):
    with pytest.raises(ValueError):
        factory.presence_offline("BATTERY_DEAD")


# --------------------------------------------------------------------------
# state (§5)
# --------------------------------------------------------------------------


def test_state_full(factory):
    payload = factory.state(
        mode="PATROLLING",
        reason_code="NONE",
        mission_id="MIS-20260726-001",
        mission_state="RUNNING",
        battery_valid=True,
        battery_percent=67.4,
        battery_state="NORMAL",
        health_block=health_ok(),
    )
    assert list(payload) == [
        "schema_version",
        "mqtt_id",
        "boot_id",
        "seq",
        "updated_at",
        "mode",
        "reason_code",
        "mission",
        "battery",
        "health",
    ]
    assert payload["schema_version"] == "2.0"
    assert payload["mission"] == {"mission_id": "MIS-20260726-001", "state": "RUNNING"}
    assert payload["battery"] == {"valid": True, "percent": 67.4, "state": "NORMAL"}
    # source 는 telemetry 전용 — state 에는 없다.
    assert "source" not in payload["battery"]
    assert set(payload["health"]) == {
        "camera",
        "lidar",
        "gps",
        "battery",
        "motor",
        "storage",
    }


def test_state_without_mission_is_null(factory):
    payload = factory.state(
        mode="READY",
        reason_code="NONE",
        battery_valid=True,
        battery_percent=90.0,
        battery_state="NORMAL",
        health_block=health_ok(),
    )
    assert payload["mission"] is None


def test_state_battery_invalid_nulls_percent(factory):
    payload = factory.state(
        mode="ERROR",
        reason_code="INTERNAL_ERROR",
        battery_valid=False,
        battery_percent=None,
        battery_state="UNKNOWN",
        health_block=health(battery="FAULT"),
    )
    assert payload["battery"] == {"valid": False, "percent": None, "state": "UNKNOWN"}
    assert payload["health"]["battery"] == "FAULT"
    # 인자를 주지 않은 장치는 UNKNOWN.
    assert payload["health"]["camera"] == "UNKNOWN"


def test_state_battery_valid_rejects_unknown_state(factory):
    """서버 DTO 는 valid=true 면 state != UNKNOWN 을 요구한다 (§5.5).

    측정값을 믿는다면 등급도 나와야 하고, 못 믿으면 valid=false 다. 여기서 안 막으면
    MQTT_PAYLOAD_VALIDATION_FAILED 로 조용히 버려진다.
    """
    with pytest.raises(ValueError):
        factory.state(
            mode="PATROLLING",
            reason_code="NONE",
            battery_valid=True,
            battery_percent=67.4,
            battery_state="UNKNOWN",
            health_block=health_ok(),
        )


@pytest.mark.parametrize(
    "kwargs",
    [
        {"mode": "CRUISING"},
        {"reason_code": "OBSTACLE_DETECTED"},
        {"mission_id": "MIS-1", "mission_state": "GOING"},
    ],
)
def test_state_rejects_unknown_enum(factory, kwargs):
    base = dict(
        mode="PATROLLING",
        reason_code="NONE",
        battery_valid=True,
        battery_percent=50.0,
        battery_state="NORMAL",
        health_block=health_ok(),
    )
    base.update(kwargs)
    with pytest.raises(ValueError):
        factory.state(**base)


def test_state_fills_missing_health_members_with_unknown(factory):
    payload = factory.state(
        mode="READY",
        reason_code="NONE",
        battery_valid=True,
        battery_percent=50.0,
        battery_state="NORMAL",
        health_block={"camera": "OK"},
    )
    assert payload["health"] == {
        "camera": "OK",
        "lidar": "UNKNOWN",
        "gps": "UNKNOWN",
        "battery": "UNKNOWN",
        "motor": "UNKNOWN",
        "storage": "UNKNOWN",
    }


def test_state_rejects_unknown_health_member(factory):
    with pytest.raises(ValueError):
        factory.state(
            mode="READY",
            reason_code="NONE",
            battery_valid=True,
            battery_percent=50.0,
            battery_state="NORMAL",
            health_block={"imu": "OK"},
        )


# --------------------------------------------------------------------------
# telemetry (§6)
# --------------------------------------------------------------------------


def telemetry_kwargs(**overrides):
    base = dict(
        position_valid=True,
        lat=37.4979,
        lon=127.0276,
        heading_deg=92.3,
        moving=True,
        battery_valid=True,
        battery_percent=67.4,
        battery_state="NORMAL",
        battery_source="MEASURED",
        jetson_temp_c=41.5,
        mission_id="MIS-20260726-001",
        mission_state="RUNNING",
        mission_elapsed_sec=583,
        mission_incident_count=2,
    )
    base.update(overrides)
    return base


def test_telemetry_full(factory):
    payload = factory.telemetry(**telemetry_kwargs())
    assert list(payload) == [
        "schema_version",
        "message_id",
        "mqtt_id",
        "boot_id",
        "seq",
        "sampled_at",
        "mission_id",
        "position",
        "motion",
        "battery",
        "system",
        "mission",
    ]
    assert is_uuid4(payload["message_id"])
    assert payload["position"] == {
        "valid": True,
        "lat": 37.4979,
        "lon": 127.0276,
        "heading_deg": 92.3,
    }
    assert payload["motion"] == {"moving": True, "speed_mps": None}
    assert payload["battery"]["source"] == "MEASURED"
    assert payload["system"] == {"jetson_temp_c": 41.5}
    assert payload["mission"] == {
        "state": "RUNNING",
        "elapsed_sec": 583,
        "incident_count": 2,
    }


def test_telemetry_drops_v1_only_members(factory):
    payload = factory.telemetry(**telemetry_kwargs())
    # v2 에서 삭제된 필드
    assert "environment" not in payload
    assert "accuracy_m" not in payload["position"]
    # 기기는 진행률·거리·ETA 를 보내지 않는다.
    for banned in (
        "progress_percent",
        "total_distance_m",
        "remaining_distance_m",
        "distance_m",
        "eta",
    ):
        assert banned not in payload["mission"]
        assert banned not in json.dumps(payload)


def test_telemetry_speed_is_always_null(factory):
    payload = factory.telemetry(**telemetry_kwargs(moving=False))
    assert payload["motion"] == {"moving": False, "speed_mps": None}


def test_telemetry_without_gps_fix_nulls_position(factory):
    payload = factory.telemetry(
        **telemetry_kwargs(position_valid=False, lat=37.4979, lon=127.0276)
    )
    assert payload["position"] == {
        "valid": False,
        "lat": None,
        "lon": None,
        "heading_deg": None,
    }


def test_telemetry_heading_null_when_course_unavailable(factory):
    payload = factory.telemetry(**telemetry_kwargs(heading_deg=None))
    assert payload["position"]["heading_deg"] is None
    assert payload["position"]["valid"] is True


@pytest.mark.parametrize("heading", [-0.1, 360.0, 400.0])
def test_telemetry_rejects_out_of_range_heading(factory, heading):
    with pytest.raises(ValueError):
        factory.telemetry(**telemetry_kwargs(heading_deg=heading))


def test_telemetry_without_battery_reading(factory):
    payload = factory.telemetry(
        **telemetry_kwargs(
            battery_valid=False, battery_percent=None, battery_state="UNKNOWN"
        )
    )
    assert payload["battery"] == {
        "valid": False,
        "percent": None,
        "state": "UNKNOWN",
        "source": None,
    }


def test_telemetry_without_mission(factory):
    payload = factory.telemetry(
        **telemetry_kwargs(
            mission_id=None,
            mission_state=None,
            mission_elapsed_sec=None,
            mission_incident_count=None,
        )
    )
    assert payload["mission_id"] is None
    assert payload["mission"] is None


def test_telemetry_optional_battery_members(factory):
    payload = factory.telemetry(
        **telemetry_kwargs(battery_extra={"voltage_v": 15.8, "charging": False})
    )
    assert payload["battery"]["voltage_v"] == 15.8
    assert payload["battery"]["charging"] is False


def test_telemetry_rejects_unknown_battery_member(factory):
    with pytest.raises(ValueError):
        factory.telemetry(**telemetry_kwargs(battery_extra={"cells_v": [3.7, 3.8]}))


def test_telemetry_percent_out_of_range(factory):
    with pytest.raises(ValueError):
        factory.telemetry(**telemetry_kwargs(battery_percent=120.0))


# --------------------------------------------------------------------------
# events/incident (§8)
# --------------------------------------------------------------------------


def incident_kwargs(**overrides):
    base = dict(
        occurred_at="2026-07-26T05:15:34.231Z",
        mission_id="MIS-20260726-001",
        location_valid=True,
        lat=37.4979,
        lon=127.0276,
        zone_id="A-01",
        block_type="LINEAR",
        confidence=0.87,
        damage_ratio=0.32,
        image_id="550e8400-e29b-41d4-a716-446655440000",
        captured_at="2026-07-26T05:15:34.120Z",
        image_width=1280,
        image_height=720,
        bbox_norm=[0.16, 0.42, 0.81, 0.95],
        model_name="tbs-braille-yolo",
        model_version="1.0.0",
    )
    base.update(overrides)
    return base


def test_incident_event(factory):
    payload = factory.incident_event(**incident_kwargs())
    assert payload["event_type"] == "BRAILLE_BLOCK_INCIDENT_DETECTED"
    assert payload["source_component"] == "ai"
    assert is_uuid4(payload["event_id"])
    assert payload["occurred_at"] == "2026-07-26T05:15:34.231Z"
    assert ISO_PATTERN.match(payload["sent_at"])
    assert payload["ttl_sec"] == EVENT_TTL_DEFAULT_SEC
    assert payload["location"] == {
        "valid": True,
        "lat": 37.4979,
        "lon": 127.0276,
        "zone_id": "A-01",
    }
    assert payload["incident"] == {
        "asset_type": "BRAILLE_BLOCK",
        "block_type": "LINEAR",
        "defect_type": "DAMAGED",
        "confidence": 0.87,
        "attributes": {"damage_ratio": 0.32},
    }
    assert payload["evidence"]["bbox_norm"] == [0.16, 0.42, 0.81, 0.95]
    assert payload["model"] == {"name": "tbs-braille-yolo", "version": "1.0.0"}


def test_incident_zone_id_is_echoed_verbatim(factory):
    # zone_id 는 START_MISSION 이 준 값을 그대로 싣는다. 좌표로 계산하지 않고
    # 형식 검증도 하지 않는다 (웹 DB CHECK 제약이지 MQTT 계약이 아님).
    payload = factory.incident_event(**incident_kwargs(zone_id="zone-임시-7"))
    assert payload["location"]["zone_id"] == "zone-임시-7"


def test_incident_without_gps_still_publishes(factory):
    payload = factory.incident_event(
        **incident_kwargs(location_valid=False, lat=None, lon=None)
    )
    assert payload["location"] == {
        "valid": False,
        "lat": None,
        "lon": None,
        "zone_id": "A-01",
    }


def test_incident_without_image_id(factory):
    payload = factory.incident_event(**incident_kwargs(image_id=None))
    assert payload["evidence"]["image_id"] is None


def test_incident_without_damage_ratio_omits_attributes(factory):
    payload = factory.incident_event(**incident_kwargs(damage_ratio=None))
    assert "attributes" not in payload["incident"]


@pytest.mark.parametrize(
    "kwargs",
    [
        {"block_type": "STRIPE"},
        {"defect_type": "CONTAMINATED"},  # 예약 값 — MVP 는 발행하지 않는다
        {"confidence": 1.4},
        {"bbox_norm": [0.1, 0.2, 0.3]},
        {"bbox_norm": [0.9, 0.2, 0.3, 0.4]},
        # 서버 DTO 는 x_min < x_max, y_min < y_max 인 강한 부등식을 요구한다.
        # 폭·높이가 0 인 상자는 MQTT_PAYLOAD_VALIDATION_FAILED 로 조용히 버려진다.
        {"bbox_norm": [0.3, 0.2, 0.3, 0.4]},
        {"bbox_norm": [0.1, 0.4, 0.3, 0.4]},
        {"lat": None},
        {"lon": None},
    ],
)
def test_incident_rejects_invalid_values(factory, kwargs):
    with pytest.raises(ValueError):
        factory.incident_event(**incident_kwargs(**kwargs))


# --------------------------------------------------------------------------
# events/battery (§9)
# --------------------------------------------------------------------------


def test_battery_event(factory):
    payload = factory.battery_event(
        event_type="BATTERY_LOW",
        occurred_at="2026-07-26T05:18:10.000Z",
        mission_id="MIS-20260726-001",
        previous_state="NORMAL",
        battery_valid=True,
        battery_percent=19.8,
        battery_state="LOW",
        threshold_pct=20.0,
        action_mission_state="PAUSED",
        action_robot_mode="SAFE_STOP",
    )
    assert payload["source_component"] == "power"
    assert payload["previous_state"] == "NORMAL"
    assert payload["battery"] == {"valid": True, "percent": 19.8, "state": "LOW"}
    assert payload["threshold_pct"] == 20.0
    assert payload["action_taken"] == {
        "mission_state": "PAUSED",
        "robot_mode": "SAFE_STOP",
    }


def test_battery_event_sensor_fault_nulls(factory):
    payload = factory.battery_event(
        event_type="BATTERY_SENSOR_FAULT",
        occurred_at="2026-07-26T05:18:10.000Z",
        previous_state="NORMAL",
        battery_valid=False,
        battery_percent=None,
        battery_state="UNKNOWN",
        threshold_pct=None,
        action_mission_state=None,
        action_robot_mode="SAFE_STOP",
    )
    assert payload["mission_id"] is None
    assert payload["threshold_pct"] is None
    assert payload["action_taken"]["mission_state"] is None
    assert payload["battery"]["percent"] is None


def test_battery_event_rejects_recovered_as_state(factory):
    # RECOVERED 는 배터리 상태가 아니라 event_type 이다.
    with pytest.raises(ValueError):
        factory.battery_event(
            event_type="BATTERY_RECOVERED",
            occurred_at="2026-07-26T05:18:10.000Z",
            previous_state="LOW",
            battery_valid=True,
            battery_percent=42.0,
            battery_state="RECOVERED",
            threshold_pct=25.0,
            action_robot_mode="PATROLLING",
        )


# --------------------------------------------------------------------------
# events/safety (§10)
# --------------------------------------------------------------------------


def test_safety_stopped(factory):
    payload = factory.safety_event(
        event_type="SAFETY_STOPPED",
        occurred_at="2026-07-26T05:19:00.410Z",
        mission_id="MIS-20260726-001",
        reason_code="OBSTACLE",
        distance_m=0.72,
        mode_before="PATROLLING",
        mode_after="SAFE_STOP",
        auto_resume_allowed=True,
    )
    assert payload["source_component"] == "safety"
    assert payload["measurement"] == {"distance_m": 0.72}
    assert payload["transition"] == {
        "mode_before": "PATROLLING",
        "mode_after": "SAFE_STOP",
    }
    assert payload["auto_resume_allowed"] is True


def test_safety_event_without_distance(factory):
    payload = factory.safety_event(
        event_type="SENSOR_FAULT",
        occurred_at="2026-07-26T05:19:00.410Z",
        reason_code="LIDAR_FAULT",
        mode_before="PATROLLING",
        mode_after="ERROR",
        auto_resume_allowed=False,
    )
    # 해당 없는 측정값은 0 이 아니라 null.
    assert payload["measurement"] == {"distance_m": None}


def test_safety_event_rejects_unknown_reason(factory):
    with pytest.raises(ValueError):
        factory.safety_event(
            event_type="SAFETY_STOPPED",
            occurred_at="2026-07-26T05:19:00.410Z",
            reason_code="NONE",  # state 전용 값 — safety reason_code 아님
            mode_before="PATROLLING",
            mode_after="SAFE_STOP",
            auto_resume_allowed=True,
        )


# --------------------------------------------------------------------------
# events/mission (§11)
# --------------------------------------------------------------------------


def test_mission_completed_has_result(factory):
    payload = factory.mission_event(
        event_type="MISSION_COMPLETED",
        occurred_at="2026-07-26T06:20:00.000Z",
        mission_id="MIS-20260726-001",
        duration_sec=4182,
        incident_count=7,
        normal_detection_count=143,
        completion_reason="ROUTE_COMPLETED",
    )
    assert payload["source_component"] == "mission"
    assert payload["result"] == {
        "duration_sec": 4182,
        "incident_count": 7,
        "normal_detection_count": 143,
        "completion_reason": "ROUTE_COMPLETED",
    }
    # v2 에서 삭제·제외된 값
    assert "distance_m" not in payload["result"]
    assert "progress_percent" not in payload["result"]


def test_mission_paused_omits_result(factory):
    payload = factory.mission_event(
        event_type="MISSION_PAUSED",
        occurred_at="2026-07-26T05:30:00.000Z",
        mission_id="MIS-20260726-001",
    )
    assert "result" not in payload
    assert list(payload) == [
        "schema_version",
        "event_id",
        "event_type",
        "mqtt_id",
        "boot_id",
        "seq",
        "source_component",
        "occurred_at",
        "sent_at",
        "ttl_sec",
        "mission_id",
    ]


@pytest.mark.parametrize(
    "ttl_sec",
    [EVENT_TTL_MIN_SEC, EVENT_TTL_MAX_SEC],
)
def test_event_ttl_accepts_contract_boundaries(ttl_sec):
    factory = MessageFactory(ROBOT_ID, event_ttl_sec=ttl_sec)
    payload = factory.mission_event(
        event_type="MISSION_PAUSED",
        occurred_at="2026-07-26T05:30:00.000Z",
        mission_id="MIS-20260726-001",
    )
    assert payload["ttl_sec"] == ttl_sec


@pytest.mark.parametrize(
    "ttl_sec",
    [
        EVENT_TTL_MIN_SEC - 1,
        EVENT_TTL_MAX_SEC + 1,
        60.0,
        True,
    ],
)
def test_event_ttl_rejects_values_outside_contract(ttl_sec):
    with pytest.raises(ValueError, match="event_ttl_sec"):
        MessageFactory(ROBOT_ID, event_ttl_sec=ttl_sec)


def test_mission_started_must_not_carry_result(factory):
    with pytest.raises(ValueError):
        factory.mission_event(
            event_type="MISSION_STARTED",
            occurred_at="2026-07-26T05:30:00.000Z",
            mission_id="MIS-1",
            duration_sec=0,
        )


def test_mission_completed_requires_full_result(factory):
    with pytest.raises(ValueError):
        factory.mission_event(
            event_type="MISSION_FAILED",
            occurred_at="2026-07-26T05:30:00.000Z",
            mission_id="MIS-1",
            duration_sec=10,
            incident_count=0,
        )


# --------------------------------------------------------------------------
# commands/result (§13)
# --------------------------------------------------------------------------


def test_command_result_completed(factory):
    payload = factory.command_result(
        command_id="95626222-c5c8-47ea-901e-f9e467b275f6",
        status="COMPLETED",
        received_at="2026-07-26T05:21:00.130Z",
        completed_at="2026-07-26T05:21:00.240Z",
        reason_code="OK",
        mode="SAFE_STOP",
        state_reason_code="REMOTE_ESTOP",
    )
    assert list(payload) == [
        "schema_version",
        "result_id",
        "command_id",
        "mqtt_id",
        "status",
        "received_at",
        "completed_at",
        "reason_code",
        "state",
    ]
    assert is_uuid4(payload["result_id"])
    assert payload["state"] == {"mode": "SAFE_STOP", "reason_code": "REMOTE_ESTOP"}
    # command result 는 seq 를 쓰지 않는다.
    assert "seq" not in payload
    assert "boot_id" not in payload


def test_command_result_accepted_has_null_completed_at(factory):
    payload = factory.command_result(
        command_id="cmd-1",
        status="ACCEPTED",
        received_at="2026-07-26T05:21:00.130Z",
        reason_code="OK",
        mode="PATROLLING",
        state_reason_code="NONE",
    )
    assert payload["completed_at"] is None


def test_command_result_accepted_rejects_completed_at(factory):
    with pytest.raises(ValueError):
        factory.command_result(
            command_id="cmd-1",
            status="ACCEPTED",
            received_at="2026-07-26T05:21:00.130Z",
            completed_at="2026-07-26T05:21:00.240Z",
            reason_code="OK",
            mode="PATROLLING",
            state_reason_code="NONE",
        )


def test_command_result_rejects_completed_at_before_received_at(factory):
    """서버 DTO 는 completed_at >= received_at 도 함께 요구한다 (§13).

    시계가 뒤로 튀면(NTP 스텝) 여기서 안 막힌 result 가 조용히 거부되고,
    관제 화면의 명령은 영원히 ACCEPTED 에 멈춘다.
    """
    with pytest.raises(ValueError):
        factory.command_result(
            command_id="cmd-1",
            status="COMPLETED",
            received_at="2026-07-26T05:21:00.240Z",
            completed_at="2026-07-26T05:21:00.130Z",
            reason_code="OK",
            mode="PATROLLING",
            state_reason_code="NONE",
        )


def test_command_result_allows_the_same_instant(factory):
    """>= 라 같은 시각은 통과한다 — 즉시 끝나는 명령이 여기서 걸리면 안 된다."""
    payload = factory.command_result(
        command_id="cmd-1",
        status="COMPLETED",
        received_at="2026-07-26T05:21:00.130Z",
        completed_at="2026-07-26T05:21:00.130Z",
        reason_code="OK",
        mode="PATROLLING",
        state_reason_code="NONE",
    )
    assert payload["completed_at"] == payload["received_at"]


def test_command_result_ids_differ_for_same_command(factory):
    common = dict(
        command_id="cmd-1",
        received_at="2026-07-26T05:21:00.130Z",
        reason_code="OK",
        mode="PATROLLING",
        state_reason_code="NONE",
    )
    accepted = factory.command_result(status="ACCEPTED", **common)
    completed = factory.command_result(
        status="COMPLETED", completed_at="2026-07-26T05:21:00.240Z", **common
    )
    assert accepted["result_id"] != completed["result_id"]
    assert accepted["command_id"] == completed["command_id"]


# --------------------------------------------------------------------------
# boot_id / seq  (DoD 5)
# --------------------------------------------------------------------------


def test_seq_is_shared_and_monotonic_within_boot(factory):
    """state·telemetry·event 가 하나의 순번을 나눠 쓴다."""
    seqs = []
    for _ in range(3):
        seqs.append(
            factory.state(
                mode="PATROLLING",
                reason_code="NONE",
                battery_valid=True,
                battery_percent=50.0,
                battery_state="NORMAL",
                health_block=health_ok(),
            )["seq"]
        )
        seqs.append(factory.telemetry(**telemetry_kwargs())["seq"])
        seqs.append(factory.incident_event(**incident_kwargs())["seq"])
        seqs.append(
            factory.mission_event(
                event_type="MISSION_RESUMED",
                occurred_at="2026-07-26T05:30:00.000Z",
                mission_id="MIS-1",
            )["seq"]
        )
    assert seqs == list(range(1, len(seqs) + 1))


def test_seq_resets_and_boot_id_changes_on_reboot():
    first = MessageFactory(ROBOT_ID)
    for _ in range(5):
        first.telemetry(**telemetry_kwargs())
    assert first.seq == 5

    rebooted = MessageFactory(ROBOT_ID)  # 프로세스 재시작 = 새 boot_id
    assert rebooted.boot_id != first.boot_id
    assert is_uuid4(rebooted.boot_id)
    assert rebooted.telemetry(**telemetry_kwargs())["seq"] == 1


def test_boot_id_is_stable_across_messages(factory):
    payloads = [
        factory.presence_online(),
        factory.telemetry(**telemetry_kwargs()),
        factory.incident_event(**incident_kwargs()),
    ]
    assert {p["boot_id"] for p in payloads} == {factory.boot_id}


def test_seq_is_thread_safe():
    import threading

    factory = MessageFactory(ROBOT_ID)
    seqs = []
    lock = threading.Lock()

    def worker():
        local = [factory.next_seq() for _ in range(200)]
        with lock:
            seqs.extend(local)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert sorted(seqs) == list(range(1, 1601))


# --------------------------------------------------------------------------
# 직렬화 규칙
# --------------------------------------------------------------------------


def test_encode_is_utf8_json_without_comments(factory):
    payload = factory.incident_event(**incident_kwargs(zone_id="구역-01"))
    raw = encode(payload)
    assert isinstance(raw, bytes)
    text = raw.decode("utf-8")
    assert "//" not in text
    assert "구역-01" in text  # 한글은 escape 하지 않는다
    assert decode(raw) == payload


def test_all_timestamps_are_iso_utc_z(factory):
    payload = factory.state(
        mode="READY",
        reason_code="NONE",
        battery_valid=True,
        battery_percent=50.0,
        battery_state="NORMAL",
        health_block=health_ok(),
    )
    assert ISO_PATTERN.match(payload["updated_at"])
    assert ISO_PATTERN.match(utc_now())


def test_to_iso_rejects_naive_datetime():
    from datetime import datetime

    with pytest.raises(ValueError):
        to_iso(datetime(2026, 7, 26, 5, 15, 35))


def test_to_iso_converts_to_utc():
    from datetime import datetime, timedelta, timezone

    kst = timezone(timedelta(hours=9))
    assert (
        to_iso(datetime(2026, 7, 26, 14, 15, 35, 123456, tzinfo=kst))
        == "2026-07-26T05:15:35.123Z"
    )


# --------------------------------------------------------------------------
# 입력 검증 — 잘못된 인자는 ValueError 이고 seq 를 소모하지 않는다
# --------------------------------------------------------------------------


def state_kwargs(**overrides):
    base = dict(
        mode="PATROLLING",
        reason_code="NONE",
        battery_valid=True,
        battery_percent=50.0,
        battery_state="NORMAL",
        health_block=health_ok(),
    )
    base.update(overrides)
    return base


def battery_event_kwargs(**overrides):
    base = dict(
        event_type="BATTERY_LOW",
        occurred_at="2026-07-26T05:18:10.000Z",
        previous_state="NORMAL",
        battery_valid=True,
        battery_percent=19.8,
        battery_state="LOW",
        threshold_pct=20.0,
        action_robot_mode="SAFE_STOP",
    )
    base.update(overrides)
    return base


def safety_event_kwargs(**overrides):
    base = dict(
        event_type="SAFETY_STOPPED",
        occurred_at="2026-07-26T05:19:00.410Z",
        reason_code="OBSTACLE",
        mode_before="PATROLLING",
        mode_after="SAFE_STOP",
        auto_resume_allowed=True,
    )
    base.update(overrides)
    return base


@pytest.mark.parametrize("health_block", [None, "OK", 3, ["camera"]])
def test_state_rejects_non_object_health_block(factory, health_block):
    with pytest.raises(ValueError):
        factory.state(**state_kwargs(health_block=health_block))
    assert factory.seq == 0


def test_state_rejection_leaves_no_gap_in_seq(factory):
    with pytest.raises(ValueError):
        factory.state(**state_kwargs(mode="NOPE"))
    assert factory.seq == 0
    assert factory.state(**state_kwargs())["seq"] == 1


def test_telemetry_rejection_leaves_no_gap_in_seq(factory):
    with pytest.raises(ValueError):
        factory.telemetry(**telemetry_kwargs(battery_percent=120.0))
    assert factory.seq == 0
    assert factory.telemetry(**telemetry_kwargs())["seq"] == 1


@pytest.mark.parametrize(
    "call",
    [
        lambda f: f.incident_event(**incident_kwargs(block_type="STRIPE")),
        lambda f: f.battery_event(**battery_event_kwargs(event_type="BATTERY_DEAD")),
        lambda f: f.safety_event(**safety_event_kwargs(reason_code="NONE")),
        lambda f: f.mission_event(
            event_type="MISSION_STARTED",
            occurred_at="2026-07-26T05:30:00.000Z",
            mission_id="MIS-1",
            duration_sec=0,
        ),
    ],
    ids=["incident", "battery", "safety", "mission"],
)
def test_event_rejection_leaves_no_gap_in_seq(factory, call):
    with pytest.raises(ValueError):
        call(factory)
    assert factory.seq == 0
    payload = factory.mission_event(
        event_type="MISSION_PAUSED",
        occurred_at="2026-07-26T05:30:00.000Z",
        mission_id="MIS-1",
    )
    assert payload["seq"] == 1


def test_telemetry_valid_battery_requires_source(factory):
    with pytest.raises(ValueError):
        factory.telemetry(**telemetry_kwargs(battery_source=None))
    assert factory.seq == 0


# --------------------------------------------------------------------------
# 계층 분리 — 계약·조립 모듈은 paho·yaml 없이 import 된다
# --------------------------------------------------------------------------


def test_pure_modules_import_without_paho_or_yaml():
    import importlib
    import sys

    blocked = ("paho", "yaml")

    class Blocker:
        def find_spec(self, fullname, path=None, target=None):
            if fullname.split(".")[0] in blocked:
                raise ImportError(f"{fullname} blocked")
            return None

    saved_meta_path = list(sys.meta_path)
    saved_modules = dict(sys.modules)
    try:
        for name in list(sys.modules):
            root = name.split(".")[0]
            if root == "comms" or root in blocked:
                del sys.modules[name]
        sys.meta_path.insert(0, Blocker())
        for name in (
            "comms.contract",
            "comms.codec",
            "comms.topics",
            "comms.blocks",
            "comms.factory",
        ):
            importlib.import_module(name)
        assert not any(m.split(".")[0] in blocked for m in sys.modules)
        with pytest.raises(ImportError):
            importlib.import_module("comms.client")
    finally:
        sys.meta_path[:] = saved_meta_path
        sys.modules.clear()
        sys.modules.update(saved_modules)


def test_lazy_attributes_still_resolve_from_package():
    import comms

    assert comms.MqttClient is comms.client.MqttClient
    assert comms.ConfigError is comms.config.ConfigError
    assert comms.OutboxRecord is comms.outbox.OutboxRecord
    assert dir(comms) == sorted(comms.__all__)
