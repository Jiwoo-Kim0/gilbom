"""Run a local TLS MQTT mock robot that acknowledges backend commands."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import ssl
import sys
import threading
import uuid
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import paho.mqtt.client as mqtt


DEFAULT_CA_CERTIFICATE = (
    Path(__file__).resolve().parent / "local" / "certs" / "server-local.pem"
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Subscribe to robot commands and publish mock ACK messages."
    )
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8883)
    parser.add_argument("--robot-id", default="robot-test")
    parser.add_argument("--qos", type=int, choices=(0, 1, 2), default=1)
    parser.add_argument("--ca-certificate", type=Path, default=DEFAULT_CA_CERTIFICATE)
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Call the local Spring publish API after the command subscription is ready.",
    )
    parser.add_argument(
        "--spring-url",
        default="http://localhost:8080/api/mqtt/test/publish",
    )
    parser.add_argument("--command-type", default="EMERGENCY_STOP")
    return parser.parse_args()


def ack_payload(robot_id: str, command_id: str, status: str) -> str:
    return json.dumps(
        {
            "messageId": f"ack-{uuid.uuid4().hex[:12]}",
            "robotId": robot_id,
            "commandId": command_id,
            "status": status,
            "observedAt": utc_now(),
        },
        separators=(",", ":"),
    )


def publish_ack(
    client: mqtt.Client,
    topic: str,
    robot_id: str,
    command_id: str,
    status: str,
    qos: int,
) -> None:
    payload = ack_payload(robot_id, command_id, status)
    result = client.publish(topic, payload, qos=qos)
    if result.rc != mqtt.MQTT_ERR_SUCCESS:
        print(
            f"ACK publish rejected status={status}: {mqtt.error_string(result.rc)}",
            file=sys.stderr,
        )
        return
    print(f"ACK queued status={status} commandId={command_id}")


def request_test_command(
    spring_url: str,
    command_topic: str,
    command_type: str,
) -> None:
    command_id = f"python-command-{uuid.uuid4().hex[:8]}"
    command_payload = json.dumps(
        {"commandId": command_id, "type": command_type},
        separators=(",", ":"),
    )
    request_payload = json.dumps(
        {"topic": command_topic, "payload": command_payload},
        separators=(",", ":"),
    ).encode("utf-8")
    request = urllib.request.Request(
        spring_url,
        data=request_payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            response_body = response.read().decode("utf-8")
        print(f"Spring command requested commandId={command_id}")
        print(f"Spring response: {response_body}")
    except (urllib.error.URLError, TimeoutError) as error:
        print(f"Spring command request failed: {error}", file=sys.stderr)


def main() -> int:
    args = parse_args()
    ca_certificate = args.ca_certificate.expanduser().resolve()
    if not ca_certificate.is_file():
        print(f"CA certificate not found: {ca_certificate}", file=sys.stderr)
        return 2

    password = os.getenv("MQTT_PASSWORD") or getpass.getpass(
        f"MQTT password for {args.robot_id}: "
    )
    if not password:
        print("MQTT password must not be empty.", file=sys.stderr)
        return 2

    command_topic = f"robots/{args.robot_id}/commands"
    ack_topic = f"{command_topic}/ack"
    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"{args.robot_id}-mock-{uuid.uuid4().hex[:8]}",
        protocol=mqtt.MQTTv5,
    )
    client.username_pw_set(args.robot_id, password)
    client.tls_set_context(ssl.create_default_context(cafile=str(ca_certificate)))
    self_test_requested = threading.Event()

    def on_connect(
        connected_client: mqtt.Client,
        userdata: Any,
        flags: mqtt.ConnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        del userdata, flags, properties
        if reason_code.is_failure:
            print(f"MQTT connection failed: {reason_code}", file=sys.stderr)
            return
        connected_client.subscribe(command_topic, qos=args.qos)
        print(f"Connected. Waiting for commands on {command_topic}")

    def on_subscribe(
        connected_client: mqtt.Client,
        userdata: Any,
        mid: int,
        reason_code_list: list[mqtt.ReasonCode],
        properties: mqtt.Properties | None,
    ) -> None:
        del connected_client, userdata, mid, reason_code_list, properties
        if not args.self_test or self_test_requested.is_set():
            return
        self_test_requested.set()
        threading.Thread(
            target=request_test_command,
            args=(args.spring_url, command_topic, args.command_type),
            daemon=True,
        ).start()

    def on_message(
        connected_client: mqtt.Client,
        userdata: Any,
        message: mqtt.MQTTMessage,
    ) -> None:
        del userdata
        try:
            command = json.loads(message.payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            print(f"Ignored invalid command JSON: {error}", file=sys.stderr)
            return

        command_id = command.get("commandId")
        command_type = command.get("type")
        if not isinstance(command_id, str) or not command_id.strip():
            print("Ignored command without commandId.", file=sys.stderr)
            return
        if not isinstance(command_type, str) or not command_type.strip():
            publish_ack(
                connected_client,
                ack_topic,
                args.robot_id,
                command_id,
                "REJECTED",
                args.qos,
            )
            return

        print(f"Command received type={command_type} commandId={command_id}")
        publish_ack(
            connected_client,
            ack_topic,
            args.robot_id,
            command_id,
            "RECEIVED",
            args.qos,
        )
        publish_ack(
            connected_client,
            ack_topic,
            args.robot_id,
            command_id,
            "EXECUTED",
            args.qos,
        )

    client.on_connect = on_connect
    client.on_subscribe = on_subscribe
    client.on_message = on_message

    try:
        client.connect(args.host, args.port, keepalive=30)
        client.loop_forever()
    except KeyboardInterrupt:
        print("\nMock robot stopped.")
        return 0
    except (OSError, mqtt.MQTTException) as error:
        print(f"Mock robot failed: {error}", file=sys.stderr)
        return 1
    finally:
        client.disconnect()


if __name__ == "__main__":
    raise SystemExit(main())
