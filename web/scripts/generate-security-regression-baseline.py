#!/usr/bin/env python3
"""Aggregate the required Jenkins/DinD security regression evidence."""

from __future__ import annotations

import datetime as dt
import json
import os
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parents[1]
OUTPUT = (
    PROJECT_DIR
    / ".artifacts"
    / "security-regression"
    / "baseline-summary.json"
)
STATUS_GATES = (
    ("backend-full-test", "backend-test"),
    ("backend-security-acceptance", "security-acceptance"),
    ("frontend-test", "frontend-test"),
    ("frontend-lint", "frontend-lint"),
    ("frontend-production-build", "frontend-production-build"),
    ("authentication-temporary-db", "auth-security-integration"),
    ("actual-tls-mqtt-broker-e2e", "mqtt-broker-e2e"),
    ("report-llm-fallback", "report-llm-fallback"),
    ("resilience-recovery", "resilience-recovery"),
    ("volume-persistence", "volume-persistence"),
    ("git-history-secret-scan", "git-history-secret"),
    ("workspace-secret-scan", "workspace-secret"),
    ("container-secret-boundary", "secret-boundary"),
    ("image-layer-log-secret-scan", "image-layer-log-secret"),
    ("backend-dependency-scan", "backend-dependency-scan"),
    ("frontend-dependency-scan", "frontend-dependency-scan"),
    ("image-sbom-cve-scan", "sbom-cve"),
)
JUNIT_TASKS = ("test", "securityAcceptanceTest", "mqttBrokerE2E")


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00",
        "Z",
    )


def status_gate(label: str, marker: str) -> dict[str, object]:
    relative = Path(".artifacts") / "ci-results" / f"{marker}.status"
    path = PROJECT_DIR / relative
    value = path.read_text(encoding="utf-8").strip() if path.is_file() else "MISSING"
    return {
        "id": label,
        "status": "PASS" if value == "PASS" else "FAIL",
        "evidence": str(relative),
    }


def json_gate(
    label: str,
    relative_name: str,
    validator,
) -> dict[str, object]:
    relative = Path(".artifacts") / "security-regression" / relative_name
    path = PROJECT_DIR / relative
    passed = False
    if path.is_file():
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            passed = isinstance(payload, dict) and validator(payload)
        except (json.JSONDecodeError, AttributeError, TypeError):
            passed = False
    return {
        "id": label,
        "status": "PASS" if passed else "FAIL",
        "evidence": str(relative),
    }


def valid_authentication_evidence(payload: dict[str, object]) -> bool:
    isolation = payload.get("isolation")
    check_count = payload.get("checkCount")
    return (
        payload.get("status") == "PASS"
        and isinstance(check_count, int)
        and not isinstance(check_count, bool)
        and check_count > 0
        and isinstance(isolation, dict)
        and isolation.get("backendRemoved") is True
        and isolation.get("databaseRemoved") is True
    )


def valid_mqtt_evidence(payload: dict[str, object]) -> bool:
    result_files = payload.get("resultFiles")
    return (
        payload.get("status") == "PASS"
        and payload.get("brokerTransport") == "TLS"
        and isinstance(result_files, int)
        and not isinstance(result_files, bool)
        and result_files > 0
        and payload.get("cleanupPassed") is True
    )


def junit_gate(task: str) -> dict[str, object]:
    relative = (
        Path(".artifacts")
        / "backend-ci"
        / "build"
        / "test-results"
        / task
    )
    directory = PROJECT_DIR / relative
    results = (
        sorted(
            path
            for path in directory.glob("*.xml")
            if path.is_file() and path.stat().st_size > 0
        )
        if directory.is_dir()
        else []
    )
    return {
        "id": f"junit-{task}",
        "status": "PASS" if results else "FAIL",
        "evidence": f"{relative}/*.xml",
        "resultFiles": len(results),
    }


def main() -> int:
    gates = [status_gate(label, marker) for label, marker in STATUS_GATES]
    gates.extend(
        (
            json_gate(
                "authentication-isolation-evidence",
                "auth-security-integration.json",
                valid_authentication_evidence,
            ),
            json_gate(
                "mqtt-tls-and-credential-cleanup-evidence",
                "mqtt-broker-e2e.json",
                valid_mqtt_evidence,
            ),
        )
    )
    gates.extend(junit_gate(task) for task in JUNIT_TASKS)
    status = "PASS" if all(gate["status"] == "PASS" for gate in gates) else "FAIL"
    release_sha = os.getenv("RELEASE_SHA", "").strip()
    if len(release_sha) != 40 or any(character not in "0123456789abcdef" for character in release_sha):
        release_sha = None

    payload = {
        "schemaVersion": 1,
        "status": status,
        "capturedAtUtc": utc_now(),
        "executionBoundary": "Jenkins TLS DinD",
        "releaseSha": release_sha,
        "gates": gates,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_name(OUTPUT.name + ".tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(OUTPUT)
    print(f"Security regression baseline: {status}")
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
