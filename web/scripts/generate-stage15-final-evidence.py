#!/usr/bin/env python3
"""Combine Stage 9, 12, 14 and host evidence into the Stage 15 verdict."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
from pathlib import Path


FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
REQUIRED_CI_RESULTS = {
    "checkout-full": "Checkout이 shallow 아님",
    "git-history-secret": "Git history Secret Scan PASS",
    "workspace-secret": "Checkout workspace Secret Scan PASS",
    "openapi-lint": "OpenAPI Scan PASS",
    "backend-dependency-scan": "Backend Dependency Scan PASS",
    "frontend-dependency-scan": "Frontend Dependency Scan PASS",
    "sha-image-build": "Full SHA Image Tag",
    "integration-smoke": "통합 docker compose up --no-build",
    "artifact-checksum": "Artifact Checksum PASS",
    "jenkins-log-secret": "Jenkins 로그 Secret Scan PASS",
}


def load_json(path: Path, label: str) -> dict[str, object]:
    if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
        raise SystemExit(f"{label} is missing, empty, or symlinked: {path}")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit(f"{label} is not valid UTF-8 JSON: {path}") from error
    if not isinstance(document, dict):
        raise SystemExit(f"{label} must be a JSON object")
    return document


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"Stage 15 final evidence failed: {message}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ci-metadata", type=Path, required=True)
    parser.add_argument("--ci-results", type=Path, required=True)
    parser.add_argument("--host-evidence", type=Path, required=True)
    parser.add_argument("--rollback-drill", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    metadata = load_json(args.ci_metadata, "CI build metadata")
    host = load_json(args.host_evidence, "host evidence")
    drill = load_json(args.rollback_drill, "rollback drill summary")
    release_sha = str(metadata.get("releaseSha", ""))
    require(bool(FULL_SHA.fullmatch(release_sha)), "CI release SHA is not a full SHA")
    images = metadata.get("images", {})
    backend_image = images.get("backend", {}) if isinstance(images, dict) else {}
    frontend_image = images.get("frontend", {}) if isinstance(images, dict) else {}
    require(
        isinstance(images, dict)
        and isinstance(backend_image, dict)
        and isinstance(frontend_image, dict)
        and backend_image.get("name") == f"gilbom-backend:{release_sha}"
        and frontend_image.get("name") == f"gilbom-frontend:{release_sha}",
        "CI images are not tagged with the exact full SHA",
    )

    ci_evidence: dict[str, str] = {}
    for result_id, label in REQUIRED_CI_RESULTS.items():
        path = args.ci_results / f"{result_id}.status"
        require(
            path.is_file()
            and not path.is_symlink()
            and path.read_text(encoding="utf-8").strip() == "PASS",
            f"required CI result is not PASS: {result_id}",
        )
        ci_evidence[label] = "PASS"

    require(host.get("status") == "PASS", "host evidence is not PASS")
    jenkins = host.get("jenkins", {})
    production = host.get("production", {})
    require(
        isinstance(jenkins, dict)
        and jenkins.get("dockerSocketMounted") is False,
        "Jenkins Docker Socket absence is not proven",
    )
    require(
        jenkins.get("dindTls") is True
        and jenkins.get("dockerHost") == "tcp://docker-builder:2376"
        and jenkins.get("tlsVerification") is True,
        "Jenkins DinD TLS is not proven",
    )
    require(
        isinstance(production, dict)
        and production.get("deployDockerGroupMember") is False,
        "deploy account Docker-group exclusion is not proven",
    )
    require(
        production.get("composeNoBuildContract") == "PASS",
        "production --no-build contract is not proven",
    )
    require(
        bool(FULL_SHA.fullmatch(str(production.get("currentSha", ""))))
        and bool(FULL_SHA.fullmatch(str(production.get("previousSha", ""))))
        and production.get("releaseManifest") == "PASS",
        "current.sha, previous.sha, or Release Manifest evidence is invalid",
    )
    require(
        production.get("currentSha") == release_sha,
        "successful CI release SHA is not the current production SHA",
    )

    scenarios = drill.get("scenarios", {})
    require(
        drill.get("status") == "PASS"
        and isinstance(scenarios, dict),
        "rollback drill is not PASS",
    )
    scenario_a = scenarios.get("noMigrationAutomaticRollback", {})
    scenario_c = scenarios.get("incompatibleMigrationContainment", {})
    require(
        isinstance(scenario_a, dict)
        and isinstance(scenario_a.get("flyway"), dict)
        and scenario_a.get("status") == "AUTOMATIC_ROLLBACK_SUCCEEDED"
        and scenario_a.get("flyway", {}).get("changed") is False,
        "migration-free automatic Rollback PASS is not proven",
    )
    require(
        isinstance(scenario_c, dict)
        and isinstance(scenario_c.get("services"), dict)
        and scenario_c.get("status") == "MANUAL_RECOVERY_REQUIRED"
        and scenario_c.get("automaticRollbackAllowed") is False
        and scenario_c.get("services", {}).get("frontend") == "STOPPED",
        "incompatible-migration automatic Rollback block is not proven",
    )

    captured_at = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    verdict = {
        "schemaVersion": 1,
        "status": "PASS",
        "capturedAtUtc": captured_at,
        "ciReleaseSha": release_sha,
        "evidence": {
            "jenkinsDockerSocketAbsent": "PASS",
            "jenkinsDindTls": "PASS",
            **ci_evidence,
            "운영 docker compose up --no-build": "PASS",
            "deploy 계정 Docker 그룹 미가입": "PASS",
            "current.sha, previous.sha, Release Manifest 존재": "PASS",
            "Migration 없는 자동 Rollback": "PASS",
            "Migration 비호환 자동 Rollback 차단": "PASS",
        },
        "sources": {
            "ciMetadata": str(args.ci_metadata),
            "ciResults": str(args.ci_results),
            "hostEvidence": str(args.host_evidence),
            "rollbackDrill": str(args.rollback_drill),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(verdict, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Stage 15 final CI/CD evidence: PASS ({args.output})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
