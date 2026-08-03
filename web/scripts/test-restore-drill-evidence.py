#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
GENERATOR = ROOT / "scripts/generate-restore-drill-evidence.py"
PASS_LOG = """
  PASS  SHA256SUMS 대조
  PASS  DB·image·PDF source hash manifest
  PASS  pg_restore
  PASS  DB table content SHA-256 — 31개 table 완전 일치
  PASS  DB sequence state SHA-256 — 22개 sequence 완전 일치
  PASS  robot_images restored file SHA-256 — 2개 file 완전 일치
  PASS  robot image DB reference ↔ file SHA-256 — 2건 모두 일치
  PASS  report_pdf_data restored file SHA-256 — 1개 file 완전 일치
  PASS  report PDF DB reference ↔ file SHA-256 — 1건 모두 일치
Restore drill: PASS
"""


class RestoreDrillEvidenceTest(unittest.TestCase):
    def invoke(
        self,
        bundle: Path,
        log: Path,
        output: Path,
        exit_code: int,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3",
                str(GENERATOR),
                "--bundle",
                str(bundle),
                "--log",
                str(log),
                "--exit-code",
                str(exit_code),
                "--output",
                str(output),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def fixture(self, root: Path, log_text: str) -> tuple[Path, Path, Path]:
        bundle = root / "20260803T000000Z-gilbom"
        bundle.mkdir()
        (bundle / "SHA256SUMS").write_text(
            "fixture SHA256SUMS\n", encoding="utf-8"
        )
        (bundle / "bundle.manifest.json").write_text(
            '{"backup":"volumes"}\n', encoding="utf-8"
        )
        log = root / "restore.log"
        log.write_text(log_text, encoding="utf-8")
        return bundle, log, root / "summary.json"

    def test_complete_pass_log_creates_pass_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            bundle, log, output = self.fixture(
                Path(temporary),
                PASS_LOG,
            )
            result = self.invoke(bundle, log, output, 0)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            evidence = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(evidence["status"], "PASS")
            self.assertTrue(evidence["checks"]["databaseContentHash"])
            self.assertFalse(
                evidence["isolation"]["productionDatabaseRestore"]
            )

    def test_missing_pdf_hash_marker_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            bundle, log, output = self.fixture(
                Path(temporary),
                PASS_LOG.replace(
                    "  PASS  report PDF DB reference ↔ file SHA-256"
                    " — 1건 모두 일치\n",
                    "",
                ),
            )
            result = self.invoke(bundle, log, output, 0)
            self.assertNotEqual(result.returncode, 0)
            evidence = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(evidence["status"], "FAIL")
            self.assertFalse(evidence["checks"]["reportPdfReferenceHash"])

    def test_nonzero_restore_exit_fails_even_with_all_markers(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            bundle, log, output = self.fixture(
                Path(temporary),
                PASS_LOG,
            )
            result = self.invoke(bundle, log, output, 1)
            self.assertNotEqual(result.returncode, 0)
            evidence = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(evidence["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
