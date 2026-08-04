"""tbs/v2 계약 상수 — namespace·schema_version·enum."""

NAMESPACE = "tbs/v2"
SCHEMA_VERSION = "2.0"
EVENT_TTL_MIN_SEC = 60
EVENT_TTL_MAX_SEC = 300
EVENT_TTL_DEFAULT_SEC = 300


# --------------------------------------------------------------------------
# Enum (계약 §4~§13)
# --------------------------------------------------------------------------

# presence.status 는 ONLINE·OFFLINE 두 가지 — 빌더가 직접 넣는다.
OFFLINE_REASONS = frozenset(
    {
        "GRACEFUL_SHUTDOWN",
        "CRITICAL_BATTERY_SHUTDOWN",
        "SYSTEM_FAULT_SHUTDOWN",
        "UNGRACEFUL_DISCONNECT",
    }
)

# 예약 mode RETURNING_TO_CHARGE·CHARGING 은 MVP 에서 발행하지 않는다.
MODES = frozenset(
    {
        "BOOTING",
        "READY",
        "PATROLLING",
        "PAUSED",
        "SAFE_STOP",
        "ERROR",
        "SHUTTING_DOWN",
    }
)
REASON_CODES = frozenset(
    {
        "NONE",
        "REMOTE_PAUSE",
        "REMOTE_ESTOP",
        "OBSTACLE",
        "BOUNDARY_LOST",
        "LOW_BATTERY",
        "CRITICAL_BATTERY",
        "LIDAR_FAULT",
        "MOTOR_FAULT",
        "INTERNAL_ERROR",
    }
)
MISSION_STATES = frozenset(
    {"READY", "RUNNING", "PAUSED", "COMPLETED", "CANCELLED", "FAILED"}
)
BATTERY_STATES = frozenset({"NORMAL", "LOW", "CRITICAL", "UNKNOWN"})
BATTERY_SOURCES = frozenset({"MEASURED", "ESTIMATED"})
HEALTH_STATES = frozenset({"OK", "DEGRADED", "FAULT", "UNKNOWN"})
HEALTH_MEMBERS = ("camera", "lidar", "gps", "battery", "motor", "storage")

SOURCE_COMPONENTS = frozenset({"ai", "power", "safety", "mission"})

#: incident.location.zone_id 에 서버가 거는 패턴 (예: "A-01").
#: 로봇은 START_MISSION 이 준 값을 그대로 echo 하므로 이 패턴을 만들지는 않는다 —
#: 다만 벗어난 값이 오면 그 미션의 incident 가 전부 조용히 거부되므로 미리 경고한다.
#: ⚠️ 서버 patrol_area.area_code 에는 아직 같은 제약이 없다 (웹팀 확인 대기).
ZONE_ID_PATTERN = r"^[A-Z]-[0-9]{2}$"

#: 증거 이미지 규격 (계약 §8). 서버가 거는 제약이라 우리 튜닝값이 아니다 —
#: 생성(evidence.py)과 업로드 게이트(comms/uploader.py)가 같은 값을 봐야 하므로
#: 여기가 단일 출처다. 한쪽만 고치면 config 는 큰 파일을 만들고 uploader 는 그걸
#: 조용히 거르는, 로그 한 줄로만 남는 상태가 된다.
EVIDENCE_CONTENT_TYPE = "image/jpeg"
EVIDENCE_MAX_BYTES = 500 * 1024
EVIDENCE_LONG_EDGE_PX = 1280
EVIDENCE_JPEG_QUALITY = 80

INCIDENT_EVENT_TYPE = "BRAILLE_BLOCK_INCIDENT_DETECTED"
ASSET_TYPE_BRAILLE_BLOCK = "BRAILLE_BLOCK"
BLOCK_TYPES = frozenset({"LINEAR", "DOT", "UNKNOWN"})
#: MVP 는 두 값이다. 예약 값은 발행하지 않는다.
#: MISSING 은 횡단보도-인도 이음부에 점자블록이 **없다** 는 보고다 (명세 5단계).
DEFECT_TYPES = frozenset({"DAMAGED", "MISSING"})

#: bbox_norm·confidence 가 null 로 나가는 defect_type (계약 §8.3 확장).
#: 없는 물건은 화면에서 가리킬 수 없고, 판정도 세그멘테이션 면적비 임계값이라
#: 모델 점수가 없다 — 두 값을 지어내면 관제의 심각도 판단이 거짓이 된다.
#: 이 집합에 없는 defect_type 은 둘 다 필수다 (factory.incident_event 가 막는다).
UNLOCALIZED_DEFECT_TYPES = frozenset({"MISSING"})

BATTERY_EVENT_TYPES = frozenset(
    {
        "BATTERY_LOW",
        "BATTERY_CRITICAL",
        "BATTERY_RECOVERED",
        "BATTERY_SENSOR_FAULT",
        "BATTERY_SENSOR_RECOVERED",
    }
)
SAFETY_EVENT_TYPES = frozenset(
    {"SAFETY_STOPPED", "SAFETY_CLEARED", "SENSOR_FAULT", "SENSOR_RECOVERED"}
)
SAFETY_REASON_CODES = frozenset(
    {
        "OBSTACLE",
        "BOUNDARY_LOST",
        "REMOTE_ESTOP",
        "LOW_BATTERY",
        "CRITICAL_BATTERY",
        "LIDAR_FAULT",
        "MOTOR_FAULT",
        "INTERNAL_ERROR",
    }
)
MISSION_EVENT_TYPES = frozenset(
    {
        "MISSION_STARTED",
        "MISSION_PAUSED",
        "MISSION_RESUMED",
        "MISSION_COMPLETED",
        "MISSION_CANCELLED",
        "MISSION_FAILED",
    }
)
#: result 블록을 싣는 미션 종료 event_type
MISSION_TERMINAL_EVENT_TYPES = frozenset(
    {"MISSION_COMPLETED", "MISSION_CANCELLED", "MISSION_FAILED"}
)

COMMAND_STATUSES = frozenset({"ACCEPTED", "COMPLETED", "FAILED", "REJECTED", "EXPIRED"})

#: 하드웨어가 실제로 제공할 때만 telemetry battery 에 실을 수 있는 선택 멤버 (이름은 v1 과 동일)
BATTERY_OPTIONAL_MEMBERS = frozenset(
    {
        "voltage_v",
        "current_a",
        "charging",
        "battery_temperature_c",
        "estimated_runtime_sec",
    }
)
