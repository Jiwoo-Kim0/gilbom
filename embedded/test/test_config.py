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


def test_tls_is_enabled_only_with_ca_cert(tmp_path):
    assert MqttConfig(robot_id="R", host="h", ca_cert="ca.crt").tls_enabled
    assert not MqttConfig(robot_id="R", host="h").tls_enabled


def test_repo_config_is_loadable(monkeypatch):
    """실제 embedded/config.yaml 이 계약대로 로드되는지."""
    monkeypatch.setenv("MQTT_HOST", "broker.example.com")
    monkeypatch.setenv("MQTT_PASSWORD", "pw")
    monkeypatch.setenv("UPLOAD_URL", "https://server.example/api/v1/images")
    monkeypatch.setenv("UPLOAD_API_KEY", "key")
    document = load_document(REPO_CONFIG)
    config = MqttConfig.from_document(document)
    assert config.robot_id == document["robot"]["id"]
    assert config.username == config.robot_id
    assert config.port == 8883
    assert config.keepalive == 30
    # 비밀값은 파일에 직접 적지 않고 환경변수로만 받는다.
    raw = REPO_CONFIG.read_text(encoding="utf-8")
    assert "${env:MQTT_PASSWORD}" in raw
    assert "${robot.id}" in raw
