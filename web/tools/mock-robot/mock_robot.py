"""Run a TLS MQTT v2 mock robot for local end-to-end tests."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import paho.mqtt.client as mqtt


SCHEMA_VERSION = "2.0"
COMMAND_TYPES = (
    "START_MISSION",
    "PAUSE_MISSION",
    "RESUME_MISSION",
    "CANCEL_MISSION",
    "EMERGENCY_STOP",
    "CLEAR_EMERGENCY_STOP",
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


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def format_instant(value: datetime | None = None) -> str:
    instant = value or utc_now()
    return instant.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def topic(mqtt_id: str, relative_path: str) -> str:
    return f"tbs/v2/robots/{mqtt_id}/{relative_path}"


def parse_instant(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Publish MQTT v2 Presence, State and Telemetry; "
            "subscribe to Commands and Ingest Receipts."
        )
    )
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8883)
    parser.add_argument(
        "--mqtt-id",
        "--robot-id",
        dest="mqtt_id",
        default="robot-test",
        help="MQTT external identity; --robot-id is kept as a compatibility alias.",
    )
    parser.add_argument("--ca-certificate", type=Path, default=DEFAULT_CA_CERTIFICATE)
    parser.add_argument("--telemetry-interval", type=float, default=5.0)
    parser.add_argument(
        "--mission-auto-complete-sec",
        type=int,
        default=0,
        help=(
            "Publish MISSION_COMPLETED after this many wall-clock seconds "
            "while a mission is RUNNING; zero disables automatic completion."
        ),
    )
    parser.add_argument("--latitude", type=float, default=37.5013)
    parser.add_argument("--longitude", type=float, default=127.0396)
    parser.add_argument("--battery-percent", type=float, default=87.0)
    parser.add_argument("--jetson-temp-c", type=float, default=41.5)
    parser.add_argument(
        "--jetson-temp-step-c",
        type=float,
        default=0.0,
        help=(
            "Increase Jetson temperature by this amount after each telemetry "
            "publish and reverse direction after five steps."
        ),
    )
    parser.add_argument("--gps-invalid", action="store_true")
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Call the local Spring MQTT v2 command endpoint after subscribing.",
    )
    parser.add_argument(
        "--spring-url",
        default=(
            "http://localhost:8080/api/mqtt/v2/robots/"
            "{mqtt_id}/commands/request"
        ),
    )
    parser.add_argument(
        "--command-type",
        default="EMERGENCY_STOP",
        choices=COMMAND_TYPES,
    )
    parser.add_argument(
        "--command-outcome",
        action="append",
        default=[],
        metavar="TYPE=STATUS:REASON",
        help=(
            "Override one command's final result for an E2E scenario. "
            "STATUS must be COMPLETED, FAILED, or REJECTED."
        ),
    )
    parser.add_argument(
        "--command-delay-sec",
        action="append",
        default=[],
        metavar="TYPE=SECONDS",
        help=(
            "Delay ACK and final Result for one command type. "
            "Useful for expiry and late-Result E2E scenarios."
        ),
    )
    parser.add_argument(
        "--duplicate-results-for",
        action="append",
        default=[],
        choices=COMMAND_TYPES,
        metavar="TYPE",
        help=(
            "Publish the same ACCEPTED and final Result twice for one "
            "command type to verify server-side deduplication."
        ),
    )
    args = parser.parse_args()
    if args.telemetry_interval <= 0:
        parser.error("--telemetry-interval must be greater than zero")
    if args.mission_auto_complete_sec < 0:
        parser.error("--mission-auto-complete-sec must be zero or greater")
    if not 0.0 <= args.battery_percent <= 100.0:
        parser.error("--battery-percent must be between 0 and 100")
    if args.jetson_temp_step_c < 0.0:
        parser.error("--jetson-temp-step-c must be zero or greater")
    args.command_outcomes = {}
    for definition in args.command_outcome:
        try:
            command_type, outcome = definition.split("=", 1)
            status, reason_code = outcome.split(":", 1)
        except ValueError:
            parser.error(
                "--command-outcome must use TYPE=STATUS:REASON"
            )
        if (
            command_type not in COMMAND_TYPES
            or status not in {"COMPLETED", "FAILED", "REJECTED"}
            or not reason_code
        ):
            parser.error(
                "--command-outcome contains an unsupported type, "
                "status, or empty reason"
            )
        args.command_outcomes[command_type] = (status, reason_code)
    args.command_delays = {}
    for definition in args.command_delay_sec:
        try:
            command_type, raw_delay = definition.split("=", 1)
            delay_seconds = float(raw_delay)
        except ValueError:
            parser.error(
                "--command-delay-sec must use TYPE=SECONDS"
            )
        if command_type not in COMMAND_TYPES or delay_seconds < 0:
            parser.error(
                "--command-delay-sec contains an unsupported type "
                "or negative delay"
            )
        args.command_delays[command_type] = delay_seconds
    args.duplicate_results_for = set(args.duplicate_results_for)
    return args


def request_test_command(
    spring_url: str,
    mqtt_id: str,
    command_type: str,
) -> None:
    issued_at = utc_now()
    parameters: dict[str, str] = {}
    if command_type == "START_MISSION":
        parameters = {
            "mission_id": "MIS-MOCK-001",
            "zone_id": "ZONE-MOCK-001",
        }
    elif command_type != "RESUME_MISSION":
        parameters = {"reason": "mock_robot_self_test"}

    command_payload = {
        "schema_version": SCHEMA_VERSION,
        "command_id": str(uuid.uuid4()),
        "command_type": command_type,
        "issued_at": format_instant(issued_at),
        "expires_at": format_instant(issued_at + timedelta(seconds=30)),
        "requested_by": {
            "actor_type": "SYSTEM",
            "actor_id": "mock-robot-self-test",
        },
        "parameters": parameters,
    }
    encoded_mqtt_id = urllib.parse.quote(mqtt_id, safe="")
    url = spring_url.format(mqtt_id=encoded_mqtt_id)
    request = urllib.request.Request(
        url,
        data=json.dumps(command_payload, separators=(",", ":")).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            response_body = response.read().decode("utf-8")
        print(f"Spring command requested commandId={command_payload['command_id']}")
        print(f"Spring response: {response_body}")
    except (urllib.error.URLError, TimeoutError) as error:
        print(f"Spring command request failed: {error}", file=sys.stderr)


class MockRobot:

    def __init__(self, args: argparse.Namespace, password: str) -> None:
        self.args = args
        self.mqtt_id = args.mqtt_id
        self.boot_id = str(uuid.uuid4())
        self.sequence = 0
        self.sequence_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.stop_event = threading.Event()
        self.connected_event = threading.Event()
        self.self_test_requested = threading.Event()
        self.telemetry_thread: threading.Thread | None = None
        self.processed_commands: dict[str, dict[str, Any]] = {}
        self.command_outcomes: dict[str, tuple[str, str]] = (
            args.command_outcomes
        )
        self.command_delays: dict[str, float] = args.command_delays
        self.duplicate_results_for: set[str] = args.duplicate_results_for

        self.mode = "READY"
        self.state_reason = "NONE"
        self.mission_id: str | None = None
        self.mission_state: str | None = None
        self.mission_started_at: datetime | None = None
        self.jetson_temp_c = args.jetson_temp_c
        self.jetson_temp_direction = 1

        self.presence_topic = topic(self.mqtt_id, "presence")
        self.state_topic = topic(self.mqtt_id, "state")
        self.telemetry_topic = topic(self.mqtt_id, "telemetry")
        self.mission_event_topic = topic(self.mqtt_id, "events/mission")
        self.command_request_topic = topic(self.mqtt_id, "commands/request")
        self.command_result_topic = topic(self.mqtt_id, "commands/result")
        self.ingest_receipt_topic = topic(self.mqtt_id, "ingest/receipt")

        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"{self.mqtt_id}-mock-{uuid.uuid4().hex[:8]}",
            protocol=mqtt.MQTTv5,
        )
        self.client.username_pw_set(self.mqtt_id, password)
        self.client.tls_set_context(
            ssl.create_default_context(
                cafile=str(args.ca_certificate.expanduser().resolve())
            )
        )
        self.client.will_set(
            self.presence_topic,
            payload=self.encoded_json(self.offline_presence("UNGRACEFUL_DISCONNECT")),
            qos=1,
            retain=True,
        )
        self.client.on_connect = self.on_connect
        self.client.on_disconnect = self.on_disconnect
        self.client.on_subscribe = self.on_subscribe
        self.client.on_message = self.on_message

    def next_sequence(self) -> int:
        with self.sequence_lock:
            self.sequence += 1
            return self.sequence

    def encoded_json(self, payload: dict[str, Any]) -> str:
        return json.dumps(payload, separators=(",", ":"), ensure_ascii=False)

    def publish_json(
        self,
        mqtt_topic: str,
        payload: dict[str, Any],
        qos: int,
        retain: bool,
    ) -> mqtt.MQTTMessageInfo:
        result = self.client.publish(
            mqtt_topic,
            self.encoded_json(payload),
            qos=qos,
            retain=retain,
        )
        if result.rc != mqtt.MQTT_ERR_SUCCESS:
            print(
                f"Publish rejected topic={mqtt_topic}: "
                f"{mqtt.error_string(result.rc)}",
                file=sys.stderr,
            )
        return result

    def online_presence(self) -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "mqtt_id": self.mqtt_id,
            "boot_id": self.boot_id,
            "status": "ONLINE",
            "connected_at": format_instant(),
        }

    def offline_presence(self, reason: str) -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "mqtt_id": self.mqtt_id,
            "boot_id": self.boot_id,
            "status": "OFFLINE",
            "reason": reason,
        }

    def battery_state(self) -> str:
        if self.args.battery_percent <= 10.0:
            return "CRITICAL"
        if self.args.battery_percent <= 20.0:
            return "LOW"
        return "NORMAL"

    def state_payload(self) -> dict[str, Any]:
        with self.state_lock:
            mission = None
            if self.mission_id is not None and self.mission_state is not None:
                mission = {
                    "mission_id": self.mission_id,
                    "state": self.mission_state,
                }
            return {
                "schema_version": SCHEMA_VERSION,
                "mqtt_id": self.mqtt_id,
                "boot_id": self.boot_id,
                "seq": self.next_sequence(),
                "updated_at": format_instant(),
                "mode": self.mode,
                "reason_code": self.state_reason,
                "mission": mission,
                "battery": {
                    "valid": True,
                    "percent": self.args.battery_percent,
                    "state": self.battery_state(),
                },
                "health": {
                    "camera": "OK",
                    "lidar": "OK",
                    "gps": "DEGRADED" if self.args.gps_invalid else "OK",
                    "battery": "OK",
                    "motor": "UNKNOWN",
                    "storage": "OK",
                },
            }

    def telemetry_payload(self) -> dict[str, Any]:
        with self.state_lock:
            mission = None
            if self.mission_id is not None and self.mission_state is not None:
                elapsed = 0
                if self.mission_started_at is not None:
                    elapsed = max(
                        0,
                        int((utc_now() - self.mission_started_at).total_seconds()),
                    )
                mission = {
                    "state": self.mission_state,
                    "elapsed_sec": elapsed,
                    "incident_count": 0,
                }

            position = {
                "valid": not self.args.gps_invalid,
                "lat": None if self.args.gps_invalid else self.args.latitude,
                "lon": None if self.args.gps_invalid else self.args.longitude,
                "heading_deg": None,
            }
            moving = self.mode == "PATROLLING"
            return {
                "schema_version": SCHEMA_VERSION,
                "message_id": str(uuid.uuid4()),
                "mqtt_id": self.mqtt_id,
                "boot_id": self.boot_id,
                "seq": self.next_sequence(),
                "sampled_at": format_instant(),
                "mission_id": self.mission_id,
                "position": position,
                "motion": {
                    "moving": moving,
                    "speed_mps": None,
                },
                "battery": {
                    "valid": True,
                    "percent": self.args.battery_percent,
                    "state": self.battery_state(),
                    "source": "MEASURED",
                    "voltage_v": None,
                    "current_a": None,
                    "charging": False,
                    "battery_temperature_c": None,
                    "estimated_runtime_sec": None,
                },
                "system": {
                    "jetson_temp_c": self.jetson_temp_c,
                },
                "mission": mission,
            }

    def advance_telemetry_values(self) -> None:
        step = self.args.jetson_temp_step_c
        if step == 0.0:
            return

        with self.state_lock:
            lower_bound = self.args.jetson_temp_c
            upper_bound = lower_bound + step * 5
            next_temperature = (
                self.jetson_temp_c
                + step * self.jetson_temp_direction
            )
            if (
                next_temperature > upper_bound
                or next_temperature < lower_bound
            ):
                self.jetson_temp_direction *= -1
                next_temperature = (
                    self.jetson_temp_c
                    + step * self.jetson_temp_direction
                )
            self.jetson_temp_c = round(next_temperature, 3)

    def current_result_state(self) -> dict[str, str]:
        with self.state_lock:
            return {
                "mode": self.mode,
                "reason_code": self.state_reason,
            }

    def command_result(
        self,
        command_id: str,
        status: str,
        received_at: datetime,
        reason_code: str,
    ) -> dict[str, Any]:
        result = {
            "schema_version": SCHEMA_VERSION,
            "result_id": str(uuid.uuid4()),
            "command_id": command_id,
            "mqtt_id": self.mqtt_id,
            "status": status,
            "received_at": format_instant(received_at),
            "reason_code": reason_code,
            "state": self.current_result_state(),
        }
        if status != "ACCEPTED":
            result["completed_at"] = format_instant()
        return result

    def mission_event(
        self,
        event_type: str,
        mission_id: str,
        *,
        duration_sec: int | None = None,
        completion_reason: str | None = None,
    ) -> dict[str, Any]:
        occurred_at = utc_now()
        event: dict[str, Any] = {
            "schema_version": SCHEMA_VERSION,
            "event_id": str(uuid.uuid4()),
            "event_type": event_type,
            "mqtt_id": self.mqtt_id,
            "boot_id": self.boot_id,
            "seq": self.next_sequence(),
            "source_component": "mission",
            "occurred_at": format_instant(occurred_at),
            "sent_at": format_instant(),
            "ttl_sec": 300,
            "mission_id": mission_id,
        }
        if completion_reason is not None:
            event["result"] = {
                "duration_sec": max(0, duration_sec or 0),
                "incident_count": 0,
                "normal_detection_count": 0,
                "completion_reason": completion_reason,
            }
        return event

    def publish_mission_event(
        self,
        event_type: str,
        mission_id: str,
        *,
        duration_sec: int | None = None,
        completion_reason: str | None = None,
    ) -> None:
        event = self.mission_event(
            event_type,
            mission_id,
            duration_sec=duration_sec,
            completion_reason=completion_reason,
        )
        self.publish_json(
            self.mission_event_topic,
            event,
            qos=1,
            retain=False,
        )
        print(
            f"Mission event published missionId={mission_id} "
            f"eventType={event_type}"
        )

    def on_connect(
        self,
        client: mqtt.Client,
        userdata: Any,
        flags: mqtt.ConnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        del userdata, flags, properties
        if reason_code.is_failure:
            print(f"MQTT connection failed: {reason_code}", file=sys.stderr)
            return

        self.connected_event.set()
        client.subscribe(
            [
                (self.command_request_topic, 1),
                (self.ingest_receipt_topic, 1),
            ]
        )
        self.publish_json(
            self.presence_topic,
            self.online_presence(),
            qos=1,
            retain=True,
        )
        self.publish_json(
            self.state_topic,
            self.state_payload(),
            qos=1,
            retain=True,
        )
        if self.telemetry_thread is None or not self.telemetry_thread.is_alive():
            self.telemetry_thread = threading.Thread(
                target=self.telemetry_loop,
                name=f"{self.mqtt_id}-telemetry",
                daemon=True,
            )
            self.telemetry_thread.start()
        print(
            "Connected. Subscribing to "
            f"{self.command_request_topic} and {self.ingest_receipt_topic}"
        )

    def on_disconnect(
        self,
        client: mqtt.Client,
        userdata: Any,
        disconnect_flags: mqtt.DisconnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        del client, userdata, disconnect_flags, properties
        self.connected_event.clear()
        if not self.stop_event.is_set():
            print(f"MQTT disconnected; waiting for reconnect: {reason_code}")

    def on_subscribe(
        self,
        client: mqtt.Client,
        userdata: Any,
        mid: int,
        reason_code_list: list[mqtt.ReasonCode],
        properties: mqtt.Properties | None,
    ) -> None:
        del client, userdata, mid, properties
        if any(reason.is_failure for reason in reason_code_list):
            print(
                f"MQTT subscribe rejected: {reason_code_list}",
                file=sys.stderr,
            )
            return
        if not self.args.self_test or self.self_test_requested.is_set():
            return
        self.self_test_requested.set()
        threading.Thread(
            target=request_test_command,
            args=(
                self.args.spring_url,
                self.mqtt_id,
                self.args.command_type,
            ),
            daemon=True,
        ).start()

    def on_message(
        self,
        client: mqtt.Client,
        userdata: Any,
        message: mqtt.MQTTMessage,
    ) -> None:
        del client, userdata
        try:
            payload = json.loads(message.payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            print(f"Ignored invalid MQTT JSON: {error}", file=sys.stderr)
            return

        if message.topic == self.command_request_topic:
            self.handle_command(payload)
            return
        if message.topic == self.ingest_receipt_topic:
            self.handle_ingest_receipt(payload)
            return
        print(f"Ignored unexpected topic: {message.topic}", file=sys.stderr)

    def handle_command(self, command: dict[str, Any]) -> None:
        received_at = utc_now()
        command_id = command.get("command_id")
        if not isinstance(command_id, str):
            print("Ignored command without command_id.", file=sys.stderr)
            return
        try:
            uuid.UUID(command_id)
        except ValueError:
            print("Ignored command with invalid command_id.", file=sys.stderr)
            return

        previous_result = self.processed_commands.get(command_id)
        if previous_result is not None:
            self.publish_json(
                self.command_result_topic,
                previous_result,
                qos=1,
                retain=False,
            )
            print(f"Duplicate command replayed without execution: {command_id}")
            return

        if command.get("schema_version") != SCHEMA_VERSION:
            self.publish_final_result(
                command_id,
                received_at,
                "REJECTED",
                "UNSUPPORTED_SCHEMA_VERSION",
            )
            return

        expires_at = parse_instant(command.get("expires_at"))
        if expires_at is None or expires_at <= received_at:
            self.publish_final_result(
                command_id,
                received_at,
                "EXPIRED",
                "COMMAND_EXPIRED",
            )
            return

        command_type = command.get("command_type")
        delay_seconds = self.command_delays.get(command_type, 0.0)
        if delay_seconds > 0:
            print(
                f"Delaying command result commandId={command_id} "
                f"type={command_type} seconds={delay_seconds}"
            )
            if self.stop_event.wait(delay_seconds):
                return

        accepted = self.command_result(
            command_id,
            "ACCEPTED",
            received_at,
            "ACCEPTED",
        )
        self.publish_json(
            self.command_result_topic,
            accepted,
            qos=1,
            retain=False,
        )
        duplicate_results = command_type in self.duplicate_results_for
        if duplicate_results:
            self.publish_json(
                self.command_result_topic,
                accepted,
                qos=1,
                retain=False,
            )
            print(
                f"Duplicate ACCEPTED Result published commandId={command_id}"
            )

        with self.state_lock:
            mission_id_before = self.mission_id
            mission_started_at_before = self.mission_started_at

        overridden = self.command_outcomes.get(command_type)
        if overridden is None:
            status, reason_code = self.apply_command(command)
        else:
            status, reason_code = overridden
            print(
                f"Command outcome overridden commandId={command_id} "
                f"type={command_type} status={status} reason={reason_code}"
            )
        if status == "COMPLETED":
            with self.state_lock:
                mission_id_after = self.mission_id
            mission_id = (
                mission_id_before
                if command_type == "CANCEL_MISSION"
                else mission_id_after
            )
            event_type = {
                "START_MISSION": "MISSION_STARTED",
                "PAUSE_MISSION": "MISSION_PAUSED",
                "RESUME_MISSION": "MISSION_RESUMED",
                "CANCEL_MISSION": "MISSION_CANCELLED",
            }.get(command_type)
            if event_type is not None and mission_id is not None:
                terminal = event_type == "MISSION_CANCELLED"
                duration_sec = None
                if terminal and mission_started_at_before is not None:
                    duration_sec = max(
                        0,
                        int(
                            (
                                utc_now() - mission_started_at_before
                            ).total_seconds()
                        ),
                    )
                self.publish_mission_event(
                    event_type,
                    mission_id,
                    duration_sec=duration_sec,
                    completion_reason=(
                        "MOCK_COMMAND_CANCELLED" if terminal else None
                    ),
                )
            self.publish_json(
                self.state_topic,
                self.state_payload(),
                qos=1,
                retain=True,
            )
        final_result = self.publish_final_result(
            command_id,
            received_at,
            status,
            reason_code,
        )
        if duplicate_results:
            self.publish_json(
                self.command_result_topic,
                final_result,
                qos=1,
                retain=False,
            )
            print(
                f"Duplicate final Result published commandId={command_id}"
            )

    def apply_command(self, command: dict[str, Any]) -> tuple[str, str]:
        command_type = command.get("command_type")
        parameters = command.get("parameters")
        if not isinstance(parameters, dict):
            return "REJECTED", "INVALID_PARAMETERS"

        with self.state_lock:
            if command_type == "START_MISSION":
                mission_id = parameters.get("mission_id")
                zone_id = parameters.get("zone_id")
                if not isinstance(mission_id, str) or not isinstance(zone_id, str):
                    return "REJECTED", "INVALID_PARAMETERS"
                self.mission_id = mission_id
                self.mission_state = "RUNNING"
                self.mission_started_at = utc_now()
                self.mode = "PATROLLING"
                self.state_reason = "NONE"
                return "COMPLETED", "OK"

            if command_type == "PAUSE_MISSION":
                if self.mission_id is None:
                    return "FAILED", "NO_ACTIVE_MISSION"
                self.mission_state = "PAUSED"
                self.mode = "PAUSED"
                self.state_reason = "REMOTE_PAUSE"
                return "COMPLETED", "OK"

            if command_type == "RESUME_MISSION":
                if self.mission_id is None:
                    return "FAILED", "NO_ACTIVE_MISSION"
                self.mission_state = "RUNNING"
                self.mode = "PATROLLING"
                self.state_reason = "NONE"
                return "COMPLETED", "OK"

            if command_type == "CANCEL_MISSION":
                if self.mission_id is None:
                    return "FAILED", "NO_ACTIVE_MISSION"
                self.mission_id = None
                self.mission_state = None
                self.mission_started_at = None
                self.mode = "READY"
                self.state_reason = "NONE"
                return "COMPLETED", "OK"

            if command_type == "EMERGENCY_STOP":
                self.mode = "SAFE_STOP"
                self.state_reason = "REMOTE_ESTOP"
                return "COMPLETED", "OK"

            if command_type == "CLEAR_EMERGENCY_STOP":
                if self.mode != "SAFE_STOP":
                    return "REJECTED", "ALREADY_IN_STATE"
                self.mode = "PAUSED" if self.mission_id is not None else "READY"
                self.state_reason = (
                    "REMOTE_PAUSE" if self.mission_id is not None else "NONE"
                )
                return "COMPLETED", "OK"

        return "REJECTED", "UNSUPPORTED_COMMAND_TYPE"

    def publish_final_result(
        self,
        command_id: str,
        received_at: datetime,
        status: str,
        reason_code: str,
    ) -> dict[str, Any]:
        result = self.command_result(
            command_id,
            status,
            received_at,
            reason_code,
        )
        self.processed_commands[command_id] = result
        self.publish_json(
            self.command_result_topic,
            result,
            qos=1,
            retain=False,
        )
        print(
            f"Command result published commandId={command_id} "
            f"status={status} reason={reason_code}"
        )
        return result

    def handle_ingest_receipt(self, receipt: dict[str, Any]) -> None:
        if receipt.get("schema_version") != SCHEMA_VERSION:
            print("Ignored receipt with unsupported schema_version.", file=sys.stderr)
            return
        print(
            "Ingest receipt received "
            f"eventId={receipt.get('event_id')} "
            f"status={receipt.get('ingest_status')}"
        )

    def telemetry_loop(self) -> None:
        while not self.stop_event.is_set():
            if self.connected_event.is_set():
                telemetry = self.telemetry_payload()
                result = self.publish_json(
                    self.telemetry_topic,
                    telemetry,
                    qos=0,
                    retain=False,
                )
                if result.rc == mqtt.MQTT_ERR_SUCCESS:
                    self.advance_telemetry_values()
                    self.maybe_auto_complete_mission(telemetry)
            self.stop_event.wait(self.args.telemetry_interval)

    def maybe_auto_complete_mission(
        self,
        telemetry: dict[str, Any],
    ) -> bool:
        threshold = self.args.mission_auto_complete_sec
        mission = telemetry.get("mission")
        mission_id = telemetry.get("mission_id")
        if (
            threshold <= 0
            or not isinstance(mission, dict)
            or not isinstance(mission_id, str)
            or mission.get("state") != "RUNNING"
            or not isinstance(mission.get("elapsed_sec"), int)
            or mission["elapsed_sec"] < threshold
        ):
            return False

        with self.state_lock:
            if (
                self.mission_id != mission_id
                or self.mission_state != "RUNNING"
            ):
                return False
            self.mission_state = "COMPLETED"
            self.mode = "READY"
            self.state_reason = "NONE"

        self.publish_mission_event(
            "MISSION_COMPLETED",
            mission_id,
            duration_sec=mission["elapsed_sec"],
            completion_reason="MOCK_PLANNED_DURATION_REACHED",
        )
        self.publish_json(
            self.state_topic,
            self.state_payload(),
            qos=1,
            retain=True,
        )
        return True

    def run(self) -> int:
        try:
            self.client.connect(self.args.host, self.args.port, keepalive=30)
            self.client.loop_forever(retry_first_connection=False)
            return 0
        except KeyboardInterrupt:
            print("\nMock robot stopped.")
            return 0
        except (OSError, mqtt.MQTTException) as error:
            print(f"Mock robot failed: {error}", file=sys.stderr)
            return 1
        finally:
            self.shutdown()

    def shutdown(self) -> None:
        self.stop_event.set()
        if self.connected_event.is_set():
            result = self.publish_json(
                self.presence_topic,
                self.offline_presence("GRACEFUL_SHUTDOWN"),
                qos=1,
                retain=True,
            )
            result.wait_for_publish(timeout=3)
        self.client.disconnect()


def main() -> int:
    args = parse_args()
    ca_certificate = args.ca_certificate.expanduser().resolve()
    if not ca_certificate.is_file():
        print(f"CA certificate not found: {ca_certificate}", file=sys.stderr)
        return 2

    password = os.getenv("MQTT_PASSWORD") or getpass.getpass(
        f"MQTT password for {args.mqtt_id}: "
    )
    if not password:
        print("MQTT password must not be empty.", file=sys.stderr)
        return 2

    return MockRobot(args, password).run()


if __name__ == "__main__":
    raise SystemExit(main())
