#!/usr/bin/env python3
"""Create the machine-readable Day 10 isolated restore-drill verdict."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path


REQUIRED_MARKERS = {
    "bundleChecksum": "PASS  SHA256SUMS 대조",
    "sourceHashManifests": "PASS  DB·image·PDF source hash manifest",
    "databaseRestore": "PASS  pg_restore",
    "databaseContentHash": "PASS  DB table content SHA-256",
    "databaseSequenceHash": "PASS  DB sequence state SHA-256",
    "robotImageVolumeHash": "PASS  robot_images restored file SHA-256",
    "robotImageReferenceHash": (
        "PASS  robot image DB reference ↔ file SHA-256"
    ),
    "reportPdfVolumeHash": "PASS  report_pdf_data restored file SHA-256",
    "reportPdfReferenceHash": (
        "PASS  report PDF DB reference ↔ file SHA-256"
    ),
    "finalVerdict": "Restore drill: PASS",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--exit-code", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    bundle = args.bundle.resolve()
    log = args.log.resolve()
    if not bundle.is_dir() or bundle.is_symlink():
        raise SystemExit(f"restore bundle is missing or symlinked: {bundle}")
    if not log.is_file() or log.is_symlink():
        raise SystemExit(f"restore log is missing or symlinked: {log}")

    log_text = log.read_text(encoding="utf-8", errors="replace")
    checks = {
        check_id: marker in log_text
        for check_id, marker in REQUIRED_MARKERS.items()
    }
    passed = args.exit_code == 0 and all(checks.values())

    sums = bundle / "SHA256SUMS"
    manifest = bundle / "bundle.manifest.json"
    evidence = {
        "schemaVersion": 1,
        "status": "PASS" if passed else "FAIL",
        "capturedAtUtc": dt.datetime.now(dt.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
        "restoreExitCode": args.exit_code,
        "bundle": {
            "name": bundle.name,
            "sha256SumsSha256": sha256(sums) if sums.is_file() else None,
            "manifestSha256": sha256(manifest) if manifest.is_file() else None,
        },
        "isolation": {
            "temporaryInternalNetwork": True,
            "temporaryDatabaseContainer": True,
            "temporaryNamedVolumes": True,
            "productionDatabaseRestore": False,
            "productionHostReboot": False,
        },
        "checks": checks,
        "log": log.name,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if passed:
        print(f"Isolated backup restore evidence: PASS ({args.output})")
        return 0
    missing = ", ".join(
        check_id for check_id, result in checks.items() if not result
    )
    print(
        "Isolated backup restore evidence: FAIL "
        f"(exit={args.exit_code}, missing={missing or 'none'})"
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
