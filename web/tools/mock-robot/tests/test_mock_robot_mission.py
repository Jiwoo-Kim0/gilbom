from __future__ import annotations

import importlib.util
import sys
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import paho.mqtt.client as mqtt


MODULE_PATH = Path(__file__).resolve().parents[1] / "mock_robot.py"
SPEC = importlib.util.spec_from_file_location("mock_robot", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load mock_robot.py")
mock_robot = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mock_robot
SPEC.loader.exec_module(mock_robot)


class PublishResult:
    rc = mqtt.MQTT_ERR_SUCCESS

    def wait_for_publish(self, timeout: int) -> None:
        del timeout


class FakeClient:
    def __init__(self, *args: object, **kwargs: object) -> None:
        del args, kwargs
        self.published: list[tuple[str, str, int, bool]] = []

    def username_pw_set(self, username: str, password: str) -> None:
        del username, password

    def tls_set_context(self, context: object) -> None:
        del context

    def will_set(
        self,
        topic: str,
        payload: str,
        qos: int,
        retain: bool,
    ) -> None:
        del topic, payload, qos, retain

    def publish(
        self,
        topic: str,
        payload: str,
        qos: int,
        retain: bool,
    ) -> PublishResult:
        self.published.append((topic, payload, qos, retain))
        return PublishResult()


def arguments(auto_complete_sec: int = 0) -> SimpleNamespace:
    return SimpleNamespace(
        mqtt_id="robot-mission-test",
        ca_certificate=Path(__file__),
        battery_percent=87.0,
        jetson_temp_c=41.5,
        jetson_temp_step_c=0.0,
        gps_invalid=False,
        latitude=37.5013,
        longitude=127.0396,
        telemetry_interval=0.1,
        mission_auto_complete_sec=auto_complete_sec,
        self_test=False,
        spring_url="http://localhost",
        command_type="START_MISSION",
        host="localhost",
        port=8883,
    )


def command(command_type: str, parameters: dict[str, str]) -> dict[str, object]:
    now = mock_robot.utc_now()
    return {
        "schema_version": mock_robot.SCHEMA_VERSION,
        "command_id": str(uuid.uuid4()),
        "command_type": command_type,
        "issued_at": mock_robot.format_instant(now),
        "expires_at": mock_robot.format_instant(
            now + mock_robot.timedelta(seconds=30)
        ),
        "requested_by": {
            "actor_type": "ADMIN",
            "actor_id": "mock-test",
        },
        "parameters": parameters,
    }


class MockRobotMissionTest(unittest.TestCase):
    def setUp(self) -> None:
        client_patcher = patch.object(mock_robot.mqtt, "Client", FakeClient)
        tls_patcher = patch.object(
            mock_robot.ssl,
            "create_default_context",
            return_value=object(),
        )
        self.addCleanup(client_patcher.stop)
        self.addCleanup(tls_patcher.stop)
        client_patcher.start()
        tls_patcher.start()
        self.robot = mock_robot.MockRobot(arguments(), "test-password")
        self.client = self.robot.client

    def published_json(self, suffix: str) -> list[dict[str, object]]:
        return [
            mock_robot.json.loads(payload)
            for topic, payload, _qos, _retain in self.client.published
            if topic.endswith(suffix)
        ]

    def test_start_pause_resume_publish_v2_mission_events_once(self) -> None:
        mission_id = "MIS-MOCK-E2E-001"
        start = command(
            "START_MISSION",
            {"mission_id": mission_id, "zone_id": "AREA-MOCK-E2E"},
        )
        self.robot.handle_command(start)
        self.robot.handle_command(start)
        self.robot.handle_command(
            command("PAUSE_MISSION", {"reason": "mock pause"})
        )
        self.robot.handle_command(command("RESUME_MISSION", {}))

        events = self.published_json("/events/mission")
        self.assertEqual(
            [event["event_type"] for event in events],
            ["MISSION_STARTED", "MISSION_PAUSED", "MISSION_RESUMED"],
        )
        self.assertTrue(all(event["schema_version"] == "2.0" for event in events))
        self.assertTrue(all(event["mqtt_id"] == self.robot.mqtt_id for event in events))
        self.assertTrue(all(event["boot_id"] == self.robot.boot_id for event in events))
        self.assertTrue(all(event["source_component"] == "mission" for event in events))
        self.assertTrue(all(event["ttl_sec"] == 300 for event in events))
        self.assertTrue(all(event["mission_id"] == mission_id for event in events))
        self.assertEqual(len({event["event_id"] for event in events}), 3)
        self.assertEqual(
            [event["seq"] for event in events],
            sorted(event["seq"] for event in events),
        )

    def test_auto_completion_follows_final_non_terminal_telemetry(self) -> None:
        self.robot.args.mission_auto_complete_sec = 3
        mission_id = "MIS-MOCK-E2E-002"
        self.robot.handle_command(
            command(
                "START_MISSION",
                {"mission_id": mission_id, "zone_id": "AREA-MOCK-E2E"},
            )
        )
        telemetry = self.robot.telemetry_payload()
        telemetry["mission"]["elapsed_sec"] = 3

        completed = self.robot.maybe_auto_complete_mission(telemetry)
        completed_again = self.robot.maybe_auto_complete_mission(telemetry)

        self.assertTrue(completed)
        self.assertFalse(completed_again)
        events = self.published_json("/events/mission")
        self.assertEqual(events[-1]["event_type"], "MISSION_COMPLETED")
        self.assertEqual(
            events[-1]["result"],
            {
                "duration_sec": 3,
                "incident_count": 0,
                "normal_detection_count": 0,
                "completion_reason": "MOCK_PLANNED_DURATION_REACHED",
            },
        )
        states = self.published_json("/state")
        self.assertEqual(states[-1]["mode"], "READY")
        self.assertEqual(states[-1]["mission"]["state"], "COMPLETED")

    def test_cancel_event_keeps_mission_id_and_requires_terminal_result(self) -> None:
        mission_id = "MIS-MOCK-E2E-003"
        self.robot.handle_command(
            command(
                "START_MISSION",
                {"mission_id": mission_id, "zone_id": "AREA-MOCK-E2E"},
            )
        )
        self.robot.handle_command(
            command("CANCEL_MISSION", {"reason": "mock cancel"})
        )

        event = self.published_json("/events/mission")[-1]
        self.assertEqual(event["event_type"], "MISSION_CANCELLED")
        self.assertEqual(event["mission_id"], mission_id)
        self.assertEqual(
            event["result"]["completion_reason"],
            "MOCK_COMMAND_CANCELLED",
        )
        self.assertIsNone(self.robot.mission_id)


if __name__ == "__main__":
    unittest.main()
