from __future__ import annotations

import json
import re
import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path

import yaml
from jsonschema import Draft7Validator, FormatChecker
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_ROOT = ROOT / "web" / "docs" / "mqtt-v2" / "schemas"
ASYNCAPI_PATH = ROOT / "web" / "docs" / "asyncapi-mqtt-v2.yaml"
HUMAN_CONTRACT_PATH = ROOT / "web" / "docs" / "final-mqtt-topic-schema-v2.md"
FIXTURE_ROOT = ROOT / "test" / "fixtures" / "mqtt" / "v2"
MANIFEST_PATH = FIXTURE_ROOT / "manifest.json"
TOPIC_PATTERN = re.compile(r"^tbs/v2/robots/([^/]+)/(.+)$")

TOPIC_POLICY = {
    "presence": (1, True),
    "state": (1, True),
    "telemetry": (0, False),
    "events/incident": (1, False),
    "events/battery": (1, False),
    "events/safety": (1, False),
    "events/mission": (1, False),
    "commands/result": (1, False),
    "commands/request": (1, False),
    "ingest/receipt": (1, False),
}

EXPECTED_CHANNELS = {
    "presence": "tbs/v2/robots/{mqtt_id}/presence",
    "state": "tbs/v2/robots/{mqtt_id}/state",
    "telemetry": "tbs/v2/robots/{mqtt_id}/telemetry",
    "incidentEvent": "tbs/v2/robots/{mqtt_id}/events/incident",
    "batteryEvent": "tbs/v2/robots/{mqtt_id}/events/battery",
    "safetyEvent": "tbs/v2/robots/{mqtt_id}/events/safety",
    "missionEvent": "tbs/v2/robots/{mqtt_id}/events/mission",
    "commandResult": "tbs/v2/robots/{mqtt_id}/commands/result",
    "commandRequest": "tbs/v2/robots/{mqtt_id}/commands/request",
    "ingestReceipt": "tbs/v2/robots/{mqtt_id}/ingest/receipt",
}

EXPECTED_OPERATIONS = {
    "receivePresence": ("receive", "presence", 1, True),
    "receiveState": ("receive", "state", 1, True),
    "receiveTelemetry": ("receive", "telemetry", 0, False),
    "receiveIncidentEvent": ("receive", "incidentEvent", 1, False),
    "receiveBatteryEvent": ("receive", "batteryEvent", 1, False),
    "receiveSafetyEvent": ("receive", "safetyEvent", 1, False),
    "receiveMissionEvent": ("receive", "missionEvent", 1, False),
    "receiveCommandResult": ("receive", "commandResult", 1, False),
    "sendCommandRequest": ("send", "commandRequest", 1, False),
    "sendIngestReceipt": ("send", "ingestReceipt", 1, False),
}


def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict:
    document = {}
    for key, value in pairs:
        if key in document:
            raise ValueError(f"duplicate JSON key: {key}")
        document[key] = value
    return document


def read_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=reject_duplicate_keys)


def parse_instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


class MqttV2ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schemas = {
            path.name: read_json(path)
            for path in sorted(SCHEMA_ROOT.glob("*.schema.json"))
        }
        registry = Registry()
        for schema in cls.schemas.values():
            registry = registry.with_resource(
                schema["$id"],
                Resource.from_contents(schema),
            )
        cls.validators = {
            name: Draft7Validator(
                schema,
                registry=registry,
                format_checker=FormatChecker(),
            )
            for name, schema in cls.schemas.items()
        }
        cls.manifest = read_json(MANIFEST_PATH)
        with ASYNCAPI_PATH.open(encoding="utf-8") as handle:
            cls.asyncapi = yaml.safe_load(handle)
        cls.human_contract = HUMAN_CONTRACT_PATH.read_text(encoding="utf-8")

    def test_every_json_schema_is_valid_draft_07(self) -> None:
        self.assertGreaterEqual(len(self.schemas), 11)
        for name, schema in self.schemas.items():
            with self.subTest(schema=name):
                self.assertEqual(
                    schema["$schema"],
                    "http://json-schema.org/draft-07/schema#",
                )
                Draft7Validator.check_schema(schema)

    def test_embedded_contract_constants_and_publish_policy_match_schemas(
        self,
    ) -> None:
        sys.path.insert(0, str(ROOT / "embedded"))
        try:
            from comms import contract
            from comms.topics import PUBLISH_POLICY, TopicKind
        finally:
            sys.path.pop(0)

        common = self.schemas["common.schema.json"]["definitions"]
        self.assertEqual(contract.NAMESPACE, "tbs/v2")
        self.assertEqual(contract.SCHEMA_VERSION, "2.0")
        self.assertEqual(contract.EVENT_TTL_MIN_SEC, 60)
        self.assertEqual(contract.EVENT_TTL_MAX_SEC, 300)
        self.assertEqual(set(common["robotMode"]["enum"]), set(contract.MODES))
        self.assertEqual(
            set(common["robotStateReason"]["enum"]),
            set(contract.REASON_CODES),
        )
        self.assertEqual(
            set(common["missionState"]["enum"]),
            set(contract.MISSION_STATES),
        )
        self.assertEqual(
            set(common["batteryState"]["enum"]),
            set(contract.BATTERY_STATES),
        )
        self.assertEqual(
            set(common["healthStatus"]["enum"]),
            set(contract.HEALTH_STATES),
        )
        self.assertEqual(
            set(common["eventSource"]["enum"]),
            set(contract.SOURCE_COMPONENTS),
        )
        self.assertEqual(
            set(
                self.schemas["command-result.schema.json"]
                ["properties"]["status"]["enum"]
            ),
            set(contract.COMMAND_STATUSES),
        )

        for kind, policy in PUBLISH_POLICY.items():
            with self.subTest(topic=kind.value):
                self.assertEqual(
                    (policy.qos, policy.retain),
                    TOPIC_POLICY[kind.value],
                )
        self.assertNotIn(TopicKind.COMMAND_REQUEST, PUBLISH_POLICY)

    def test_asyncapi_has_frozen_v2_topics_operations_and_schema_refs(self) -> None:
        document = self.asyncapi
        self.assertEqual(document["asyncapi"], "3.1.0")
        self.assertEqual(document["info"]["version"], "2.0.0")
        self.assertEqual(document["x-contract-status"], "FROZEN")
        self.assertEqual(str(document["x-frozen-at"]), "2026-07-30")

        channels = document["channels"]
        self.assertEqual(
            {name: channel["address"] for name, channel in channels.items()},
            EXPECTED_CHANNELS,
        )

        operations = document["operations"]
        self.assertEqual(set(operations), set(EXPECTED_OPERATIONS))
        for operation_name, expected in EXPECTED_OPERATIONS.items():
            action, channel_name, qos, retained = expected
            operation = operations[operation_name]
            with self.subTest(operation=operation_name):
                self.assertEqual(operation["action"], action)
                self.assertEqual(
                    operation["channel"]["$ref"],
                    f"#/channels/{channel_name}",
                )
                mqtt = operation["bindings"]["mqtt"]
                self.assertEqual(mqtt["qos"], qos)
                self.assertEqual(mqtt["bindingVersion"], "0.2.0")
                if action == "send":
                    self.assertEqual(mqtt["retain"], retained)
                else:
                    self.assertEqual(
                        operation["x-gilbom-publisher-retain"],
                        retained,
                    )

        messages = document["components"]["messages"]
        self.assertEqual(len(messages), 10)
        referenced_schemas = set()
        for message_name, message in messages.items():
            with self.subTest(message=message_name):
                self.assertEqual(message["contentType"], "application/json")
                payload = message["payload"]
                self.assertEqual(
                    payload["schemaFormat"],
                    "application/schema+json;version=draft-07",
                )
                schema_ref = payload["schema"]["$ref"]
                schema_path = (ASYNCAPI_PATH.parent / schema_ref).resolve()
                self.assertTrue(schema_path.is_file(), schema_ref)
                referenced_schemas.add(schema_path.name)
        self.assertEqual(
            referenced_schemas,
            set(self.schemas) - {
                "common.schema.json",
                "event-envelope.schema.json",
            },
        )

    def test_manifest_is_frozen_and_has_complete_expectations(self) -> None:
        self.assertEqual(self.manifest["contract_version"], "2.0")
        self.assertEqual(self.manifest["status"], "FROZEN")
        self.assertEqual(self.manifest["frozen_at"], "2026-07-30")
        required_expected = {
            "schema_valid",
            "processing",
            "error_code",
            "db_insert",
            "current_state_update",
            "row_delta",
            "execute_command",
        }
        ids = []
        for fixture in self.manifest["fixtures"]:
            ids.append(fixture["id"])
            with self.subTest(fixture=fixture["id"]):
                self.assertEqual(set(fixture["expected"]), required_expected)
                self.assertIn(fixture["schema"], self.schemas)
                self.assertTrue((FIXTURE_ROOT / fixture["file"]).is_file())
                match = TOPIC_PATTERN.fullmatch(fixture["topic"])
                self.assertIsNotNone(match)
                self.assertIn(match.group(2), TOPIC_POLICY)
        self.assertEqual(len(ids), len(set(ids)))

    def test_all_fixture_files_are_registered(self) -> None:
        registered = {
            fixture["file"]
            for fixture in self.manifest["fixtures"]
        }
        registered.update(
            fixture["baseline"]
            for fixture in self.manifest["fixtures"]
            if "baseline" in fixture
        )
        actual = {
            path.relative_to(FIXTURE_ROOT).as_posix()
            for directory in ("valid", "invalid")
            for path in (FIXTURE_ROOT / directory).glob("*.json")
        }
        self.assertEqual(registered, actual)

    def test_fixture_schema_results_match_manifest(self) -> None:
        for fixture in self.manifest["fixtures"]:
            payload = read_json(FIXTURE_ROOT / fixture["file"])
            errors = list(
                self.validators[fixture["schema"]].iter_errors(payload)
            )
            expected_valid = fixture["expected"]["schema_valid"]
            with self.subTest(fixture=fixture["id"]):
                if expected_valid:
                    self.assertEqual(
                        errors,
                        [],
                        "\n".join(error.message for error in errors),
                    )
                else:
                    self.assertNotEqual(errors, [])

    def test_semantic_scenarios_match_processing_contract(self) -> None:
        checks = {
            fixture["id"]: fixture
            for fixture in self.manifest["fixtures"]
            if "semantic_check" in fixture
        }
        self.assertEqual(
            {fixture["semantic_check"] for fixture in checks.values()},
            {
                "expires_after_issued",
                "bbox_order",
                "topic_payload_mqtt_id",
                "topic_qos",
                "freshness",
                "event_retry",
                "sequence_conflict",
                "previous_boot",
                "command_expired",
                "command_duplicate",
            },
        )

        for fixture in checks.values():
            payload = read_json(FIXTURE_ROOT / fixture["file"])
            check = fixture["semantic_check"]
            with self.subTest(fixture=fixture["id"], check=check):
                self.assertTrue(self._semantic_violation_or_scenario(
                    check,
                    fixture,
                    payload,
                ))

    def test_fixtures_contain_no_secret_material(self) -> None:
        forbidden_keys = {
            "password",
            "secret",
            "api_key",
            "private_key",
            "access_key",
        }
        for path in FIXTURE_ROOT.rglob("*.json"):
            document = read_json(path)
            serialized = json.dumps(document).lower()
            with self.subTest(path=path.name):
                for key in forbidden_keys:
                    self.assertNotIn(f'"{key}"', serialized)

    def test_mission_elapsed_time_semantics_match_human_contract(self) -> None:
        mission_schema = self.schemas["telemetry.schema.json"][
            "properties"
        ]["mission"]["anyOf"][0]
        description = mission_schema["properties"]["elapsed_sec"][
            "description"
        ]

        for phrase in ("벽시계", "PAUSED", "단조 증가"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, description)
                self.assertIn(phrase, self.human_contract)

        self.assertIn(
            "elapsed_sec / planned_duration_sec",
            self.human_contract,
        )
        self.assertNotIn(
            "계획 경로와 유효 GPS 궤적을 이용해 별도로 계산",
            self.human_contract,
        )

    def _semantic_violation_or_scenario(
        self,
        check: str,
        fixture: dict,
        payload: dict,
    ) -> bool:
        if check == "expires_after_issued":
            return parse_instant(payload["expires_at"]) <= parse_instant(
                payload["issued_at"]
            )
        if check == "bbox_order":
            x_min, y_min, x_max, y_max = payload["evidence"]["bbox_norm"]
            return not (x_min < x_max and y_min < y_max)
        if check == "topic_payload_mqtt_id":
            topic_mqtt_id = TOPIC_PATTERN.fullmatch(
                fixture["topic"]
            ).group(1)
            return topic_mqtt_id != payload["mqtt_id"]
        if check == "topic_qos":
            relative = TOPIC_PATTERN.fullmatch(fixture["topic"]).group(2)
            return fixture["qos"] != TOPIC_POLICY[relative][0]
        if check == "freshness":
            received_at = parse_instant(fixture["received_at"])
            if "ttl_sec" in payload:
                return received_at >= (
                    parse_instant(payload["occurred_at"])
                    + timedelta(seconds=payload["ttl_sec"])
                )
            if "sampled_at" in payload:
                return received_at >= (
                    parse_instant(payload["sampled_at"])
                    + timedelta(seconds=60)
                )
            raise AssertionError("freshness scenario has no contract timestamp")
        if check == "event_retry":
            baseline = read_json(FIXTURE_ROOT / fixture["baseline"])
            retry = dict(payload)
            original = dict(baseline)
            retry.pop("sent_at")
            original.pop("sent_at")
            return (
                retry == original
                and payload["sent_at"] != baseline["sent_at"]
            )
        if check == "sequence_conflict":
            baseline = read_json(FIXTURE_ROOT / fixture["baseline"])
            same_wire_sequence = all(
                payload[key] == baseline[key]
                for key in ("mqtt_id", "boot_id", "seq")
            )
            return same_wire_sequence and payload["event_id"] != baseline["event_id"]
        if check == "previous_boot":
            return fixture["context"]["boot_status"] == "REPLACED_BY_NEW_BOOT"
        if check == "command_expired":
            return parse_instant(fixture["received_at"]) > parse_instant(
                payload["expires_at"]
            )
        if check == "command_duplicate":
            return (
                fixture["context"]["command_id_processed"]
                and not fixture["expected"]["execute_command"]
            )
        raise AssertionError(f"unsupported semantic check: {check}")


if __name__ == "__main__":
    unittest.main()
