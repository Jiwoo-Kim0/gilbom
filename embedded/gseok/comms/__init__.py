"""tbs/v2 통신 계층 — 계약 상수·payload 조립·topic 문자열·MQTT 클라이언트·Outbox·설정 로더.  [F-006]

발행 payload 에는 주석을 넣지 않는다 — 여기 dict 를 JSON 으로 직렬화한 결과가 곧 계약이다.
paho·yaml 을 끌어오는 하위 모듈(client·config·outbox)은 실제로 참조될 때 import 한다 (PEP 562).
기준 문서: web/docs/final-mqtt-topic-schema-v2.md
"""

from importlib import import_module

from .blocks import health
from .codec import decode, encode, new_uuid, to_iso, utc_now
from .factory import MessageFactory
from .topics import EVENT_KINDS, PUBLISH_KINDS, PUBLISH_POLICY, TopicKind, Topics, topic

__all__ = [
    "MessageFactory",
    "MqttClient",
    "MqttConfig",
    "Outbox",
    "OutboxRecord",
    "TopicKind",
    "Topics",
    "ConfigError",
    "PUBLISH_POLICY",
    "PUBLISH_KINDS",
    "EVENT_KINDS",
    "encode",
    "decode",
    "utc_now",
    "to_iso",
    "new_uuid",
    "health",
    "topic",
    "load_document",
]

_LAZY = {
    "MqttClient": ".client",
    "MqttConfig": ".config",
    "ConfigError": ".config",
    "load_document": ".config",
    "Outbox": ".outbox",
    "OutboxRecord": ".outbox",
}


def __getattr__(name: str):
    module = _LAZY.get(name)
    if module is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    value = getattr(import_module(module, __name__), name)
    globals()[name] = value
    return value


def __dir__():
    return list(__all__)
