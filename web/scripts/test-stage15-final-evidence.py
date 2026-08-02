#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/generate-stage15-final-evidence.py"
SHA = "0123456789abcdef0123456789abcdef01234567"
PREVIOUS = "89abcdef0123456789abcdef0123456789abcdef"
RESULTS = (
    "checkout-full",
    "git-history-secret",
    "workspace-secret",
    "openapi-lint",
    "backend-dependency-scan",
    "frontend-dependency-scan",
    "sha-image-build",
    "integration-smoke",
    "artifact-checksum",
    "jenkins-log-secret",
)


def write_json(path: Path, document: dict[str, object]) -> None:
    path.write_text(
        json.dumps(document, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


class Stage15FinalEvidenceTest(unittest.TestCase):
    def fixture(self, root: Path) -> tuple[Path, Path, Path, Path, Path]:
        metadata = root / "build-metadata.json"
        results = root / "ci-results"
        host = root / "host.json"
        drill = root / "drill.json"
        output = root / "final.json"
        results.mkdir()
        for result_id in RESULTS:
            (results / f"{result_id}.status").write_text("PASS\n", encoding="utf-8")
        write_json(
            metadata,
            {
                "releaseSha": SHA,
                "images": {
                    "backend": {"name": f"gilbom-backend:{SHA}"},
                    "frontend": {"name": f"gilbom-frontend:{SHA}"},
                },
            },
        )
        write_json(
            host,
            {
                "status": "PASS",
                "jenkins": {
                    "dockerSocketMounted": False,
                    "dindTls": True,
                    "dockerHost": "tcp://docker-builder:2376",
                    "tlsVerification": True,
                },
                "production": {
                    "deployDockerGroupMember": False,
                    "composeNoBuildContract": "PASS",
                    "currentSha": SHA,
                    "previousSha": PREVIOUS,
                    "releaseManifest": "PASS",
                },
            },
        )
        write_json(
            drill,
            {
                "status": "PASS",
                "scenarios": {
                    "noMigrationAutomaticRollback": {
                        "status": "AUTOMATIC_ROLLBACK_SUCCEEDED",
                        "flyway": {"changed": False},
                    },
                    "incompatibleMigrationContainment": {
                        "status": "MANUAL_RECOVERY_REQUIRED",
                        "automaticRollbackAllowed": False,
                        "services": {"frontend": "STOPPED"},
                    },
                },
            },
        )
        return metadata, results, host, drill, output

    def invoke(
        self,
        metadata: Path,
        results: Path,
        host: Path,
        drill: Path,
        output: Path,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3",
                str(SCRIPT),
                "--ci-metadata",
                str(metadata),
                "--ci-results",
                str(results),
                "--host-evidence",
                str(host),
                "--rollback-drill",
                str(drill),
                "--output",
                str(output),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def test_complete_evidence_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            files = self.fixture(Path(temporary))
            result = self.invoke(*files)
            self.assertEqual(result.returncode, 0, result.stderr)
            verdict = json.loads(files[-1].read_text(encoding="utf-8"))
            self.assertEqual(verdict["status"], "PASS")
            self.assertEqual(
                verdict["evidence"]["Migration 비호환 자동 Rollback 차단"],
                "PASS",
            )

    def test_missing_log_scan_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            files = self.fixture(Path(temporary))
            (files[1] / "jenkins-log-secret.status").unlink()
            result = self.invoke(*files)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("jenkins-log-secret", result.stderr)


if __name__ == "__main__":
    unittest.main()
