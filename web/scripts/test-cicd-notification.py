#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/generate-cicd-notification.py"
SHA = "0123456789abcdef0123456789abcdef01234567"


class CicdNotificationTest(unittest.TestCase):
    @staticmethod
    def load_generator_module():
        specification = importlib.util.spec_from_file_location(
            "generate_cicd_notification",
            SCRIPT,
        )
        if specification is None or specification.loader is None:
            raise RuntimeError("could not load CI/CD notification generator")
        module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(module)
        return module

    def run_generator(
        self,
        result: str,
        output: Path,
        pipeline: str = "ci",
    ) -> dict[str, object]:
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
                sys.executable,
                str(SCRIPT),
                "--pipeline",
                pipeline,
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

    def test_current_flyway_gate_does_not_create_obsolete_v35_failure(self) -> None:
        generator = self.load_generator_module()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            results = root / ".artifacts/ci-results"
            results.mkdir(parents=True)
            for result_id, _, _ in generator.PIPELINES["ci"]["ordered_gates"]:
                if result_id == "postgis-rbac-session":
                    break
                (results / f"{result_id}.status").write_text(
                    "PASS\n",
                    encoding="utf-8",
                )

            suspected, _, passed = generator.ci_diagnosis(root)
            self.assertEqual(suspected, "PostGIS/RBAC/Session")
            self.assertIn("Flyway V1-V44", passed)
            self.assertNotIn("Flyway V1-V35", passed)

    def test_automatic_cd_pipeline_labels_and_artifact_roots(self) -> None:
        generator = self.load_generator_module()
        self.assertEqual(
            generator.PIPELINES["production-auto-deploy"]["artifact_root"],
            ".artifacts/production-auto-deploy",
        )
        with tempfile.TemporaryDirectory() as temporary:
            summary = self.run_generator(
                "FAILURE",
                Path(temporary),
                pipeline="production-auto-deploy",
            )
            self.assertEqual(summary["pipelineLabel"], "운영 자동 배포")

    def test_production_auto_trigger_gate_follows_kill_switch(self) -> None:
        generator = self.load_generator_module()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            results = root / ".artifacts/ci-results"
            results.mkdir(parents=True)
            for result_id, _, _ in generator.PIPELINES["ci"]["ordered_gates"]:
                if result_id == "production-auto-deploy-trigger":
                    continue
                (results / f"{result_id}.status").write_text(
                    "PASS\n",
                    encoding="utf-8",
                )

            with mock.patch.dict(
                os.environ,
                {"GILBOM_PRODUCTION_AUTO_CD_ENABLED": "false"},
            ):
                suspected, _, _ = generator.ci_diagnosis(root)
                self.assertEqual(suspected, "후처리 또는 자동 Artifact 승격")

            with mock.patch.dict(
                os.environ,
                {"GILBOM_PRODUCTION_AUTO_CD_ENABLED": "true"},
            ):
                suspected, _, _ = generator.ci_diagnosis(root)
                self.assertEqual(
                    suspected,
                    "Production automatic deployment trigger",
                )


if __name__ == "__main__":
    unittest.main()
