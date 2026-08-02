"""실브로커 스모크 — 실제 comms 스택으로 tbs/v2 발행 8종·명령 수신을 검증한다.

이전 목 로봇(test/통신테스트/mqtt_tls_test.py, gilbom-mqtt-external-test/)의
후속이다. 그 스크립트는 payload 를 손으로 조립했고 지금 서버 계약과 갈라졌다:
  - 식별자가 robot_id 다 — 서버는 07-28 rename 이후 mqtt_id 만 받는다
    (FAIL_ON_UNKNOWN_PROPERTIES 라 robot_id 가 있으면 MQTT_PAYLOAD_MALFORMED).
  - event 에 ttl_sec 이 없다 — 서버 DTO 는 @NotNull @Min(60) @Max(300) 필수.
  - zone_id는 START_MISSION으로 받은 순찰 구역 코드를 해석하지 않고 그대로 사용.
이 도구는 payload 를 직접 만들지 않고 MessageFactory·MqttClient 를 그대로 쓰므로
로봇 본체와 같은 계약으로 나간다 — 여기서 통과하면 main.py 도 같은 wire 를 탄다.

사용:
    python scripts/mqtt_live_smoke.py                              # 운영 브로커 (Let's Encrypt)
    python scripts/mqtt_live_smoke.py --kind incident --count 3 --interval 2
    python scripts/mqtt_live_smoke.py --host 127.0.0.1 --ca-cert local-ca.pem  # 사설 CA 브로커

접속값은 인자 > 환경변수 > .env(스크립트 옆, 없으면 무시) 순서다. 필요한 키:
MQTT_HOST · MQTT_PORT · MQTT_ID(또는 MQTT_USERNAME) · MQTT_PASSWORD ·
MQTT_CA_CERT(CA 파일 경로. 생략하면 system — OS 신뢰 저장소로 검증).
공유 계정(robot-test) 대비 client_id 에는 항상 랜덤 접미사를 붙인다 — 같은
client_id 로 두 번 붙으면 브로커가 먼저 붙은 쪽을 끊는다(session taken over).

한계(계정 ACL): 로봇 계정은 자기 발행 topic 을 구독할 수 없어 수신 확인은 서버
로그로 한다. commands/request 는 서버만 쏠 수 있다 — 도착하면 여기 출력만 하고
실행·result 응답은 하지 않는다(그 경로는 main.py + commands.CommandRunner 몫).
presence 는 접속 시 ONLINE, 정상 종료 시 OFFLINE(GRACEFUL_SHUTDOWN) 이 자동으로
나간다. command-result 의 command_id 는 임의값이라 서버 DB 대조에서는 거절될 수
있다 — 파이프라인(QoS·스키마) 검증용이다.
"""

from __future__ import annotations

import argparse
import getpass
import json
import logging
import os
import sys
import tempfile
import threading
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Windows 개발 PC 의 cp949 콘솔은 '—' 같은 문자에서 UnicodeEncodeError 로 죽는다.
# 인코딩은 그대로 두고 못 그리는 문자만 ? 로 바꾼다 — Jetson(UTF-8)에는 영향 없다.
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(errors="replace")

from comms import MessageFactory, MqttClient, MqttConfig, TopicKind, utc_now  # noqa: E402

MISSION_ID = "MIS-SMOKE-001"
ZONE_ID = "DEMO-ZONE-01"
CONNECT_TIMEOUT_SEC = 10.0
PUBACK_TIMEOUT_SEC = 10.0

EVENT_CHOICES = ("incident", "battery", "safety", "mission")
DIRECT_CHOICES = ("state", "telemetry", "command-result")
KIND_CHOICES = ("all",) + DIRECT_CHOICES + EVENT_CHOICES


def load_env_file(path: Path) -> None:
    """KEY=VALUE 파일을 os.environ 에 얹는다. 이미 있는 OS 값은 이긴다."""
    if not path.is_file():
        return
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"{path}:{number}: KEY=VALUE expected")
        key, value = line.split("=", 1)
        if not key.strip():
            raise ValueError(f"{path}:{number}: key is empty")
        os.environ.setdefault(key.strip(), value.strip())


def build_payload(factory: MessageFactory, kind: str) -> tuple[TopicKind, dict]:
    """kind 별 표본 payload. 값은 이전 목 로봇의 시나리오를 계약에 맞게 이식한 것."""
    now = utc_now()
    if kind == "state":
        return TopicKind.STATE, factory.state(
            mode="PATROLLING",
            reason_code="NONE",
            health_block={m: "OK" for m in ("camera", "lidar", "gps", "battery", "motor", "storage")},
            battery_valid=True,
            battery_percent=87.0,
            battery_state="NORMAL",
            mission_id=MISSION_ID,
            mission_state="RUNNING",
        )
    if kind == "telemetry":
        return TopicKind.TELEMETRY, factory.telemetry(
            position_valid=True,
            lat=37.5013,
            lon=127.0396,
            heading_deg=92.3,
            moving=True,
            battery_valid=True,
            battery_percent=87.0,
            battery_state="NORMAL",
            battery_source="MEASURED",
            battery_extra={
                "voltage_v": 25.2,
                "current_a": -1.8,
                "charging": False,
                "battery_temperature_c": 32.4,
                "estimated_runtime_sec": 5400,
            },
            jetson_temp_c=41.5,
            mission_id=MISSION_ID,
            mission_state="RUNNING",
            mission_elapsed_sec=120,
            mission_incident_count=2,
        )
    if kind == "incident":
        return TopicKind.EVENT_INCIDENT, factory.incident_event(
            occurred_at=now,
            location_valid=True,
            lat=37.5013,
            lon=127.0396,
            zone_id=ZONE_ID,
            block_type="LINEAR",
            confidence=0.94,
            image_id=None,  # 스모크는 업로드를 하지 않는다 — 계약상 null 허용
            captured_at=now,
            image_width=1920,
            image_height=1080,
            bbox_norm=[0.10, 0.20, 0.45, 0.70],
            model_name="tbs-braille-yolo",
            model_version="smoke",
            damage_ratio=0.35,
            mission_id=MISSION_ID,
        )
    if kind == "battery":
        return TopicKind.EVENT_BATTERY, factory.battery_event(
            event_type="BATTERY_LOW",
            occurred_at=now,
            previous_state="NORMAL",
            battery_valid=True,
            battery_percent=19.5,
            battery_state="LOW",
            threshold_pct=20.0,
            action_robot_mode="PAUSED",
            action_mission_state="PAUSED",
            mission_id=MISSION_ID,
        )
    if kind == "safety":
        return TopicKind.EVENT_SAFETY, factory.safety_event(
            event_type="SAFETY_STOPPED",
            occurred_at=now,
            reason_code="OBSTACLE",
            mode_before="PATROLLING",
            mode_after="SAFE_STOP",
            auto_resume_allowed=True,
            distance_m=0.42,
            mission_id=MISSION_ID,
        )
    if kind == "mission":
        return TopicKind.EVENT_MISSION, factory.mission_event(
            event_type="MISSION_COMPLETED",
            occurred_at=now,
            mission_id=MISSION_ID,
            duration_sec=1800,
            incident_count=2,
            normal_detection_count=148,
            completion_reason="ROUTE_COMPLETED",
        )
    if kind == "command-result":
        return TopicKind.COMMAND_RESULT, factory.command_result(
            command_id=str(uuid.uuid4()),
            status="COMPLETED",
            received_at=now,
            completed_at=now,
            reason_code="OK",
            mode="PATROLLING",
            state_reason_code="NONE",
        )
    raise ValueError(f"unknown kind: {kind}")


def watch_commands(client: MqttClient) -> None:
    while True:
        command = client.commands.get()
        print("<< commands/request received:")
        print(json.dumps(command, ensure_ascii=False, indent=2))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--env-file", type=Path,
                        default=Path(__file__).resolve().parent / ".env")
    parser.add_argument("--host", default=None)
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--username", default=None, help="mqtt_id 이자 MQTT username")
    parser.add_argument("--ca-cert", default=None,
                        help="브로커 CA 파일 경로. 생략하면 system(OS 신뢰 저장소)")
    parser.add_argument("--kind", choices=KIND_CHOICES, default="all")
    parser.add_argument("--count", type=int, default=1,
                        help="발행 사이클 수. 0 이면 Ctrl+C 까지 반복.")
    parser.add_argument("--interval", type=float, default=5.0)
    return parser.parse_args()


def main() -> int:
    # comms.client 의 접속 거부·SUBACK 거부·재접속 로그가 진단의 전부다 — 꼭 보이게 한다.
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    args = parse_args()
    if args.count < 0 or args.interval <= 0:
        print("--count must be >= 0 and --interval > 0", file=sys.stderr)
        return 2
    load_env_file(args.env_file)

    host = args.host or os.getenv("MQTT_HOST")
    username = args.username or os.getenv("MQTT_ID") or os.getenv("MQTT_USERNAME")
    if not host or not username:
        print("MQTT_HOST and MQTT_ID are required (args, env, or .env)", file=sys.stderr)
        return 2
    ca_cert = args.ca_cert or os.getenv("MQTT_CA_CERT") or "system"
    if ca_cert != "system":
        ca_cert = str(Path(ca_cert).expanduser().resolve())
        if not Path(ca_cert).is_file():
            print(f"broker CA certificate not found: {ca_cert}", file=sys.stderr)
            return 2
    password = os.getenv("MQTT_PASSWORD") or getpass.getpass(f"MQTT password for {username}: ")
    if not password:
        print("MQTT password must not be empty.", file=sys.stderr)
        return 2

    kinds = list(DIRECT_CHOICES[:2] + EVENT_CHOICES + DIRECT_CHOICES[2:]) \
        if args.kind == "all" else [args.kind]

    with tempfile.TemporaryDirectory(prefix="mqtt-smoke-") as workdir:
        config = MqttConfig(
            robot_id=username,
            host=host,
            port=args.port or int(os.getenv("MQTT_PORT") or 8883),
            username=username,
            password=password,
            ca_cert=ca_cert,
            require_tls=True,
            client_id=f"{username}-smoke-{uuid.uuid4().hex[:8]}",
            outbox_path=str(Path(workdir) / "outbox.sqlite3"),
        )
        factory = MessageFactory(username)
        client = MqttClient(config, factory=factory)
        client.start()
        try:
            if not client.wait_until_connected(CONNECT_TIMEOUT_SEC):
                print("MQTT connection timed out.", file=sys.stderr)
                return 1
            print(f"Connected host={config.host} port={config.port} "
                  f"mqtt_id={username} client_id={config.client_id} tls=on")
            print(f"presence ONLINE published (retain), boot_id={factory.boot_id}")

            deadline = time.monotonic() + 5.0
            while client.subscribed is None and time.monotonic() < deadline:
                time.sleep(0.1)
            print(f"commands/request subscribed={client.subscribed} "
                  f"reason_codes={client.subscribe_reason_codes}")
            threading.Thread(target=watch_commands, args=(client,), daemon=True).start()

            cycle = 0
            while args.count == 0 or cycle < args.count:
                for kind in kinds:
                    topic_kind, payload = build_payload(factory, kind)
                    print(f">> {client.topics.of(topic_kind)} seq={payload.get('seq')}")
                    print(json.dumps(payload, ensure_ascii=False, indent=2))
                    if not client.publish(topic_kind, payload, wait_sec=5.0):
                        print(f"publish not sent now ({kind}) — outbox 에 남음", file=sys.stderr)
                # event 는 PUBACK 을 받아야 성공이다 — outbox 가 비는 것으로 확인한다.
                deadline = time.monotonic() + PUBACK_TIMEOUT_SEC
                while client.outbox.pending_count() and time.monotonic() < deadline:
                    time.sleep(0.2)
                remaining = client.outbox.pending_count()
                if remaining:
                    print(f"PUBACK missing for {remaining} event(s)", file=sys.stderr)
                    return 1
                print(f"cycle {cycle + 1} done — all PUBACKs received")
                cycle += 1
                if args.count == 0 or cycle < args.count:
                    time.sleep(args.interval)
            return 0
        except KeyboardInterrupt:
            print("\nStopped by user.")
            return 0
        finally:
            # 정상 종료 — presence OFFLINE(GRACEFUL_SHUTDOWN) 발행 후 절단.
            client.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
