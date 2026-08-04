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

INCIDENT_EVENT_TYPE = "BRAILLE_BLOCK_INCIDENT_DETECTED"
ASSET_TYPE_BRAILLE_BLOCK = "BRAILLE_BLOCK"
BLOCK_TYPES = frozenset({"LINEAR", "DOT", "UNKNOWN"})
DEFECT_TYPES = frozenset({"DAMAGED"})  # MVP. 예약 값은 발행하지 않는다.

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
