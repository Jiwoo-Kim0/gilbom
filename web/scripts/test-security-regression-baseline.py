#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path


SCRIPT = Path(__file__).with_name("generate-security-regression-baseline.py")
SPEC = importlib.util.spec_from_file_location("security_regression_baseline", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Cannot load security regression baseline generator")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SecurityRegressionBaselineTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.project = Path(self.temporary.name)
        MODULE.PROJECT_DIR = self.project
        MODULE.OUTPUT = (
            self.project
            / ".artifacts"
            / "security-regression"
            / "baseline-summary.json"
        )
        for _, marker in MODULE.STATUS_GATES:
            path = (
                self.project
                / ".artifacts"
                / "ci-results"
                / f"{marker}.status"
            )
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("PASS\n", encoding="utf-8")
        evidence = {
            "auth-security-integration.json": {
                "status": "PASS",
                "checkCount": 17,
                "isolation": {
                    "backendRemoved": True,
                    "databaseRemoved": True,
                },
            },
            "mqtt-broker-e2e.json": {
                "status": "PASS",
                "brokerTransport": "TLS",
                "resultFiles": 2,
                "cleanupPassed": True,
            },
        }
        for name, payload in evidence.items():
            path = (
                self.project
                / ".artifacts"
                / "security-regression"
                / name
            )
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                json.dumps(payload) + "\n",
                encoding="utf-8",
            )
        for task in MODULE.JUNIT_TASKS:
            path = (
                self.project
                / ".artifacts"
                / "backend-ci"
                / "build"
                / "test-results"
                / task
                / "TEST-regression.xml"
            )
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("<testsuite tests=\"1\" failures=\"0\"/>\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def summary(self) -> dict[str, object]:
        return json.loads(MODULE.OUTPUT.read_text(encoding="utf-8"))

    def run_generator(self) -> int:
        with redirect_stdout(io.StringIO()):
            return MODULE.main()

    def test_all_required_evidence_passes(self) -> None:
        self.assertEqual(self.run_generator(), 0)
        summary = self.summary()
        self.assertEqual(summary["status"], "PASS")
        self.assertTrue(
            all(gate["status"] == "PASS" for gate in summary["gates"])
        )

    def test_missing_marker_fails_closed(self) -> None:
        missing = (
            self.project
            / ".artifacts"
            / "ci-results"
            / "mqtt-broker-e2e.status"
        )
        missing.unlink()
        self.assertEqual(self.run_generator(), 1)
        summary = self.summary()
        self.assertEqual(summary["status"], "FAIL")
        gate = next(
            item
            for item in summary["gates"]
            if item["id"] == "actual-tls-mqtt-broker-e2e"
        )
        self.assertEqual(gate["status"], "FAIL")

    def test_mqtt_cleanup_failure_fails_closed(self) -> None:
        evidence = (
            self.project
            / ".artifacts"
            / "security-regression"
            / "mqtt-broker-e2e.json"
        )
        payload = json.loads(evidence.read_text(encoding="utf-8"))
        payload["cleanupPassed"] = False
        evidence.write_text(json.dumps(payload) + "\n", encoding="utf-8")
        self.assertEqual(self.run_generator(), 1)
        gate = next(
            item
            for item in self.summary()["gates"]
            if item["id"] == "mqtt-tls-and-credential-cleanup-evidence"
        )
        self.assertEqual(gate["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
