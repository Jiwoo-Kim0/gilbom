#!/usr/bin/env python3

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("security_policy.py")


def report(vulnerabilities):
    return {
        "SchemaVersion": 2,
        "Results": [
            {
                "Target": "fixture",
                "Vulnerabilities": vulnerabilities,
            }
        ],
    }


def finding(identifier, severity, package="example:package"):
    return {
        "VulnerabilityID": identifier,
        "PkgName": package,
        "InstalledVersion": "1.0.0",
        "FixedVersion": "1.0.1",
        "Severity": severity,
    }


class SecurityPolicyTest(unittest.TestCase):
    def run_policy(self, exception_body, vulnerabilities, role="backend"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            exceptions = root / "exceptions.yaml"
            trivy = root / "trivy.json"
            output = root / "summary.json"
            exceptions.write_text(exception_body, encoding="utf-8")
            trivy.write_text(
                json.dumps(report(vulnerabilities)), encoding="utf-8"
            )
            environment = os.environ.copy()
            environment["SECURITY_POLICY_DATE"] = "2026-08-02"
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--exceptions",
                    str(exceptions),
                    "--report",
                    f"{role}={trivy}",
                    "--output",
                    str(output),
                ],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            return result, json.loads(output.read_text(encoding="utf-8"))

    def test_clean_report_passes(self):
        result, summary = self.run_policy(
            "schemaVersion: 1\nexceptions: []\n", []
        )
        self.assertEqual(0, result.returncode)
        self.assertEqual("PASS", summary["status"])

    def test_critical_cannot_be_excepted(self):
        result, summary = self.run_policy(
            """schemaVersion: 1
exceptions:
  - cve: CVE-2026-12345
    target: image:backend
    owner: security-team
    reason: Temporary operational approval with remediation.
    expiresOn: 2026-08-31
""",
            [finding("CVE-2026-12345", "CRITICAL")],
        )
        self.assertEqual(1, result.returncode)
        self.assertEqual("critical", summary["violations"][0]["type"])

    def test_high_without_exception_is_blocked(self):
        result, summary = self.run_policy(
            "schemaVersion: 1\nexceptions: []\n",
            [finding("CVE-2026-12345", "HIGH")],
        )
        self.assertEqual(1, result.returncode)
        self.assertEqual("unexcepted-high", summary["violations"][0]["type"])

    def test_valid_package_exception_passes(self):
        result, summary = self.run_policy(
            """schemaVersion: 1
exceptions:
  - cve: CVE-2026-12345
    target: package:example:package
    owner: backend-team
    reason: Upstream fix is scheduled before this approval expires.
    expiresOn: 2026-08-31
""",
            [finding("CVE-2026-12345", "HIGH")],
        )
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, summary["exceptedHighCount"])

    def test_expired_exception_blocks_even_without_finding(self):
        result, summary = self.run_policy(
            """schemaVersion: 1
exceptions:
  - cve: CVE-2026-12345
    target: image:frontend
    owner: frontend-team
    reason: Historical approval must be removed after expiry.
    expiresOn: 2026-08-01
""",
            [],
        )
        self.assertEqual(1, result.returncode)
        self.assertEqual("expired-exception", summary["violations"][0]["type"])

    def test_malformed_exception_fails_closed(self):
        result, summary = self.run_policy(
            """schemaVersion: 1
exceptions:
  - cve: CVE-2026-12345
    target: image:backend
    owner: TODO
    reason: short
    expiresOn: tomorrow
""",
            [],
        )
        self.assertEqual(2, result.returncode)
        self.assertEqual("policy-error", summary["violations"][0]["type"])


if __name__ == "__main__":
    unittest.main()
