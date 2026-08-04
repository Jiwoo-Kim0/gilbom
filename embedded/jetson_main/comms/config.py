"""config.yaml 로드와 MQTT 접속 설정."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

import yaml


class ConfigError(Exception):
    pass


#: ca_cert 의 특수값 — CA 파일 대신 OS 신뢰 저장소로 서버 인증서를 검증한다.
#: 운영 브로커(i15d101.p.ssafy.io:8883)는 Let's Encrypt 인증서라 이걸로 충분하다.
#: CA 파일 경로는 로컬 사설 CA 브로커를 검증할 때만 필요하다.
CA_SYSTEM = "system"


_PLACEHOLDER = re.compile(r"^\$\{([^}]+)\}$")


def _resolve(node, document):
    """`${env:NAME}` 은 환경변수, `${robot.id}` 는 문서 안의 다른 값으로 치환한다.

    `${env?:NAME}` 은 선택 환경변수다 — 없으면 죽지 않고 null 이 된다.
    `${env?:NAME|기본값}` 은 없을 때 쓸 값을 파일 안에 적어 둔다. 기본값을 코드에
    숨기지 않기 위한 것이다 — 설정을 읽는 사람이 여기서 다 볼 수 있어야 한다.

    기동에 반드시 있어야 하는 값(브로커 비번)은 `${env:}` 로 둔다. 없으면 즉시
    죽는 쪽이, 비어 있는 채로 떠서 무한 재접속만 반복하는 것보다 낫다.
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
    if ref.startswith("env?:"):
        name, _, fallback = ref[5:].partition("|")
        return os.environ.get(name.strip()) or (fallback.strip() or None)
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
    #: 브로커 CA 파일 경로, 또는 "system"(OS 신뢰 저장소 — Let's Encrypt 운영 브로커용).
    ca_cert: str = None
    outbox_path: str = "var/outbox.sqlite3"
    #: MQTT client_id. 기본은 robot_id — 로봇마다 다르므로 그대로 두면 충돌하지 않는다.
    #: 계정을 공유하는 테스트에서는 접속마다 다른 값을 줘야 한다. 같은 client_id 로
    #: 두 번 붙으면 브로커가 먼저 붙은 쪽을 끊는다("session taken over").
    #: username 은 ACL 때문에 robot_id 와 같아야 하지만 client_id 는 상관없다.
    client_id: str = None
    #: True면 Broker PUBACK이 아니라 Backend ingest/receipt까지 Event 완료로 본다.
    #: Backend 자동 Receipt 배포 전에는 False로 둔다.
    receipt_required: bool = False
    #: True 면 ca_cert 없이 기동하지 못한다. 환경변수 하나가 빠졌을 때 조용히 평문으로
    #: 뜨는 것을 막는다 — 평문은 브로커가 거부하므로 무한 재접속으로만 보인다.
    #: 운영·실기 배포는 항상 True. 로컬 mosquitto 검증에서만 끈다.
    require_tls: bool = False

    @property
    def tls_enabled(self) -> bool:
        """ca_cert 가 있으면 TLS. 없으면 평문 — 로컬 검증 전용이다."""
        return bool(self.ca_cert)

    @classmethod
    def from_document(cls, document: dict, base_dir=None) -> "MqttConfig":
        """`base_dir` 는 상대 ca_cert·outbox_path 를 푸는 기준이다 — config.yaml 이 있는 디렉터리.

        작업 디렉터리 기준으로 풀면 systemd 가 WorkingDirectory 없이 띄웠을 때
        인증서를 못 찾아 접속이 통째로 실패한다. CA 는 config 옆에 두고 함께 옮긴다.

        outbox_path 도 같은 이유로 여기서 푼다. 작업 디렉터리 기준이면 재시작 때
        WorkingDirectory 가 달라진 순간 빈 sqlite 가 새로 생기고, 이전 실행이 남긴
        미전송 event 는 디스크에 있는 채로 영영 발행되지 않는다.
        """
        try:
            robot_id = document["robot"]["id"]
            section = dict(document["mqtt"])
        except (KeyError, TypeError) as exc:
            raise ConfigError("config must define robot.id and the mqtt section") from exc
        if not robot_id:
            raise ConfigError("robot.id is required")

        # 값을 안 주면 robot.id 를 쓴다. 발급 응답이 username 을 따로 주므로
        # 항목 자체는 분리해 두고, 같은지만 검사한다.
        username = section.pop("username", None) or robot_id
        # mqtt_id / username / topic 세그먼트는 갈라지면 안 된다. 브로커 ACL 이
        # %u 를 mqtt_id 에 묶어 두었고 서버는 topic 과 payload 의 mqtt_id 를 대조한다 —
        # 갈라지면 접속은 되는데 발행이 전부 거부되는, 찾기 어려운 상태가 된다.
        if username != robot_id:
            raise ConfigError(
                f"mqtt.username({username!r}) must equal robot.id({robot_id!r})"
                " — MQTT_USERNAME and MQTT_ID must carry the same mqtt_id"
            )
        known = {f for f in cls.__dataclass_fields__ if f != "robot_id"}
        unknown = set(section) - known
        if unknown:
            raise ConfigError(f"unknown mqtt option: {sorted(unknown)}")
        if "host" not in section or not section["host"]:
            raise ConfigError("mqtt.host is required")
        if bool(section.get("require_tls")) and not section.get("ca_cert"):
            # 여기서 죽이지 않으면 평문으로 접속을 시도하다 무한 재접속만 반복한다.
            # 원인이 로그 맨 앞의 경고 한 줄뿐이라 실기에서 찾기 어렵다.
            raise ConfigError(
                "mqtt.require_tls is on but mqtt.ca_cert is empty;"
                " set the broker CA certificate path"
            )
        return cls(
            robot_id=robot_id,
            host=section["host"],
            port=int(section.get("port") or 8883),
            username=username,
            password=section.get("password"),
            keepalive=int(section.get("keepalive", 30)),
            ca_cert=_resolve_path(section.get("ca_cert") or None, base_dir),
            outbox_path=_resolve_path(
                section.get("outbox_path") or "var/outbox.sqlite3", base_dir),
            client_id=section.get("client_id") or robot_id,
            receipt_required=bool(section.get("receipt_required", False)),
            require_tls=bool(section.get("require_tls")),
        )

    @classmethod
    def load(cls, path) -> "MqttConfig":
        return cls.from_document(load_document(path), base_dir=Path(path).resolve().parent)


def _resolve_path(value, base_dir):
    """상대경로를 base_dir 기준으로 푼다. 절대경로·None·"system" 은 그대로 둔다.

    POSIX 절대경로(`/etc/orincar/...`)도 절대경로로 본다. 설정은 Jetson(Linux)
    기준으로 적히는데 Windows 에서 검증하면 drive 가 없어 상대경로로 오해되고,
    `C:\\etc\\orincar\\...` 같은 없는 경로가 만들어진다.
    """
    if not value or value == CA_SYSTEM or base_dir is None:
        return value
    if value.startswith("/") or Path(value).is_absolute():
        return value
    return str(Path(base_dir) / value)
