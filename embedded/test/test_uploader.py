"""uploader 단위 테스트 — 재시도 정책·멱등 재업로드·실패 시 None.

requests 는 monkeypatch 로 갈아끼운다. 네트워크를 타지 않는다.
기준: web/docs/api-contract.md §5·§7.2·§8
"""

import pytest

from comms import uploader
from comms.uploader import UploadConfig

EVENT_ID = "81fd9bd1-8639-47a2-82fd-9f26281dac68"
ROBOT_ID = "RBT-001"
IMAGE_ID = "550e8400-e29b-41d4-a716-446655440000"


class FakeResponse:
    def __init__(self, status_code, body=None, text=""):
        self.status_code = status_code
        self._body = body
        self.text = text

    def json(self):
        if self._body is None:
            raise ValueError("response is not JSON")
        return self._body


class FakePost:
    """호출을 기록하고 미리 정한 응답(또는 예외)을 순서대로 돌려준다."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def __call__(self, url, **kwargs):
        self.calls.append(dict(kwargs, url=url))
        item = self.responses[min(len(self.calls) - 1, len(self.responses) - 1)]
        if isinstance(item, Exception):
            raise item
        return item


@pytest.fixture
def config():
    return UploadConfig(url="https://server.example/api/v1/images", api_key="KEY-123")


@pytest.fixture
def image(tmp_path):
    path = tmp_path / "evidence.jpg"
    path.write_bytes(b"\xff\xd8\xff" + b"x" * 1024)
    return path


@pytest.fixture
def sleeps(monkeypatch):
    """재시도 간격만 기록하고 실제로 자지는 않는다."""
    recorded = []
    monkeypatch.setattr(uploader.time, "sleep", recorded.append)
    return recorded


def post_with(monkeypatch, *responses) -> FakePost:
    fake = FakePost(*responses)
    monkeypatch.setattr(uploader.requests, "post", fake)
    return fake


# --------------------------------------------------------------------------
# 성공 경로
# --------------------------------------------------------------------------


def test_created_response_returns_image_id(monkeypatch, image, config, sleeps):
    post = post_with(monkeypatch, FakeResponse(201, {"image_id": IMAGE_ID}))
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) == IMAGE_ID
    assert len(post.calls) == 1
    assert sleeps == []


def test_idempotent_reupload_is_success(monkeypatch, image, config, sleeps):
    """(event_id, robot_id) 자연 멱등 키 재업로드 — 200 도 성공이다 (계약 §7.2)."""
    post_with(monkeypatch, FakeResponse(200, {"image_id": IMAGE_ID}))
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) == IMAGE_ID


@pytest.mark.parametrize(
    "body",
    [
        {"image_id": IMAGE_ID},
        {"imageId": IMAGE_ID},
        {"data": {"image_id": IMAGE_ID}},
        {"result": {"imageId": IMAGE_ID}},
        {"image": {"image_id": IMAGE_ID}},
    ],
)
def test_image_id_is_read_from_candidate_keys(monkeypatch, image, config, sleeps, body):
    post_with(monkeypatch, FakeResponse(201, body))
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) == IMAGE_ID


def test_request_carries_api_key_event_id_and_robot_id(
    monkeypatch, image, config, sleeps
):
    post = post_with(monkeypatch, FakeResponse(201, {"image_id": IMAGE_ID}))
    uploader.upload(image, EVENT_ID, ROBOT_ID, config=config)

    call = post.calls[0]
    assert call["url"] == config.url
    assert call["headers"][uploader.API_KEY_HEADER] == config.api_key
    # 세션 쿠키·CSRF 는 로봇 이미지 API 의 인증 수단이 아니다.
    assert "X-CSRF-TOKEN" not in call["headers"]
    assert call["data"] == {
        uploader.FIELD_EVENT_ID: EVENT_ID,
        uploader.FIELD_ROBOT_ID: ROBOT_ID,
    }
    filename, blob, content_type = call["files"][uploader.FIELD_FILE]
    assert filename == image.name
    assert blob == image.read_bytes()
    assert content_type == uploader.IMAGE_CONTENT_TYPE
    assert call["timeout"] == uploader.TIMEOUT_SEC


# --------------------------------------------------------------------------
# 재시도 정책
# --------------------------------------------------------------------------


def test_server_error_retries_three_times_then_gives_up(
    monkeypatch, image, config, sleeps
):
    post = post_with(monkeypatch, FakeResponse(503))
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) is None
    assert len(post.calls) == 1 + len(uploader.RETRY_DELAYS_SEC)
    assert sleeps == [1, 2, 4]


def test_rate_limit_is_retried(monkeypatch, image, config, sleeps):
    post = post_with(
        monkeypatch, FakeResponse(429), FakeResponse(201, {"image_id": IMAGE_ID})
    )
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) == IMAGE_ID
    assert len(post.calls) == 2
    assert sleeps == [1]


@pytest.mark.parametrize("status", [400, 401, 403, 413, 415])
def test_permanent_rejections_are_not_retried(
    monkeypatch, image, config, sleeps, status
):
    post = post_with(monkeypatch, FakeResponse(status))
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) is None
    assert len(post.calls) == 1
    assert sleeps == []


def test_transport_errors_are_retried(monkeypatch, image, config, sleeps):
    post = post_with(
        monkeypatch,
        uploader.requests.exceptions.Timeout("read timeout"),
        uploader.requests.exceptions.ConnectionError("no route"),
        FakeResponse(201, {"image_id": IMAGE_ID}),
    )
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) == IMAGE_ID
    assert len(post.calls) == 3
    assert sleeps == [1, 2]


# --------------------------------------------------------------------------
# 로컬 선검사 · 응답 공백
# --------------------------------------------------------------------------


def test_oversized_image_is_rejected_without_a_request(
    monkeypatch, tmp_path, config, sleeps
):
    path = tmp_path / "big.jpg"
    path.write_bytes(b"x" * (uploader.MAX_IMAGE_BYTES + 1))
    post = post_with(monkeypatch, FakeResponse(201, {"image_id": IMAGE_ID}))
    assert uploader.upload(path, EVENT_ID, ROBOT_ID, config=config) is None
    assert post.calls == []


def test_image_at_the_size_limit_is_uploaded(monkeypatch, tmp_path, config, sleeps):
    path = tmp_path / "exact.jpg"
    path.write_bytes(b"x" * uploader.MAX_IMAGE_BYTES)
    post = post_with(monkeypatch, FakeResponse(201, {"image_id": IMAGE_ID}))
    assert uploader.upload(path, EVENT_ID, ROBOT_ID, config=config) == IMAGE_ID
    assert len(post.calls) == 1


@pytest.mark.parametrize(
    "response",
    [
        FakeResponse(201, {"status": "OK"}),
        FakeResponse(201, {"data": {"status": "OK"}}),
        FakeResponse(201, {"image_id": None}),
        FakeResponse(200, None, text="not json"),
        FakeResponse(201, ["unexpected"]),
    ],
)
def test_missing_image_id_warns_and_returns_none(
    monkeypatch, image, config, sleeps, caplog, response
):
    post_with(monkeypatch, response)
    with caplog.at_level("WARNING"):
        assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) is None
    assert any("image_id" in record.getMessage() for record in caplog.records)


# --------------------------------------------------------------------------
# 어떤 실패도 밖으로 새지 않는다 (계약 §8.3)
# --------------------------------------------------------------------------


def test_missing_file_returns_none_without_a_request(monkeypatch, tmp_path, config):
    post = post_with(monkeypatch, FakeResponse(201, {"image_id": IMAGE_ID}))
    assert uploader.upload(tmp_path / "gone.jpg", EVENT_ID, ROBOT_ID, config=config) is None
    assert post.calls == []


def test_unexpected_exception_never_escapes(monkeypatch, image, config, sleeps):
    def explode(*args, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(uploader.requests, "post", explode)
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) is None


def test_config_failure_never_escapes(monkeypatch, image):
    def explode():
        raise uploader.ConfigError("upload.api_key is required")

    monkeypatch.setattr(uploader, "_config", explode)
    assert uploader.upload(image, EVENT_ID, ROBOT_ID) is None


def test_broken_response_object_never_escapes(monkeypatch, image, config, sleeps):
    class Hostile:
        @property
        def status_code(self):
            raise RuntimeError("no status")

    post_with(monkeypatch, Hostile())
    assert uploader.upload(image, EVENT_ID, ROBOT_ID, config=config) is None


# --------------------------------------------------------------------------
# UploadConfig
# --------------------------------------------------------------------------


def test_upload_config_from_document():
    config = UploadConfig.from_document(
        {"upload": {"url": "https://server/api/v1/images", "api_key": "K"}}
    )
    assert config.url == "https://server/api/v1/images"
    assert config.api_key == "K"


@pytest.mark.parametrize(
    "document",
    [
        {},
        {"upload": None},
        {"upload": {"api_key": "K"}},
        {"upload": {"url": "https://server/api/v1/images"}},
        {"upload": {"url": "u", "api_key": "K", "bucket": "b"}},
    ],
)
def test_upload_config_rejects_incomplete_section(document):
    with pytest.raises(uploader.ConfigError):
        UploadConfig.from_document(document)


def test_repo_config_defines_upload_secrets_as_env_placeholders():
    raw = uploader.DEFAULT_CONFIG_PATH.read_text(encoding="utf-8")
    # API Key 는 파일에 직접 적지 않는다.
    assert "${env:UPLOAD_API_KEY}" in raw
    assert "${env:UPLOAD_URL}" in raw
