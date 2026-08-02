#!/usr/bin/env python3
"""Generate deterministic release provenance from validated CI evidence."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
RELEASE = ROOT / "release"
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
MIGRATION = re.compile(r"^V(\d+)__(.+)\.sql$")
REQUIRED_RESULTS = (
    "backend-test",
    "security-acceptance",
    "frontend-test",
    "frontend-lint",
    "frontend-production-build",
    "sha-image-build",
    "backend-runtime-image",
    "frontend-runtime-image",
    "flyway-v1-v37",
    "postgis-rbac-session",
    "csrf-permission-audit",
    "integration-smoke",
    "graceful-shutdown",
    "report-llm-fallback",
    "resilience-recovery",
    "volume-persistence",
    "image-layer-log-secret",
    "sbom-cve",
)


def require_env(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise SystemExit(f"missing required environment variable: {name}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def bases(path: Path) -> list[str]:
    result = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^\s*FROM\s+([^\s]+)", line, re.IGNORECASE)
        if match:
            result.append(match.group(1))
    return result


def runtime_versions() -> dict[str, str]:
    java = (ROOT / ".java-version").read_text(encoding="utf-8").strip()
    wrapper = (
        ROOT / "backend/gradle/wrapper/gradle-wrapper.properties"
    ).read_text(encoding="utf-8")
    gradle_match = re.search(r"gradle-([0-9.]+)-bin\.zip", wrapper)
    if not gradle_match:
        raise SystemExit("could not resolve the Gradle Wrapper version")
    package = json.loads(
        (ROOT / "frontend/gilbom/package.json").read_text(encoding="utf-8")
    )
    return {
        "java": java,
        "gradle": gradle_match.group(1),
        "node": str(package["engines"]["node"]),
        "npm": str(package["engines"]["npm"]),
    }


def migration_evidence() -> dict[str, object]:
    entries = []
    directory = ROOT / "backend/src/main/resources/db/migration"
    for path in directory.iterdir():
        match = MIGRATION.fullmatch(path.name)
        if match:
            entries.append(
                {
                    "version": int(match.group(1)),
                    "file": path.name,
                    "description": match.group(2).replace("_", " "),
                    "sha256": sha256(path),
                }
            )
    entries.sort(key=lambda entry: entry["version"])
    versions = [entry["version"] for entry in entries]
    if versions != list(range(1, 38)):
        raise SystemExit("Flyway migrations must be contiguous from V1 through V37")
    return {"latestVersion": "V37", "migrations": entries}


def result_evidence() -> dict[str, str]:
    results = {}
    for result_id in REQUIRED_RESULTS:
        path = ROOT / f".artifacts/ci-results/{result_id}.status"
        status = path.read_text(encoding="utf-8").strip() if path.is_file() else ""
        if status != "PASS":
            raise SystemExit(f"required CI result is not PASS: {result_id}")
        results[result_id] = status
    return results


def artifact(path: str) -> dict[str, object]:
    absolute = RELEASE / path
    if not absolute.is_file() or absolute.stat().st_size == 0:
        raise SystemExit(f"required release artifact is missing or empty: {path}")
    return {
        "path": path,
        "bytes": absolute.stat().st_size,
        "sha256": sha256(absolute),
    }


def utc(value: str, name: str) -> str:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value):
        raise SystemExit(f"{name} must be an RFC3339 UTC second timestamp")
    return value


def main() -> int:
    release_sha = require_env("RELEASE_SHA")
    if not FULL_SHA.fullmatch(release_sha):
        raise SystemExit("RELEASE_SHA must be a full lowercase 40-character SHA")
    backend_image = require_env("BACKEND_IMAGE")
    frontend_image = require_env("FRONTEND_IMAGE")
    if backend_image != f"gilbom-backend:{release_sha}":
        raise SystemExit("Backend release image is not full-SHA tagged")
    if frontend_image != f"gilbom-frontend:{release_sha}":
        raise SystemExit("Frontend release image is not full-SHA tagged")
    backend_image_id = require_env("BACKEND_IMAGE_ID")
    frontend_image_id = require_env("FRONTEND_IMAGE_ID")
    image_id_pattern = re.compile(r"^sha256:[0-9a-f]{64}$")
    if not image_id_pattern.fullmatch(backend_image_id):
        raise SystemExit("BACKEND_IMAGE_ID is not a SHA-256 image ID")
    if not image_id_pattern.fullmatch(frontend_image_id):
        raise SystemExit("FRONTEND_IMAGE_ID is not a SHA-256 image ID")

    backend_dockerfile = ROOT / "backend/Dockerfile"
    frontend_dockerfile = ROOT / "frontend/Dockerfile"
    started = utc(require_env("BUILD_STARTED_AT_UTC"), "BUILD_STARTED_AT_UTC")
    finished = utc(require_env("BUILD_FINISHED_AT_UTC"), "BUILD_FINISHED_AT_UTC")

    document = {
        "schemaVersion": 1,
        "releaseSha": release_sha,
        "images": {
            "backend": {
                "name": backend_image,
                "id": backend_image_id,
            },
            "frontend": {
                "name": frontend_image,
                "id": frontend_image_id,
            },
        },
        "dockerfiles": {
            "backend": {
                "path": "backend/Dockerfile",
                "sha256": sha256(backend_dockerfile),
                "baseImages": bases(backend_dockerfile),
            },
            "frontend": {
                "path": "frontend/Dockerfile",
                "sha256": sha256(frontend_dockerfile),
                "baseImages": bases(frontend_dockerfile),
            },
        },
        "build": {
            "startedAtUtc": started,
            "finishedAtUtc": finished,
            "jenkins": {
                "jobName": require_env("JOB_NAME"),
                "buildNumber": require_env("BUILD_NUMBER"),
            },
        },
        "runtimes": runtime_versions(),
        "tests": result_evidence(),
        "flyway": migration_evidence(),
        "artifacts": {
            "sbom": {
                "backend": artifact("sbom/backend.sbom.json"),
                "frontend": artifact("sbom/frontend.sbom.json"),
            },
            "vulnerabilities": {
                "backend": artifact("vulnerabilities/backend.txt"),
                "frontend": artifact("vulnerabilities/frontend.txt"),
                "policy": artifact("vulnerabilities/policy-summary.json"),
            },
            "images": artifact("images.tar.zst"),
        },
    }

    destination = RELEASE / "build-metadata.json"
    temporary = RELEASE / ".build-metadata.json.tmp"
    temporary.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(destination)
    destination.chmod(0o600)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
