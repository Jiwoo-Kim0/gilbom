"""증거 이미지 HTTP 업로드 — POST /api/v1/images.  [F-006]

<<<<<<< HEAD
기준 문서: web/docs/api-contract.md §5·§7.2·§8, web/docs/authorization-matrix.md
=======
기준 문서: web/docs/api-contract.md §5·§7.2·§8, web/docs/authorization-matrix.md,
          docs/comms/웹_통신계약_로봇_수신_정리.md §4 (2026-08-01 · 서버 구현과 대조 완료)
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
"""

from __future__ import annotations

import logging
import time
<<<<<<< HEAD
=======
import uuid
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
from dataclasses import dataclass
from pathlib import Path

import requests

from .config import ConfigError, load_document

log = logging.getLogger(__name__)


#: 계약 §8 — JPEG 만, 최대 500KB. 서버 413 을 기다릴 이유가 없으니 여기서 먼저 거른다.
MAX_IMAGE_BYTES = 500 * 1024
IMAGE_CONTENT_TYPE = "image/jpeg"
#: 로봇 이미지 API 인증. 세션 쿠키·CSRF 대상이 아니다 (authorization-matrix).
API_KEY_HEADER = "X-API-Key"
TIMEOUT_SEC = 10
#: 재시도 3회의 간격. 첫 시도 전에는 쉬지 않는다.
RETRY_DELAYS_SEC = (1, 2, 4)
#: 201 신규 · 200 멱등 재업로드((event_id, robot_id) 자연 키, 계약 §7.2) 둘 다 성공이다.
SUCCESS_STATUSES = frozenset({200, 201})
<<<<<<< HEAD
#: 4xx 중 유일하게 재시도할 값. 400·401·403·413·415 는 다시 보내도 결과가 같다.
RETRYABLE_CLIENT_STATUSES = frozenset({429})

#: ⚠️ POST /api/v1/images 가 openapi-v1.yaml 에 없다 — form 필드명은 산문 계약에서 읽은
#:    추정치다. 서버팀 확인 후 이 세 줄만 고치면 된다.
FIELD_FILE = "file"
FIELD_EVENT_ID = "event_id"
FIELD_ROBOT_ID = "robot_id"

#: ⚠️ 응답 body 스키마도 계약에 없다. 추측은 _extract_image_id() 한 곳에만 가둔다.
=======
#: 4xx 중 유일하게 재시도할 값. 400·401·403·409·413·415 는 다시 보내도 결과가 같다.
#: 409 는 같은 event_id 를 다른 로봇·다른 파일로 올렸다는 뜻이라 재시도가 무의미하다.
RETRYABLE_CLIENT_STATUSES = frozenset({429})

#: form 필드명 — 서버 ImageUploadController 와 대조 완료 (2026-08-01).
#: ⚠️ FIELD_ROBOT_ID 에 넣는 값은 mqtt_id("RBT-001")가 아니라 DB 내부 robot.robot_id UUID 다.
#:    서버가 UUID.fromString() 으로 파싱하므로 mqtt_id 를 보내면 400 이 떨어진다.
#:    MQTT payload 는 mqtt_id, 이미지 업로드 form 은 robot_id UUID — 채널마다 이름과 값이 다르다.
FIELD_FILE = "file"
FIELD_EVENT_ID = "event_id"
FIELD_ROBOT_ID = "robot_id"
#: 선택 필드. 서버는 ISO-8601 UTC 를 받고 미래 시각은 거부한다.
FIELD_CAPTURED_AT = "captured_at"

#: 응답 body 는 camelCase 다 (MQTT 의 snake_case 와 다르다):
#: {"imageId", "eventId", "objectKey", "imageWidth", "imageHeight"}.
#: image_id 를 먼저 보는 순서는 그대로 둔다 — 서버가 snake_case 로 바뀌어도 계속 읽힌다.
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
IMAGE_ID_KEYS = ("image_id", "imageId")
IMAGE_ID_CONTAINERS = ("data", "result", "image")

DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[1] / "config.yaml"

_default_config = None


@dataclass(frozen=True)
class UploadConfig:
    url: str
    api_key: str

    @classmethod
    def from_document(cls, document: dict) -> "UploadConfig":
        section = document.get("upload") if isinstance(document, dict) else None
        if not isinstance(section, dict):
            raise ConfigError("config must define the upload section")
        unknown = set(section) - set(cls.__dataclass_fields__)
        if unknown:
            raise ConfigError(f"unknown upload option: {sorted(unknown)}")
        if not section.get("url"):
            raise ConfigError("upload.url is required")
        if not section.get("api_key"):
            raise ConfigError("upload.api_key is required")
        return cls(url=section["url"], api_key=section["api_key"])

    @classmethod
    def load(cls, path) -> "UploadConfig":
        return cls.from_document(load_document(path))


def _config() -> UploadConfig:
    """호출부가 config 를 넘기지 않았을 때의 기본값. 파일은 한 번만 읽는다."""
    global _default_config
    if _default_config is None:
        _default_config = UploadConfig.load(DEFAULT_CONFIG_PATH)
    return _default_config


def _extract_image_id(body):
    """응답에서 image_id 를 꺼내는 유일한 곳.

<<<<<<< HEAD
    ⚠️ 응답 스키마가 기계 판독 계약(openapi-v1.yaml)에 없어서 후보 키를 관용적으로
    읽는다. 이 추측을 다른 곳으로 퍼뜨리지 말 것 — 서버팀 확인 후 여기만 고친다.
=======
    서버 응답은 camelCase 다 (MQTT 의 snake_case 와 다르다):
    `{"imageId", "eventId", "objectKey", "imageWidth", "imageHeight"}`.
    201 신규 · 200 같은 event_id 멱등 재업로드 · 409 다른 로봇/다른 파일 충돌.
    image_id 를 먼저 보는 순서는 앞으로도 유지한다 — 서버가 snake_case 로 바뀌어도
    계속 읽히는 쪽이 안전하다.
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    """
    if not isinstance(body, dict):
        return None
    for key in IMAGE_ID_KEYS:
        value = body.get(key)
        if isinstance(value, str) and value:
            return value
    for key in IMAGE_ID_CONTAINERS:
        found = _extract_image_id(body.get(key))
        if found:
            return found
    return None


<<<<<<< HEAD
def upload(image_path, event_id: str, robot_id: str, *, config: UploadConfig = None):
    """증거 이미지를 올리고 image_id 를 돌려준다. 실패하면 None.

=======
def _is_uuid(value) -> bool:
    """서버가 UUID.fromString() 으로 파싱하는 값인지 미리 본다."""
    if not isinstance(value, str):
        return False
    try:
        uuid.UUID(value)
    except ValueError:
        return False
    return True


def upload(
    image_path,
    event_id: str,
    robot_uuid: str,
    *,
    captured_at: str = None,
    config: UploadConfig = None,
):
    """증거 이미지를 올리고 image_id 를 돌려준다. 실패하면 None.

    `robot_uuid` 는 DB 내부 robot.robot_id UUID 다 — mqtt_id 가 아니다.
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    계약 §8.3 — 업로드가 실패해도 예외를 올리지 않는다. 호출부는 image_id 만 null 로
    두고 incident event 를 그대로 발행한다. 업로드 실패가 결함 사건 자체를 삼키면 안 된다.
    """
    try:
<<<<<<< HEAD
        return _upload(image_path, event_id, robot_id, config)
=======
        return _upload(image_path, event_id, robot_uuid, captured_at, config)
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    except Exception:
        log.warning("image upload failed (event_id=%s)", event_id, exc_info=True)
        return None


<<<<<<< HEAD
def _upload(image_path, event_id: str, robot_id: str, config: UploadConfig):
    config = config or _config()
=======
def _upload(image_path, event_id: str, robot_uuid: str, captured_at, config: UploadConfig):
    config = config or _config()
    if not _is_uuid(robot_uuid):
        # 서버는 UUID.fromString() 으로 파싱한다. mqtt_id("RBT-001")나 None 을 보내면
        # 400 이 오고, 400 은 재시도 대상이 아니라 조용히 유실된다. 네트워크를 태우기 전에
        # 여기서 끊고 원인을 남긴다 — config 의 robot.uuid(ROBOT_UUID) 가 비었다는 뜻이다.
        log.error(
            "robot uuid %r is not a UUID; set robot.uuid (ROBOT_UUID) to the DB"
            " robot.robot_id — not the mqtt_id. skipping upload (event_id=%s)",
            robot_uuid,
            event_id,
        )
        return None
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    path = Path(image_path)
    size = path.stat().st_size
    if size > MAX_IMAGE_BYTES:
        # 리사이즈·재인코딩은 여기서 하지 않는다 — 거르고 None 만 돌려준다.
        log.warning(
            "image %s is %d bytes, over the %d byte contract limit; not uploading",
            path,
            size,
            MAX_IMAGE_BYTES,
        )
        return None
    # 재시도마다 다시 읽지 않도록 한 번만 읽는다.
    blob = path.read_bytes()

    # 서버는 API Key 의 로봇과 body 의 robot_id 가 같은지 검사한다 — 둘 다 보낸다.
    headers = {API_KEY_HEADER: config.api_key}
<<<<<<< HEAD
    fields = {FIELD_EVENT_ID: event_id, FIELD_ROBOT_ID: robot_id}
=======
    fields = {FIELD_EVENT_ID: event_id, FIELD_ROBOT_ID: robot_uuid}
    if captured_at is not None:
        # 선택 필드다. 서버가 미래 시각을 거부하므로 없는 값을 지어내지 않는다.
        fields[FIELD_CAPTURED_AT] = captured_at
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84

    attempts = len(RETRY_DELAYS_SEC) + 1
    for attempt in range(attempts):
        if attempt:
            time.sleep(RETRY_DELAYS_SEC[attempt - 1])
        try:
            response = requests.post(
                config.url,
                headers=headers,
                data=fields,
                files={FIELD_FILE: (path.name, blob, IMAGE_CONTENT_TYPE)},
                timeout=TIMEOUT_SEC,
            )
        except Exception as exc:
            # 타임아웃·연결오류는 다시 걸어볼 값어치가 있다.
            log.warning("upload attempt %d failed: %s", attempt + 1, exc)
            continue

        status = getattr(response, "status_code", None)
        if status in SUCCESS_STATUSES:
            return _image_id_of(response, event_id)
        if status in RETRYABLE_CLIENT_STATUSES or (status or 0) >= 500:
            log.warning("upload attempt %d got %s, retrying", attempt + 1, status)
            continue
        # 400·401·403·413·415 는 같은 요청을 다시 보내도 같은 답이 온다.
        log.warning("upload rejected with %s (event_id=%s); giving up", status, event_id)
        return None

    log.warning("upload gave up after %d attempts (event_id=%s)", attempts, event_id)
    return None


def _image_id_of(response, event_id: str):
    try:
        body = response.json()
    except Exception:
        body = None
    image_id = _extract_image_id(body)
    if image_id is None:
        log.warning(
            "upload succeeded (%s) but no image_id in the response body: %.500s",
            getattr(response, "status_code", None),
            body if body is not None else getattr(response, "text", ""),
        )
    return image_id
