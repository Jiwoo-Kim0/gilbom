#!/usr/bin/env python3
"""Evaluate Trivy JSON reports against the repository vulnerability policy."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
from pathlib import Path


ENTRY_FIELDS = {"cve", "target", "owner", "reason", "expiresOn"}
VULNERABILITY_ID = re.compile(
    r"^(?:CVE-\d{4}-\d{4,}|GHSA-[23456789cfghjmpqrvwx]{4}-"
    r"[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4})$",
    re.IGNORECASE,
)
IMAGE_TARGETS = {"image:backend", "image:frontend", "image:dependency"}
PLACEHOLDERS = {"todo", "tbd", "unknown", "n/a", "na", "-"}


class PolicyError(ValueError):
    pass


def _strip_comment(line: str) -> str:
    quote = None
    escaped = False
    for index, character in enumerate(line):
        if escaped:
            escaped = False
            continue
        if character == "\\" and quote == '"':
            escaped = True
            continue
        if character in {"'", '"'}:
            if quote is None:
                quote = character
            elif quote == character:
                quote = None
            continue
        if character == "#" and quote is None and (
            index == 0 or line[index - 1].isspace()
        ):
            return line[:index].rstrip()
    return line.rstrip()


def _value(raw: str, line_number: int) -> str:
    value = raw.strip()
    if not value:
        raise PolicyError(f"line {line_number}: empty values are not allowed")
    if value[0] in {"'", '"'}:
        if len(value) < 2 or value[-1] != value[0]:
            raise PolicyError(f"line {line_number}: unterminated quoted value")
        value = value[1:-1]
    return value.strip()


def parse_exceptions(path: Path) -> list[dict[str, str]]:
    """Parse the deliberately small, dependency-free exception YAML subset."""

    root_seen: set[str] = set()
    entries: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    exceptions_mode = None

    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = _strip_comment(raw_line)
        if not line.strip():
            continue

        if line.startswith("\t"):
            raise PolicyError(f"line {line_number}: tabs are not allowed")

        if not line.startswith(" "):
            if ":" not in line:
                raise PolicyError(f"line {line_number}: expected a root key")
            key, raw_value = line.split(":", 1)
            key = key.strip()
            if key not in {"schemaVersion", "exceptions"}:
                raise PolicyError(f"line {line_number}: unknown root key {key}")
            if key in root_seen:
                raise PolicyError(f"line {line_number}: duplicate root key {key}")
            root_seen.add(key)

            if key == "schemaVersion":
                if _value(raw_value, line_number) != "1":
                    raise PolicyError("schemaVersion must be 1")
            else:
                value = raw_value.strip()
                if value == "[]":
                    exceptions_mode = "empty"
                elif value == "":
                    exceptions_mode = "list"
                else:
                    raise PolicyError(
                        f"line {line_number}: exceptions must be [] or a list"
                    )
            continue

        if exceptions_mode != "list":
            raise PolicyError(
                f"line {line_number}: list entry outside exceptions list"
            )

        stripped = line.strip()
        if stripped.startswith("- "):
            if current is not None:
                entries.append(current)
            current = {}
            field_line = stripped[2:]
        elif stripped == "-":
            if current is not None:
                entries.append(current)
            current = {}
            continue
        else:
            if current is None:
                raise PolicyError(f"line {line_number}: field before list entry")
            field_line = stripped

        if ":" not in field_line:
            raise PolicyError(f"line {line_number}: expected key: value")
        key, raw_value = field_line.split(":", 1)
        key = key.strip()
        if key not in ENTRY_FIELDS:
            raise PolicyError(f"line {line_number}: unknown exception field {key}")
        if key in current:
            raise PolicyError(f"line {line_number}: duplicate exception field {key}")
        current[key] = _value(raw_value, line_number)

    if current is not None:
        entries.append(current)
    if root_seen != {"schemaVersion", "exceptions"}:
        missing = ", ".join(sorted({"schemaVersion", "exceptions"} - root_seen))
        raise PolicyError(f"missing root key(s): {missing}")
    if exceptions_mode == "empty" and entries:
        raise PolicyError("exceptions: [] cannot contain entries")

    seen: set[tuple[str, str]] = set()
    for index, entry in enumerate(entries, start=1):
        missing = ENTRY_FIELDS - entry.keys()
        if missing:
            raise PolicyError(
                f"exception {index}: missing field(s): {', '.join(sorted(missing))}"
            )

        entry["cve"] = entry["cve"].upper()
        if not VULNERABILITY_ID.fullmatch(entry["cve"]):
            raise PolicyError(f"exception {index}: invalid CVE/GHSA identifier")

        target = entry["target"]
        if target not in IMAGE_TARGETS and not (
            target.startswith("package:") and len(target) > len("package:")
        ):
            raise PolicyError(f"exception {index}: invalid target {target}")

        owner = entry["owner"].strip()
        if owner.lower() in PLACEHOLDERS or len(owner) < 2:
            raise PolicyError(f"exception {index}: owner is a placeholder")

        reason = entry["reason"].strip()
        if reason.lower() in PLACEHOLDERS or len(reason) < 20:
            raise PolicyError(
                f"exception {index}: reason must contain at least 20 characters"
            )

        try:
            dt.date.fromisoformat(entry["expiresOn"])
        except ValueError as error:
            raise PolicyError(
                f"exception {index}: expiresOn must be a valid YYYY-MM-DD date"
            ) from error

        identity = (entry["cve"], target)
        if identity in seen:
            raise PolicyError(
                f"exception {index}: duplicate cve and target combination"
            )
        seen.add(identity)

    return entries


def policy_date() -> dt.date:
    raw = os.environ.get("SECURITY_POLICY_DATE")
    if raw:
        try:
            return dt.date.fromisoformat(raw)
        except ValueError as error:
            raise PolicyError(
                "SECURITY_POLICY_DATE must be a valid YYYY-MM-DD date"
            ) from error
    return dt.datetime.now(dt.timezone.utc).date()


def read_findings(report_specifications: list[str]) -> list[dict[str, str]]:
    findings = []
    for specification in report_specifications:
        if "=" not in specification:
            raise PolicyError("--report must use role=/absolute/or/relative/path.json")
        role, raw_path = specification.split("=", 1)
        if role not in {"backend", "frontend", "dependency"}:
            raise PolicyError(f"unsupported report role: {role}")
        path = Path(raw_path)
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise PolicyError(f"could not read Trivy JSON report: {path}") from error

        results = document.get("Results")
        if results is None:
            results = []
        if not isinstance(results, list):
            raise PolicyError(f"invalid Trivy Results array: {path}")

        for result in results:
            vulnerabilities = result.get("Vulnerabilities") or []
            if not isinstance(vulnerabilities, list):
                raise PolicyError(f"invalid Trivy Vulnerabilities array: {path}")
            for vulnerability in vulnerabilities:
                severity = str(vulnerability.get("Severity", "")).upper()
                if severity not in {"HIGH", "CRITICAL"}:
                    continue
                identifier = str(vulnerability.get("VulnerabilityID", "")).upper()
                package = str(vulnerability.get("PkgName", ""))
                if not identifier or not package:
                    raise PolicyError(
                        f"High/Critical finding lacks identifier or package: {path}"
                    )
                findings.append(
                    {
                        "role": role,
                        "cve": identifier,
                        "package": package,
                        "severity": severity,
                        "installedVersion": str(
                            vulnerability.get("InstalledVersion", "")
                        ),
                        "fixedVersion": str(vulnerability.get("FixedVersion", "")),
                    }
                )
    return findings


def evaluate(
    entries: list[dict[str, str]],
    findings: list[dict[str, str]],
    today: dt.date,
) -> tuple[list[dict[str, str]], int]:
    violations: list[dict[str, str]] = []
    expired = []
    for entry in entries:
        expiry = dt.date.fromisoformat(entry["expiresOn"])
        if expiry < today:
            expired.append(entry)
            violations.append(
                {
                    "type": "expired-exception",
                    "cve": entry["cve"],
                    "target": entry["target"],
                    "expiresOn": entry["expiresOn"],
                }
            )

    valid_entries = [entry for entry in entries if entry not in expired]
    excepted_high = 0
    for finding in findings:
        if finding["severity"] == "CRITICAL":
            violations.append(
                {
                    "type": "critical",
                    "cve": finding["cve"],
                    "target": f"image:{finding['role']}",
                    "package": finding["package"],
                }
            )
            continue

        matched = any(
            entry["cve"] == finding["cve"]
            and entry["target"]
            in {
                f"image:{finding['role']}",
                f"package:{finding['package']}",
            }
            for entry in valid_entries
        )
        if matched:
            excepted_high += 1
        else:
            violations.append(
                {
                    "type": "unexcepted-high",
                    "cve": finding["cve"],
                    "target": f"image:{finding['role']}",
                    "package": finding["package"],
                }
            )

    summary = {
        "policyDate": today.isoformat(),
        "exceptionCount": len(entries),
        "expiredExceptionCount": len(expired),
        "criticalCount": sum(
            finding["severity"] == "CRITICAL" for finding in findings
        ),
        "highCount": sum(finding["severity"] == "HIGH" for finding in findings),
        "exceptedHighCount": excepted_high,
        "violations": violations,
        "status": "PASS" if not violations else "FAIL",
    }
    return summary, 0 if not violations else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--exceptions", required=True, type=Path)
    parser.add_argument("--report", action="append", default=[], required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    try:
        entries = parse_exceptions(args.exceptions)
        findings = read_findings(args.report)
        summary, exit_code = evaluate(entries, findings, policy_date())
    except PolicyError as error:
        summary = {
            "status": "FAIL",
            "violations": [{"type": "policy-error", "message": str(error)}],
        }
        exit_code = 2

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    os.chmod(args.output, 0o600)

    if exit_code:
        for violation in summary["violations"]:
            identifier = violation.get("cve", "")
            target = violation.get("target", "")
            message = violation.get("message", "")
            print(
                f"Vulnerability policy violation: {violation['type']} "
                f"{identifier} {target} {message}".rstrip(),
                file=sys.stderr,
            )
        return exit_code

    print(
        "Vulnerability policy: PASS "
        f"(critical={summary['criticalCount']}, high={summary['highCount']}, "
        f"excepted-high={summary['exceptedHighCount']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
