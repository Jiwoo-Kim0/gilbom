"""Publish State and Telemetry test messages to the Gilbom MQTT TLS broker."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import ssl
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import paho.mqtt.client as mqtt


PACKAGE_DIRECTORY = Path(__file__).resolve().parent
LOCAL_ENV_FILE = PACKAGE_DIRECTORY / ".env"


def load_local_environment() -> None:
    """Load this package's simple KEY=VALUE file without overriding OS values."""
    if not LOCAL_ENV_FILE.is_file():
        return

    for line_number, raw_line in enumerate(
        LOCAL_ENV_FILE.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(
                f"Invalid .env entry at line {line_number}: expected KEY=VALUE"
            )
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            raise ValueError(
                f"Invalid .env entry at line {line_number}: key is empty"
            )
        os.environ.setdefault(key, value.strip())


def environment_port() -> int:
    value = os.getenv("MQTT_PORT", "8883")
    try:
        return int(value)
    except ValueError as error:
        raise ValueError("MQTT_PORT in .env must be an integer") from error


load_local_environment()

DEFAULT_HOST = os.getenv("MQTT_HOST", "125.137.58.131")
DEFAULT_PORT = environment_port()
DEFAULT_USERNAME = os.getenv("MQTT_USERNAME", "robot-test")
DEFAULT_CA_CERTIFICATE = PACKAGE_DIRECTORY / os.getenv(
    "MQTT_CA_CERTIFICATE",
    "local-ca-cert.pem",
)


def utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


def telemetry_payload(robot_id: str, boot_id: str, seq: int) -> dict:
    return {
        "schema_version": "2.0",
        "message_id": str(uuid.uuid4()),
        "robot_id": robot_id,
        "boot_id": boot_id,
        "seq": seq,
        "sampled_at": utc_now(),
        "mission_id": "MIS-TEST-001",
        "position": {
            "valid": True,
            "lat": 37.5013,
            "lon": 127.0396,
            "heading_deg": 92.3,
        },
        "motion": {
            "moving": True,
            "speed_mps": 0.75,
        },
        "battery": {
            "valid": True,
            "percent": 87.0,
            "state": "NORMAL",
            "source": "MEASURED",
            "voltage_v": 25.2,
            "current_a": -1.8,
            "charging": False,
            "battery_temperature_c": 32.4,
            "estimated_runtime_sec": 5400,
        },
        "system": {
            "jetson_temp_c": 41.5,
        },
        "mission": {
            "state": "RUNNING",
            "elapsed_sec": 120,
            "incident_count": 2,
        },
    }


def state_payload(robot_id: str, boot_id: str, seq: int) -> dict:
    return {
        "schema_version": "2.0",
        "robot_id": robot_id,
        "boot_id": boot_id,
        "seq": seq,
        "updated_at": utc_now(),
        "mode": "PATROLLING",
        "reason_code": "NONE",
        "mission": {
            "mission_id": "MIS-TEST-001",
            "state": "RUNNING",
        },
        "battery": {
            "valid": True,
            "percent": 87.0,
            "state": "NORMAL",
        },
        "health": {
            "camera": "OK",
            "lidar": "OK",
            "gps": "OK",
            "battery": "OK",
            "motor": "OK",
            "storage": "OK",
        },
    }


def presence_payload(robot_id: str, boot_id: str) -> dict:
    return {
        "schema_version": "2.0",
        "robot_id": robot_id,
        "boot_id": boot_id,
        "status": "ONLINE",
        "connected_at": utc_now(),
    }


def event_base(
    robot_id: str,
    boot_id: str,
    seq: int,
    event_type: str,
    source_component: str,
) -> dict:
    occurred_at = utc_now()
    return {
        "schema_version": "2.0",
        "event_id": str(uuid.uuid4()),
        "event_type": event_type,
        "robot_id": robot_id,
        "boot_id": boot_id,
        "seq": seq,
        "source_component": source_component,
        "occurred_at": occurred_at,
        "sent_at": occurred_at,
        "mission_id": "MIS-TEST-001",
    }


def incident_event_payload(robot_id: str, boot_id: str, seq: int) -> dict:
    payload = event_base(
        robot_id,
        boot_id,
        seq,
        "BRAILLE_BLOCK_INCIDENT_DETECTED",
        "ai",
    )
    payload.update(
        {
            "location": {
                "valid": True,
                "lat": 37.5013,
                "lon": 127.0396,
                "zone_id": "ZONE-TEST-01",
            },
            "incident": {
                "asset_type": "BRAILLE_BLOCK",
                "block_type": "LINEAR",
                "defect_type": "DAMAGED",
                "confidence": 0.94,
                "attributes": {
                    "damage_ratio": 0.35,
                },
            },
            "evidence": {
                "image_id": str(uuid.uuid4()),
                "captured_at": utc_now(),
                "image_width": 1920,
                "image_height": 1080,
                "bbox_norm": [0.10, 0.20, 0.45, 0.70],
            },
            "model": {
                "name": "gilbom-defect-detector",
                "version": "test-1.0.0",
            },
        }
    )
    return payload


def battery_event_payload(robot_id: str, boot_id: str, seq: int) -> dict:
    payload = event_base(
        robot_id,
        boot_id,
        seq,
        "BATTERY_LOW",
        "power",
    )
    payload.update(
        {
            "previous_state": "NORMAL",
            "battery": {
                "valid": True,
                "percent": 19.5,
                "state": "LOW",
            },
            "threshold_pct": 20.0,
            "action_taken": {
                "mission_state": "PAUSED",
                "robot_mode": "PAUSED",
            },
        }
    )
    return payload


def safety_event_payload(robot_id: str, boot_id: str, seq: int) -> dict:
    payload = event_base(
        robot_id,
        boot_id,
        seq,
        "SAFETY_STOPPED",
        "safety",
    )
    payload.update(
        {
            "reason_code": "OBSTACLE",
            "measurement": {
                "distance_m": 0.42,
            },
            "transition": {
                "mode_before": "PATROLLING",
                "mode_after": "SAFE_STOP",
            },
            "auto_resume_allowed": True,
        }
    )
    return payload


def mission_event_payload(robot_id: str, boot_id: str, seq: int) -> dict:
    payload = event_base(
        robot_id,
        boot_id,
        seq,
        "MISSION_COMPLETED",
        "mission",
    )
    payload.update(
        {
            "result": {
                "duration_sec": 1800,
                "incident_count": 2,
                "normal_detection_count": 148,
                "completion_reason": "TEST_ROUTE_COMPLETED",
            },
        }
    )
    return payload


def command_result_payload(robot_id: str) -> dict:
    completed_at = utc_now()
    return {
        "schema_version": "2.0",
        "result_id": str(uuid.uuid4()),
        "command_id": str(uuid.uuid4()),
        "robot_id": robot_id,
        "status": "COMPLETED",
        "received_at": completed_at,
        "completed_at": completed_at,
        "reason_code": "EXECUTED_FOR_TEST",
        "state": {
            "mode": "PATROLLING",
            "reason_code": "NONE",
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Test external MQTT 5 communication over TLS."
    )
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--username", default=DEFAULT_USERNAME)
    parser.add_argument(
        "--kind",
        choices=(
            "all",
            "both",
            "presence",
            "state",
            "telemetry",
            "incident",
            "battery",
            "safety",
            "mission",
            "command-result",
        ),
        default="all",
        help="Message type to publish. 'both' means State and Telemetry.",
    )
    parser.add_argument(
        "--ca-certificate",
        type=Path,
        default=DEFAULT_CA_CERTIFICATE,
    )
    parser.add_argument(
        "--count",
        type=int,
        default=0,
        help="Publish cycle count. Default 0 continues until Ctrl+C.",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=5.0,
        help="Seconds between publish cycles. Default is 5 seconds.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.count < 0:
        print("--count must be 0 or greater.", file=sys.stderr)
        return 2
    if args.interval <= 0:
        print("--interval must be greater than zero.", file=sys.stderr)
        return 2

    ca_certificate = args.ca_certificate.expanduser().resolve()
    if not ca_certificate.is_file():
        print(f"CA certificate not found: {ca_certificate}", file=sys.stderr)
        return 2

    password = os.getenv("MQTT_PASSWORD") or getpass.getpass(
        f"MQTT password for {args.username}: "
    )
    if not password:
        print("MQTT password must not be empty.", file=sys.stderr)
        return 2

    connected = threading.Event()
    connection_result: dict[str, object] = {}

    def on_connect(
        client: mqtt.Client,
        userdata: object,
        flags: mqtt.ConnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        connection_result["reason_code"] = reason_code
        connected.set()

    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"gilbom-external-test-{uuid.uuid4().hex[:8]}",
        protocol=mqtt.MQTTv5,
    )
    client.on_connect = on_connect
    client.username_pw_set(args.username, password)

    tls_context = ssl.create_default_context(cafile=str(ca_certificate))
    client.tls_set_context(tls_context)

    try:
        client.connect(args.host, args.port, keepalive=30)
        client.loop_start()

        if not connected.wait(timeout=10):
            print("MQTT connection timed out.", file=sys.stderr)
            return 1

        reason_code = connection_result.get("reason_code")
        if reason_code != 0:
            print(f"MQTT connection rejected: {reason_code}", file=sys.stderr)
            return 1

        print(
            f"Connected host={args.host} port={args.port} "
            f"username={args.username} tls=on"
        )

        boot_id = str(uuid.uuid4())
        sequence = 1
        cycle = 0

        while args.count == 0 or cycle < args.count:
            messages: list[tuple[str, dict, int, bool, int]] = []

            if args.kind in ("all", "presence"):
                messages.append(
                    (
                        f"tbs/v2/robots/{args.username}/presence",
                        presence_payload(args.username, boot_id),
                        1,
                        True,
                        sequence,
                    )
                )
                sequence += 1
            if args.kind in ("all", "both", "state"):
                messages.append(
                    (
                        f"tbs/v2/robots/{args.username}/state",
                        state_payload(args.username, boot_id, sequence),
                        1,
                        True,
                        sequence,
                    )
                )
                sequence += 1
            if args.kind in ("all", "both", "telemetry"):
                messages.append(
                    (
                        f"tbs/v2/robots/{args.username}/telemetry",
                        telemetry_payload(args.username, boot_id, sequence),
                        0,
                        False,
                        sequence,
                    )
                )
                sequence += 1
            if args.kind in ("all", "incident"):
                messages.append(
                    (
                        f"tbs/v2/robots/{args.username}/events/incident",
                        incident_event_payload(
                            args.username,
                            boot_id,
                            sequence,
                        ),
                        1,
                        False,
                        sequence,
                    )
                )
                sequence += 1
            if args.kind in ("all", "battery"):
                messages.append(
                    (
                        f"tbs/v2/robots/{args.username}/events/battery",
                        battery_event_payload(
                            args.username,
                            boot_id,
                            sequence,
                        ),
                        1,
                        False,
                        sequence,
                    )
                )
                sequence += 1
            if args.kind in ("all", "safety"):
                messages.append(
                    (
                        f"tbs/v2/robots/{args.username}/events/safety",
                        safety_event_payload(
                            args.username,
                            boot_id,
                            sequence,
                        ),
                        1,
                        False,
                        sequence,
                    )
                )
                sequence += 1
            if args.kind in ("all", "mission"):
                messages.append(
                    (
                        f"tbs/v2/robots/{args.username}/events/mission",
                        mission_event_payload(
                            args.username,
                            boot_id,
                            sequence,
                        ),
                        1,
                        False,
                        sequence,
                    )
                )
                sequence += 1
            if args.kind in ("all", "command-result"):
                messages.append(
                    (
                        f"tbs/v2/robots/{args.username}/commands/result",
                        command_result_payload(args.username),
                        1,
                        False,
                        sequence,
                    )
                )
                sequence += 1

            for topic, payload, qos, retain, published_sequence in messages:
                encoded_payload = json.dumps(payload, separators=(",", ":"))
                print(
                    f"Sending topic={topic} qos={qos} retain={retain} "
                    f"seq={published_sequence}"
                )
                print(json.dumps(payload, ensure_ascii=False, indent=2))
                result = client.publish(
                    topic,
                    encoded_payload,
                    qos=qos,
                    retain=retain,
                )
                result.wait_for_publish(timeout=10)
                if result.rc != mqtt.MQTT_ERR_SUCCESS or not result.is_published():
                    print(
                        f"Publish failed topic={topic}: "
                        f"{mqtt.error_string(result.rc)}",
                        file=sys.stderr,
                    )
                    return 1
                print(
                    f"Published topic={topic} qos={qos} retain={retain} "
                    f"seq={published_sequence}"
                )

            cycle += 1
            if args.count == 0 or cycle < args.count:
                time.sleep(args.interval)

        return 0
    except KeyboardInterrupt:
        print("\nStopped by user.")
        return 0
    except ssl.SSLCertVerificationError as error:
        print(f"TLS certificate verification failed: {error}", file=sys.stderr)
        return 1
    except (OSError, mqtt.MQTTException) as error:
        print(f"MQTT communication failed: {error}", file=sys.stderr)
        return 1
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    raise SystemExit(main())
