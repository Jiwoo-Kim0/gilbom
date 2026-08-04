#!/usr/bin/env python3
"""Create a secret-free Jenkins result summary and Mattermost payload."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
from pathlib import Path


PIPELINES = {
    "ci": {
        "label": "CI",
        "artifact_root": ".artifacts",
        "ordered_gates": (
            ("checkout-full", "Full checkout", "git rev-parse --is-shallow-repository"),
            ("repository-policy", "Repository policy", "sh scripts/verify-repository-policy.sh"),
            (
                "git-history-secret",
                "Git history Secret scan",
                "sh scripts/verify-source-security.sh history",
            ),
            (
                "workspace-secret",
                "Workspace Secret scan",
                "sh scripts/verify-source-security.sh workspace",
            ),
            ("secret-boundary", "Secret boundary", "sh scripts/verify-secret-boundary.sh"),
            (
                "supported-runtime",
                "Supported runtime versions",
                "sh scripts/verify-supported-versions.sh",
            ),
            (
                "production-compose",
                "Production Compose contract",
                "sh scripts/verify-production-compose.sh",
            ),
            ("openapi-lint", "OpenAPI lint", "sh scripts/verify-source-security.sh openapi"),
            (
                "backend-dependency-scan",
                "Backend dependency scan",
                "sh scripts/verify-source-security.sh backend-dependencies",
            ),
            (
                "frontend-dependency-scan",
                "Frontend dependency scan",
                "sh scripts/verify-source-security.sh frontend-dependencies",
            ),
            (
                "image-scanner-readiness",
                "Image scanner readiness",
                "sh scripts/scan-image-security.sh prepare",
            ),
            (
                "artifact-transfer-readiness",
                "Artifact transfer readiness",
                "sh scripts/verify-ci-artifact-remote-readiness.sh",
            ),
            ("backend-test", "Backend full test", "sh scripts/run-backend-ci-task.sh test"),
            (
                "security-acceptance",
                "Backend security acceptance",
                "sh scripts/run-backend-ci-task.sh securityAcceptanceTest",
            ),
            ("frontend-test", "Frontend test", "sh scripts/run-frontend-ci-task.sh test"),
            ("frontend-lint", "Frontend lint", "sh scripts/run-frontend-ci-task.sh lint"),
            (
                "frontend-production-build",
                "Frontend production build",
                "sh scripts/run-frontend-ci-task.sh build",
            ),
            ("sha-image-build", "Full SHA image build", "docker compose -f compose.build.yaml build"),
            (
                "backend-runtime-image",
                "Backend runtime image",
                "sh scripts/verify-backend-runtime-image.sh",
            ),
            (
                "frontend-runtime-image",
                "Frontend runtime image",
                "sh scripts/verify-frontend-runtime-image.sh",
            ),
            (
                "integration-stack-start",
                "Integration stack start",
                "docker compose up -d --no-build --wait",
            ),
            (
                "auth-security-integration",
                "Authentication temporary DB integration",
                "python3 scripts/auth-security-integration-test.py",
            ),
            (
                "mqtt-broker-e2e",
                "Actual TLS MQTT Broker E2E",
                "sh scripts/run-mqtt-broker-e2e-ci.sh",
            ),
            ("flyway-v1-v41", "Flyway V1-V41", "docker compose exec -T db psql"),
            (
                "postgis-rbac-session",
                "PostGIS/RBAC/Session",
                "docker compose exec -T db psql",
            ),
            (
                "csrf-permission-audit",
                "CSRF/Permission/Append-only audit",
                "docker compose exec -T db psql",
            ),
            ("integration-smoke", "Integration smoke", "docker compose up -d --no-build --wait"),
            (
                "graceful-shutdown",
                "Graceful shutdown",
                "sh scripts/verify-backend-graceful-shutdown.sh",
            ),
            (
                "build-resource-evidence",
                "Build resource evidence",
                "free -h && df -h / && docker system df",
            ),
            (
                "report-llm-fallback",
                "Report fallback/resilience",
                "sh scripts/container-resilience-smoke.sh",
            ),
            (
                "resilience-recovery",
                "DB/MQTT/Backend recovery",
                "sh scripts/container-resilience-smoke.sh",
            ),
            (
                "volume-persistence",
                "DB/Image/PDF Volume persistence",
                "sh scripts/container-resilience-smoke.sh",
            ),
            (
                "image-layer-log-secret",
                "Image layer/log Secret scan",
                "sh scripts/verify-production-images.sh",
            ),
            ("sbom-cve", "Image SBOM/CVE policy", "sh scripts/scan-image-security.sh"),
            (
                "security-regression-baseline",
                "Security regression baseline evidence",
                "python3 scripts/generate-security-regression-baseline.py",
            ),
            (
                "release-security-artifacts",
                "Release SBOM/CVE Artifact preparation",
                "sh scripts/prepare-release-security-artifacts.sh",
            ),
            (
                "release-image-archive",
                "Release image archive",
                "sh scripts/create-release-image-archive.sh",
            ),
            (
                "cosign-blob-signature",
                "Cosign Blob signature",
                "sh scripts/sign-release-image-archive.sh",
            ),
            (
                "cosign-release-manifest",
                "Cosign complete release manifest",
                "sh scripts/sign-release-manifest.sh",
            ),
            ("artifact-checksum", "Release Artifact checksum", "sh scripts/verify-release-artifacts.sh"),
            (
                "artifact-upload",
                "Artifact upload to EC2 incoming",
                "sh scripts/upload-ci-release-artifacts.sh",
            ),
            (
                "artifact-promotion",
                "Automatic Artifact promotion",
                "sh scripts/promote-ci-release-artifacts-remote.sh",
            ),
            (
                "jenkins-log-secret",
                "Jenkins console Secret scan",
                "sh scripts/verify-jenkins-console-secret.sh",
            ),
            (
                "production-auto-deploy-trigger",
                "Production automatic deployment trigger",
                "Jenkins gilbom-production-auto-deploy console",
            ),
        ),
    },
    "deploy": {
        "label": "운영 배포",
        "artifact_root": ".artifacts/deploy",
        "files": (
            ("preflight.json", "운영 배포 preflight"),
            ("approval-record.json", "Jenkins 배포 승인 기록"),
            ("external-https-smoke.json", "외부 HTTPS smoke"),
            ("release-manifest.json", "Release Manifest 회수"),
        ),
    },
    "production-auto-deploy": {
        "label": "운영 자동 배포",
        "artifact_root": ".artifacts/production-auto-deploy",
        "files": (
            ("preflight.json", "운영 자동 배포 preflight"),
            ("approval-record.json", "운영 자동 승인 기록"),
            ("external-https-smoke.json", "운영 외부 HTTPS smoke"),
            ("release-manifest.json", "운영 Release Manifest 회수"),
        ),
    },
    "rollback": {
        "label": "조건부 Rollback",
        "artifact_root": ".artifacts/rollback",
        "files": (
            ("preflight.json", "Rollback preflight"),
            ("approval-record.json", "호환성 수동 승인 기록"),
            ("external-https-smoke.json", "Rollback 후 HTTPS smoke"),
            ("rollback-manifest.json", "Rollback Manifest 회수"),
        ),
    },
    "rollback-drill": {
        "label": "격리 Rollback 훈련",
        "artifact_root": ".artifacts/rollback-drill",
        "files": (("summary.json", "A/B/C 훈련 summary"),),
    },
}

FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
BUILD_RESULTS = {"SUCCESS", "FAILURE", "UNSTABLE", "ABORTED", "NOT_BUILT"}


def env(name: str, fallback: str = "") -> str:
    return os.environ.get(name, fallback).strip()


def safe_url(value: str) -> str:
    if not value:
        return ""
    if not re.fullmatch(r"https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+", value):
        return ""
    return value.rstrip("/") + "/"


def resolve_sha() -> str:
    for name in (
        "RELEASE_SHA",
        "FULL_COMMIT_SHA",
        "FAILED_RELEASE_SHA",
        "GIT_COMMIT",
    ):
        value = env(name)
        if FULL_SHA.fullmatch(value):
            return value
    return "unavailable"


def result_icon(result: str) -> str:
    return {
        "SUCCESS": "✅",
        "FAILURE": "❌",
        "UNSTABLE": "⚠️",
        "ABORTED": "⏹️",
        "NOT_BUILT": "⏭️",
    }[result]


def ci_diagnosis(root: Path) -> tuple[str, str, list[str]]:
    passed: list[str] = []
    for result_id, label, reproduce in PIPELINES["ci"]["ordered_gates"]:
        if (
            result_id == "production-auto-deploy-trigger"
            and env("GILBOM_PRODUCTION_AUTO_CD_ENABLED") != "true"
        ):
            continue
        status = root / ".artifacts/ci-results" / f"{result_id}.status"
        if status.is_file() and status.read_text(encoding="utf-8").strip() == "PASS":
            passed.append(label)
            continue
        return label, reproduce, passed
    return "후처리 또는 자동 Artifact 승격", "Jenkins Console에서 마지막 오류 확인", passed


def file_diagnosis(root: Path, pipeline: str) -> tuple[str, str, list[str]]:
    definition = PIPELINES[pipeline]
    artifact_root = root / str(definition["artifact_root"])
    passed: list[str] = []
    for file_name, label in definition["files"]:
        if (artifact_root / file_name).is_file():
            passed.append(label)
            continue
        return label, "Jenkins Console과 보관된 preflight/manifest 확인", passed
    return "후처리 또는 원격 실행 결과", "Jenkins Console에서 마지막 오류 확인", passed


def write_documents(root: Path, pipeline: str, result: str, output: Path) -> None:
    definition = PIPELINES[pipeline]
    build_url = safe_url(env("BUILD_URL"))
    job_name = env("JOB_NAME", "unknown-job")
    build_number = env("BUILD_NUMBER", "?")
    release_sha = resolve_sha()
    if pipeline == "ci":
        suspected_gate, reproduce, passed = ci_diagnosis(root)
    else:
        suspected_gate, reproduce, passed = file_diagnosis(root, pipeline)

    console_url = f"{build_url}console" if build_url else ""
    summary_url = (
        f"{build_url}artifact/.artifacts/cicd-notification/summary.md"
        if build_url
        else ""
    )
    source_security_url = (
        f"{build_url}artifact/.artifacts/source-security/" if build_url else ""
    )
    finished_at = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    diagnosis = (
        "모든 필수 게이트 통과"
        if result == "SUCCESS"
        else f"첫 미완료 게이트: {suspected_gate}"
    )

    summary = {
        "schemaVersion": 1,
        "pipeline": pipeline,
        "pipelineLabel": definition["label"],
        "result": result,
        "jobName": job_name,
        "buildNumber": build_number,
        "releaseSha": release_sha,
        "finishedAtUtc": finished_at,
        "diagnosis": diagnosis,
        "suspectedGate": suspected_gate if result != "SUCCESS" else None,
        "lastPassedGate": passed[-1] if passed else None,
        "reproduceCommand": reproduce if result != "SUCCESS" else None,
        "links": {
            "console": console_url or None,
            "summary": summary_url or None,
            "sourceSecurity": source_security_url or None,
        },
        "security": {
            "containsConsoleExcerpt": False,
            "containsCredentialValue": False,
        },
    }

    lines = [
        f"# {definition['label']} #{build_number} — {result}",
        "",
        f"- Job: `{job_name}`",
        f"- Commit SHA: `{release_sha}`",
        f"- 판정: {diagnosis}",
    ]
    if result != "SUCCESS":
        lines.extend(
            [
                f"- 마지막 PASS: {passed[-1] if passed else '없음'}",
                f"- 로컬 재현: `{reproduce}`",
            ]
        )
    if console_url:
        lines.append(f"- [Jenkins 전체 콘솔]({console_url})")
    if source_security_url and pipeline == "ci":
        lines.append(f"- [보안 검사 산출물]({source_security_url})")
    lines.extend(
        [
            "",
            "> 콘솔 원문은 Mattermost에 복제하지 않습니다. Jenkins 인증 후 읽기 전용으로 확인하세요.",
            "",
        ]
    )

    mention = env("MATTERMOST_FAILURE_MENTION")
    if mention and not re.fullmatch(r"(?:@(?:channel|here|all)|<!(?:channel|here|all)>)", mention):
        raise SystemExit("MATTERMOST_FAILURE_MENTION is not an allowed channel mention")
    headline = (
        f"{mention} " if result != "SUCCESS" and mention else ""
    ) + f"{result_icon(result)} **{definition['label']} #{build_number}: {result}**"
    message = [
        headline,
        f"- Commit: `{release_sha}`",
        f"- {diagnosis}",
    ]
    if result != "SUCCESS":
        message.append(f"- 재현: `{reproduce}`")
    if console_url:
        message.append(f"- [전체 실패 로그]({console_url})")
    if summary_url:
        message.append(f"- [실패 요약/증거]({summary_url})")
    if source_security_url and pipeline == "ci":
        message.append(f"- [보안 검사 결과]({source_security_url})")

    payload = {
        "username": "gilbom-cicd",
        "icon_emoji": ":robot_face:",
        "text": "\n".join(message),
        "props": {
            "card": "\n".join(lines),
        },
    }

    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (output / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    (output / "mattermost-payload.json").write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pipeline", choices=sorted(PIPELINES), required=True)
    parser.add_argument("--result", choices=sorted(BUILD_RESULTS), required=True)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(".artifacts/cicd-notification"),
    )
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    write_documents(root, args.pipeline, args.result, args.output)
    print(f"CI/CD notification summary: PASS ({args.pipeline}, {args.result})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
