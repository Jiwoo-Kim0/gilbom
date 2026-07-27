"""Publish one telemetry message to the local TLS Mosquitto broker.

This is a manual connectivity check for Spring listening on:
tbs/v2/robots/+/telemetry
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import ssl
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

import paho.mqtt.client as mqtt


DEFAULT_USERNAME = "robot-test"
DEFAULT_TOPIC = "tbs/v2/robots/robot-test/telemetry"
DEFAULT_PAYLOAD = json.dumps(
    {
        "schema_version": "2.0",
        "message_id": str(uuid.uuid4()),
        "robot_id": "robot-test",
        "boot_id": str(uuid.uuid4()),
        "seq": 1,
        "sampled_at": datetime.now(timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z"),
        "mission_id": None,
        "position": {
            "valid": True,
            "lat": 37.5013,
            "lon": 127.0396,
            "heading_deg": None,
        },
        "motion": {
            "moving": False,
            "speed_mps": None,
        },
        "battery": {
            "valid": True,
            "percent": 87.0,
            "state": "NORMAL",
            "source": "MEASURED",
        },
        "system": {
            "jetson_temp_c": 41.5,
        },
        "mission": None,
    },
    separators=(",", ":"),
)
DEFAULT_CA_CERTIFICATE = (
    Path(__file__).resolve().parents[2]
    / "infra"
    / "mosquitto"
    / "local"
    / "runtime"
    / "certs"
    / "local-ca-cert.pem"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish test MQTT telemetry over TLS.")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8883)
    parser.add_argument("--username", default=DEFAULT_USERNAME)
    parser.add_argument("--topic", default=DEFAULT_TOPIC)
    parser.add_argument("--payload", default=DEFAULT_PAYLOAD)
    parser.add_argument("--qos", type=int, choices=(0, 1, 2), default=0)
    parser.add_argument("--ca-certificate", type=Path, default=DEFAULT_CA_CERTIFICATE)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
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

    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"gilbom-python-test-{uuid.uuid4().hex[:8]}",
        protocol=mqtt.MQTTv5,
    )
    client.username_pw_set(args.username, password)

    tls_context = ssl.create_default_context(cafile=str(ca_certificate))
    client.tls_set_context(tls_context)

    try:
        client.connect(args.host, args.port, keepalive=30)
        client.loop_start()

        result = client.publish(args.topic, args.payload, qos=args.qos)
        result.wait_for_publish(timeout=10)
        if result.rc != mqtt.MQTT_ERR_SUCCESS or not result.is_published():
            print(
                f"Publish failed: {mqtt.error_string(result.rc)}",
                file=sys.stderr,
            )
            return 1

        print(f"Published topic={args.topic} qos={args.qos}")
        print(f"Payload: {args.payload}")
        return 0
    except (OSError, mqtt.MQTTException) as error:
        print(f"MQTT publish failed: {error}", file=sys.stderr)
        return 1
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    raise SystemExit(main())
