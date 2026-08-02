#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/generate-cicd-notification.py"
SHA = "0123456789abcdef0123456789abcdef01234567"


class CicdNotificationTest(unittest.TestCase):
    def run_generator(self, result: str, output: Path) -> dict[str, object]:
        environment = os.environ.copy()
        environment.update(
            {
                "BUILD_URL": "https://jenkins.example.invalid/job/gilbom-ci/7/",
                "JOB_NAME": "gilbom-ci",
                "BUILD_NUMBER": "7",
                "RELEASE_SHA": SHA,
            }
        )
        subprocess.run(
            [
                "python3",
                str(SCRIPT),
                "--pipeline",
                "ci",
                "--result",
                result,
                "--output",
                str(output),
            ],
            cwd=ROOT,
            env=environment,
            check=True,
            stdout=subprocess.DEVNULL,
        )
        return json.loads((output / "summary.json").read_text(encoding="utf-8"))

    def test_failure_has_safe_console_link_and_no_excerpt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            summary = self.run_generator("FAILURE", output)
            self.assertEqual(summary["result"], "FAILURE")
            self.assertEqual(
                summary["links"]["console"],
                "https://jenkins.example.invalid/job/gilbom-ci/7/console",
            )
            self.assertFalse(summary["security"]["containsConsoleExcerpt"])
            payload = json.loads(
                (output / "mattermost-payload.json").read_text(encoding="utf-8")
            )
            self.assertIn("전체 실패 로그", payload["text"])
            self.assertNotIn("MATTERMOST_WEBHOOK_URL", payload["text"])

    def test_success_is_rendered_without_failure_claim(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            summary = self.run_generator("SUCCESS", Path(temporary))
            self.assertEqual(summary["diagnosis"], "모든 필수 게이트 통과")
            self.assertIsNone(summary["suspectedGate"])


if __name__ == "__main__":
    unittest.main()
