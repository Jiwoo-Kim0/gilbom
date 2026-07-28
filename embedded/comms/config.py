"""config.yaml 로드와 MQTT 접속 설정."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass

import yaml


class ConfigError(Exception):
    pass


_PLACEHOLDER = re.compile(r"^\$\{([^}]+)\}$")


def _resolve(node, document):
    """`${env:NAME}` 은 환경변수, `${robot.id}` 는 문서 안의 다른 값으로 치환한다.

    문자열 전체가 placeholder 인 경우만 치환한다 — 부분 치환은 지원하지 않는다.
    """
    if isinstance(node, dict):
        return {key: _resolve(value, document) for key, value in node.items()}
    if isinstance(node, list):
        return [_resolve(item, document) for item in node]
    if not isinstance(node, str):
        return node
    match = _PLACEHOLDER.match(node.strip())
    if not match:
        return node
    ref = match.group(1).strip()
    if ref.startswith("env:"):
        name = ref[4:].strip()
        if name not in os.environ:
            raise ConfigError(f"environment variable {name} is not set (${{{ref}}})")
        return os.environ[name]
    value = document
    for part in ref.split("."):
        if not isinstance(value, dict) or part not in value:
            raise ConfigError(f"config reference ${{{ref}}} cannot be resolved")
        value = value[part]
    return _resolve(value, document)


def load_document(path) -> dict:
    with open(path, "r", encoding="utf-8") as handle:
        document = yaml.safe_load(handle) or {}
    if not isinstance(document, dict):
        raise ConfigError(f"{path}: mapping expected at the top level")
    return _resolve(document, document)


@dataclass(frozen=True)
class MqttConfig:
    robot_id: str
    host: str
    port: int = 8883
    username: str = None
    password: str = None
    keepalive: int = 30
    ca_cert: str = None
    outbox_path: str = "var/outbox.sqlite3"
    #: MQTT client_id. 기본은 robot_id — 로봇마다 다르므로 그대로 두면 충돌하지 않는다.
    #: 계정을 공유하는 테스트에서는 접속마다 다른 값을 줘야 한다. 같은 client_id 로
    #: 두 번 붙으면 브로커가 먼저 붙은 쪽을 끊는다("session taken over").
    #: username 은 ACL 때문에 robot_id 와 같아야 하지만 client_id 는 상관없다.
    client_id: str = None

    @property
    def tls_enabled(self) -> bool:
        """ca_cert 가 있으면 TLS. 없으면 평문 — 로컬 검증 전용이다."""
        return bool(self.ca_cert)

    @classmethod
    def from_document(cls, document: dict) -> "MqttConfig":
        try:
            robot_id = document["robot"]["id"]
            section = dict(document["mqtt"])
        except (KeyError, TypeError) as exc:
            raise ConfigError("config must define robot.id and the mqtt section") from exc
        if not robot_id:
            raise ConfigError("robot.id is required")

        username = section.pop("username", robot_id)
        # robot.id / username / topic 세그먼트는 갈라지면 안 된다.
        # 갈라지면 브로커 ACL 과 payload 검증(계약 §3.4)이 동시에 깨진다.
        if username != robot_id:
            raise ConfigError(
                f"mqtt.username({username!r}) must equal robot.id({robot_id!r})"
            )
        known = {f for f in cls.__dataclass_fields__ if f != "robot_id"}
        unknown = set(section) - known
        if unknown:
            raise ConfigError(f"unknown mqtt option: {sorted(unknown)}")
        if "host" not in section or not section["host"]:
            raise ConfigError("mqtt.host is required")
        return cls(
            robot_id=robot_id,
            host=section["host"],
            port=int(section.get("port", 8883)),
            username=username,
            password=section.get("password"),
            keepalive=int(section.get("keepalive", 30)),
            ca_cert=section.get("ca_cert") or None,
            outbox_path=section.get("outbox_path") or "var/outbox.sqlite3",
            client_id=section.get("client_id") or robot_id,
        )

    @classmethod
    def load(cls, path) -> "MqttConfig":
        return cls.from_document(load_document(path))
