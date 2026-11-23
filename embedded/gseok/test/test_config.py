"""config.yaml 로드 — placeholder 치환과 robot_id 단일 출처 검증."""

import textwrap
from pathlib import Path

import pytest

from comms import ConfigError, MqttConfig, load_document

REPO_CONFIG = Path(__file__).resolve().parents[1] / "config.yaml"


def write(tmp_path, body):
    path = tmp_path / "config.yaml"
    path.write_text(textwrap.dedent(body), encoding="utf-8")
    return path


def test_env_placeholder(tmp_path, monkeypatch):
    monkeypatch.setenv("MQTT_HOST", "broker.internal")
    monkeypatch.setenv("MQTT_PASSWORD", "s3cret")
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: ${env:MQTT_HOST}
          password: ${env:MQTT_PASSWORD}
        """,
    )
    config = MqttConfig.load(path)
    assert config.host == "broker.internal"
    assert config.password == "s3cret"


def test_missing_env_fails_loudly(tmp_path, monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: localhost
          password: ${env:MQTT_PASSWORD}
        """,
    )
    with pytest.raises(ConfigError, match="MQTT_PASSWORD"):
        MqttConfig.load(path)


<<<<<<< HEAD
=======
def test_optional_env_placeholder_is_null_when_unset(tmp_path, monkeypatch):
    """`${env?:}` 는 없어도 죽지 않는다 — 런타임에 채워질 값 전용이다.

    기동에 반드시 필요한 값은 계속 `${env:}` 로 두어 조용히 비는 일이 없게 한다.
    """
    monkeypatch.delenv("ROBOT_UUID", raising=False)
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
          uuid: ${env?:ROBOT_UUID}
        mqtt:
          host: localhost
        """,
    )
    assert load_document(path)["robot"]["uuid"] is None

    monkeypatch.setenv("ROBOT_UUID", "6f1b6b1e-9c2a-4c1e-9f3a-2b6d5a4c7e10")
    assert (
        load_document(path)["robot"]["uuid"] == "6f1b6b1e-9c2a-4c1e-9f3a-2b6d5a4c7e10"
    )


def test_optional_env_placeholder_falls_back_to_the_written_default(tmp_path, monkeypatch):
    """기본값을 코드가 아니라 파일 안에 적어 둔다 — 읽는 사람이 다 볼 수 있어야 한다."""
    monkeypatch.delenv("MQTT_PORT", raising=False)
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: localhost
          port: ${env?:MQTT_PORT|8883}
        """,
    )
    assert MqttConfig.load(path).port == 8883

    monkeypatch.setenv("MQTT_PORT", "1883")
    assert MqttConfig.load(path).port == 1883


def test_provisioning_env_fills_the_credential_fields(tmp_path, monkeypatch):
    """로봇 등록·Credential 발급 응답을 그대로 환경변수로 넣는 경로 (scripts/orincar.env)."""
    monkeypatch.setenv("MQTT_ID", "RBT-042")
    monkeypatch.setenv("MQTT_USERNAME", "RBT-042")
    monkeypatch.setenv("MQTT_CLIENT_ID", "jetson-bench-1")
    monkeypatch.setenv("MQTT_PASSWORD", "one-time-secret")
    monkeypatch.setenv("MQTT_HOST", "i15d101.p.ssafy.io")
    monkeypatch.setenv("MQTT_PORT", "8883")
    monkeypatch.setenv("MQTT_CA_CERT", "/etc/orincar/gilbom-mqtt-ca.crt")
    path = write(
        tmp_path,
        """
        robot:
          id: ${env:MQTT_ID}
        mqtt:
          host: ${env:MQTT_HOST}
          port: ${env?:MQTT_PORT|8883}
          username: ${env?:MQTT_USERNAME}
          client_id: ${env?:MQTT_CLIENT_ID}
          password: ${env:MQTT_PASSWORD}
          ca_cert: ${env?:MQTT_CA_CERT|gilbom-mqtt-ca.crt}
          require_tls: true
        """,
    )
    config = MqttConfig.load(path)
    assert config.robot_id == "RBT-042"
    assert config.username == "RBT-042"
    # client_id 는 계약에 없는 접속 식별자라 mqtt_id 와 달라도 된다.
    assert config.client_id == "jetson-bench-1"
    # 절대경로는 config 디렉터리 기준으로 다시 풀지 않는다.
    assert config.ca_cert == "/etc/orincar/gilbom-mqtt-ca.crt"
    assert config.tls_enabled


def test_a_username_that_drifts_from_the_mqtt_id_is_rejected(tmp_path, monkeypatch):
    """브로커 ACL 이 %u 를 mqtt_id 에 묶었다. 갈라지면 접속은 되는데 발행이 전부 거부된다."""
    monkeypatch.setenv("MQTT_ID", "RBT-042")
    monkeypatch.setenv("MQTT_USERNAME", "RBT-999")
    path = write(
        tmp_path,
        """
        robot:
          id: ${env:MQTT_ID}
        mqtt:
          host: localhost
          username: ${env?:MQTT_USERNAME}
        """,
    )
    with pytest.raises(ConfigError, match="MQTT_USERNAME"):
        MqttConfig.load(path)


>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
def test_username_is_derived_from_robot_id(tmp_path):
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-042
        mqtt:
          host: localhost
          username: ${robot.id}
        """,
    )
    config = MqttConfig.load(path)
    assert config.robot_id == "RBT-042"
    assert config.username == "RBT-042"


def test_diverging_username_is_rejected(tmp_path):
    """robot.id / username / topic 세그먼트가 갈라지면 기동을 막는다."""
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: localhost
          username: RBT-002
        """,
    )
    with pytest.raises(ConfigError, match="must equal robot.id"):
        MqttConfig.load(path)


def test_username_defaults_to_robot_id(tmp_path):
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-007
        mqtt:
          host: localhost
        """,
    )
    assert MqttConfig.load(path).username == "RBT-007"


def test_unknown_option_is_rejected(tmp_path):
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: localhost
          keepalve: 30
        """,
    )
    with pytest.raises(ConfigError, match="keepalve"):
        MqttConfig.load(path)


def test_host_is_required(tmp_path):
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          port: 8883
        """,
    )
    with pytest.raises(ConfigError, match="host"):
        MqttConfig.load(path)


def test_client_id_defaults_to_robot_id(tmp_path):
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: broker.example.com
        """,
    )
    assert MqttConfig.load(path).client_id == "RBT-001"


def test_client_id_can_differ_from_the_username(tmp_path):
    """계정을 공유하는 테스트에서 두 클라이언트가 서로 세션을 뺏지 않게 한다.

    같은 client_id 로 두 번 붙으면 브로커가 먼저 붙은 쪽을 끊는다
    ("session taken over"). username 은 ACL 때문에 robot_id 여야 한다.
    """
    path = write(
        tmp_path,
        """
        robot:
          id: robot-test
        mqtt:
          host: broker.example.com
          client_id: robot-test-orincar
        """,
    )
    config = MqttConfig.load(path)
    assert config.client_id == "robot-test-orincar"
    assert config.username == "robot-test"  # ACL 은 그대로 지킨다


def test_require_tls_rejects_an_empty_ca_cert(tmp_path):
    """환경변수 하나가 빠져 조용히 평문으로 뜨는 것을 기동 시점에 막는다."""
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: broker.example.com
          require_tls: true
        """,
    )
    with pytest.raises(ConfigError, match="ca_cert"):
        MqttConfig.load(path)


def test_require_tls_passes_with_a_ca_cert(tmp_path):
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: broker.example.com
          require_tls: true
          ca_cert: /etc/gilbom/ca.crt
        """,
    )
    config = MqttConfig.load(path)
    assert config.require_tls is True
    assert config.tls_enabled is True


def test_require_tls_is_off_by_default(tmp_path):
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-001
        mqtt:
          host: broker.example.com
        """,
    )
    assert MqttConfig.load(path).require_tls is False


def test_tls_is_enabled_only_with_ca_cert(tmp_path):
    assert MqttConfig(robot_id="R", host="h", ca_cert="ca.crt").tls_enabled
    assert not MqttConfig(robot_id="R", host="h").tls_enabled


<<<<<<< HEAD
def test_repo_config_is_loadable(monkeypatch):
    """실제 embedded/config.yaml 이 계약대로 로드되는지."""
=======
def test_ca_cert_system_uses_the_os_trust_store(tmp_path):
    """"system" 은 경로가 아니다 — config 디렉터리 기준으로 풀면 없는 파일이 된다."""
    path = write(
        tmp_path,
        """
        robot:
          id: RBT-042
        mqtt:
          host: broker.example.com
          ca_cert: system
          require_tls: true
        """,
    )
    config = MqttConfig.load(path)
    assert config.ca_cert == "system"
    assert config.tls_enabled


def test_repo_config_is_loadable(monkeypatch):
    """실제 embedded/config.yaml 이 계약대로 로드되는지."""
    monkeypatch.setenv("MQTT_ID", "RBT-001")
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    monkeypatch.setenv("MQTT_HOST", "broker.example.com")
    monkeypatch.setenv("MQTT_PASSWORD", "pw")
    monkeypatch.setenv("UPLOAD_URL", "https://server.example/api/v1/images")
    monkeypatch.setenv("UPLOAD_API_KEY", "key")
<<<<<<< HEAD
    document = load_document(REPO_CONFIG)
    config = MqttConfig.from_document(document)
    assert config.robot_id == document["robot"]["id"]
    assert config.username == config.robot_id
    assert config.port == 8883
=======
    monkeypatch.setenv("ROBOT_UUID", "6f1b6b1e-9c2a-4c1e-9f3a-2b6d5a4c7e10")
    for name in ("MQTT_PORT", "MQTT_USERNAME", "MQTT_CLIENT_ID", "MQTT_CA_CERT"):
        monkeypatch.delenv(name, raising=False)
    document = load_document(REPO_CONFIG)
    config = MqttConfig.from_document(document)
    assert config.robot_id == document["robot"]["id"] == "RBT-001"
    # 발급 응답이 따로 주지만 생략하면 mqtt_id 를 쓴다. 갈라지면 ACL 이 깨진다.
    assert config.username == config.robot_id
    assert config.client_id == config.robot_id
    # 환경변수를 안 줬을 때의 기본값이 파일 안에 적혀 있어야 한다.
    assert config.port == 8883
    # 운영 브로커는 Let's Encrypt — 기본값은 OS 신뢰 저장소 검증이다.
    assert config.ca_cert == "system"
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    assert config.keepalive == 30
    # 비밀값은 파일에 직접 적지 않고 환경변수로만 받는다.
    raw = REPO_CONFIG.read_text(encoding="utf-8")
    assert "${env:MQTT_PASSWORD}" in raw
    assert "${robot.id}" in raw
<<<<<<< HEAD
=======
    # 이미지 업로드 form 의 robot_id 는 DB UUID 다 — mqtt_id 와 별개로 주입받는다.
    assert "${env?:ROBOT_UUID}" in raw
    assert document["robot"]["uuid"] != document["robot"]["id"]
    # 이미지 업로드는 X-API-Key 발급 경로가 없어 막혀 있다. 그것 때문에 로봇이
    # 못 뜨면 MQTT E2E 자체를 못 한다 — 선택으로 두어야 한다.
    assert "${env?:UPLOAD_API_KEY}" in raw
    # 운영 브로커는 8883 TLS 전용이다. ca_cert 없이 뜨면 무한 재접속만 반복한다.
    assert config.require_tls is True
    assert config.tls_enabled
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
