"""topic 문자열과 발행 정책(QoS·retain)."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from .contract import NAMESPACE


class TopicKind(str, Enum):
    """topic 의 논리 이름. 발행 정책(QoS·retain) 도 이 키로 찾는다."""

    PRESENCE = "presence"
    STATE = "state"
    TELEMETRY = "telemetry"
    EVENT_INCIDENT = "events/incident"
    EVENT_BATTERY = "events/battery"
    EVENT_SAFETY = "events/safety"
    EVENT_MISSION = "events/mission"
    COMMAND_RESULT = "commands/result"
    COMMAND_REQUEST = "commands/request"
    INGEST_RECEIPT = "ingest/receipt"


#: 로봇 → 서버 발행 topic 8종
PUBLISH_KINDS = (
    TopicKind.PRESENCE,
    TopicKind.STATE,
    TopicKind.TELEMETRY,
    TopicKind.EVENT_INCIDENT,
    TopicKind.EVENT_BATTERY,
    TopicKind.EVENT_SAFETY,
    TopicKind.EVENT_MISSION,
    TopicKind.COMMAND_RESULT,
)

#: Outbox 대상 — 유실되면 안 되는 사건 4종
EVENT_KINDS = frozenset(
    {
        TopicKind.EVENT_INCIDENT,
        TopicKind.EVENT_BATTERY,
        TopicKind.EVENT_SAFETY,
        TopicKind.EVENT_MISSION,
    }
)


def topic(robot_id: str, kind: TopicKind) -> str:
    """tbs/v2 topic 문자열을 만드는 유일한 함수."""
    if not robot_id:
        raise ValueError("robot_id is required")
    return f"{NAMESPACE}/robots/{robot_id}/{TopicKind(kind).value}"


class Topics:
    def __init__(self, robot_id: str) -> None:
        self.robot_id = robot_id

    def of(self, kind: TopicKind) -> str:
        return topic(self.robot_id, kind)


# --------------------------------------------------------------------------
# 발행 정책 — QoS·retain 은 여기 한 곳에서만 정한다 (계약 §2.1, §3.3, §16.3)
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Policy:
    qos: int
    retain: bool


PUBLISH_POLICY = {
    TopicKind.PRESENCE: Policy(qos=1, retain=True),
    TopicKind.STATE: Policy(qos=1, retain=True),
    TopicKind.TELEMETRY: Policy(qos=0, retain=False),
    TopicKind.EVENT_INCIDENT: Policy(qos=1, retain=False),
    TopicKind.EVENT_BATTERY: Policy(qos=1, retain=False),
    TopicKind.EVENT_SAFETY: Policy(qos=1, retain=False),
    TopicKind.EVENT_MISSION: Policy(qos=1, retain=False),
    TopicKind.COMMAND_RESULT: Policy(qos=1, retain=False),
}

#: 서버 → 로봇 구독 QoS. retain 은 쓰지 않는다.
SUBSCRIBE_QOS = 1
