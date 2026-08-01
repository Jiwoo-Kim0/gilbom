#!/usr/bin/env python3

"""HTTP/SSE client for the disposable production report smoke stack."""

from __future__ import annotations

import argparse
import base64
from http.cookies import SimpleCookie
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import queue
import re
import struct
import threading
import time
from typing import Any
from urllib.parse import urlencode, urlsplit
import uuid


AUTHOR_EMAIL = "report-smoke-author@example.test"
REVIEWER_EMAIL = "report-smoke-reviewer@example.test"
REVIEWER_ID = "00000000-0000-0000-0000-000000000002"
FIXTURE_DEFECT_ID = "70000000-0000-0000-0000-000000000001"
FIXTURE_MISSION_ID = "40000000-0000-0000-0000-000000000001"
PERIOD_FROM = "2026-07-01T00:00:00Z"
PERIOD_TO = "2026-07-08T00:00:00Z"


class SmokeFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SmokeFailure(message)


def read_secret(path: str) -> str:
    value = Path(path).read_text(encoding="utf-8").strip()
    require(bool(value), "authentication password file is empty")
    return value


def totp(secret: str, at: int | None = None) -> str:
    normalized = "".join(secret.split()).upper()
    padding = "=" * ((8 - len(normalized) % 8) % 8)
    key = base64.b32decode(normalized + padding, casefold=True)
    counter = int(time.time() if at is None else at) // 30
    digest = hmac.new(
        key,
        struct.pack(">Q", counter),
        hashlib.sha1,
    ).digest()
    offset = digest[-1] & 0x0F
    binary = struct.unpack(
        ">I", digest[offset : offset + 4]
    )[0] & 0x7FFFFFFF
    return f"{binary % 1_000_000:06d}"


class ApiSession:
    def __init__(
        self,
        base_url: str,
        *,
        cookies: dict[str, str] | None = None,
        csrf_token: str | None = None,
    ) -> None:
        parsed = urlsplit(base_url)
        require(
            parsed.scheme == "http" and bool(parsed.hostname),
            "smoke base URL must be an HTTP URL",
        )
        self.host = parsed.hostname
        self.port = parsed.port or 80
        self.prefix = parsed.path.rstrip("/")
        self.cookies = dict(cookies or {})
        self.csrf_token = csrf_token

    def _connection(self, timeout: float = 30.0) -> http.client.HTTPConnection:
        return http.client.HTTPConnection(
            self.host,
            self.port,
            timeout=timeout,
        )

    def cookie_header(self) -> str:
        return "; ".join(
            f"{name}={value}"
            for name, value in sorted(self.cookies.items())
        )

    def _capture_cookies(
        self,
        headers: list[tuple[str, str]],
    ) -> None:
        for name, value in headers:
            if name.lower() != "set-cookie":
                continue
            parsed = SimpleCookie()
            parsed.load(value)
            for cookie_name, morsel in parsed.items():
                if morsel["max-age"] == "0" or not morsel.value:
                    self.cookies.pop(cookie_name, None)
                else:
                    self.cookies[cookie_name] = morsel.value

    def request(
        self,
        method: str,
        path: str,
        *,
        payload: dict[str, Any] | None = None,
        expected: tuple[int, ...] = (200,),
        csrf: bool = False,
        headers: dict[str, str] | None = None,
    ) -> Any:
        request_headers = {
            "Accept": "application/json",
            "User-Agent": "gilbom-container-report-smoke/1",
        }
        if self.cookies:
            request_headers["Cookie"] = self.cookie_header()
        if payload is not None:
            request_headers["Content-Type"] = "application/json"
        if csrf:
            require(
                bool(self.csrf_token),
                "CSRF token is unavailable",
            )
            request_headers["X-CSRF-TOKEN"] = str(self.csrf_token)
        if headers:
            request_headers.update(headers)
        body = (
            json.dumps(
                payload,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
            if payload is not None
            else None
        )
        connection = self._connection()
        try:
            connection.request(
                method,
                self.prefix + path,
                body=body,
                headers=request_headers,
            )
            response = connection.getresponse()
            response_body = response.read()
            self._capture_cookies(response.getheaders())
        finally:
            connection.close()
        if response.status not in expected:
            safe_code = "unparseable"
            try:
                decoded = json.loads(response_body)
                if isinstance(decoded, dict):
                    safe_code = str(decoded.get("code", "missing"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                pass
            raise SmokeFailure(
                f"{method} {path} returned HTTP {response.status} "
                f"(code={safe_code})"
            )
        if not response_body:
            return None
        try:
            return json.loads(response_body)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise SmokeFailure(
                f"{method} {path} did not return JSON"
            ) from error

    def fetch_csrf(self) -> None:
        response = self.request(
            "GET",
            "/api/v1/auth/csrf-token",
        )
        require(
            response.get("headerName") == "X-CSRF-TOKEN",
            "unexpected CSRF header name",
        )
        token = response.get("token")
        require(
            isinstance(token, str) and bool(token),
            "CSRF token response is empty",
        )
        self.csrf_token = token

    def serializable(self) -> dict[str, Any]:
        return {
            "cookies": self.cookies,
            "csrfToken": self.csrf_token,
        }


class ReportSseListener:
    def __init__(self, session: ApiSession, report_id: str) -> None:
        self.session = session
        self.report_id = report_id
        self.ready = threading.Event()
        self.events: queue.Queue[tuple[str, dict[str, Any]]] = queue.Queue()
        self.failure: queue.Queue[BaseException] = queue.Queue(maxsize=1)
        self.connection: http.client.HTTPConnection | None = None
        self.thread = threading.Thread(
            target=self._run,
            name="report-smoke-sse",
            daemon=True,
        )

    def start(self) -> None:
        self.thread.start()
        require(
            self.ready.wait(timeout=15),
            "SSE did not emit CONNECTED",
        )
        self._raise_failure()

    def wait_for_update(self, timeout: float = 15.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self._raise_failure()
            try:
                event_name, data = self.events.get(timeout=0.25)
            except queue.Empty:
                continue
            if event_name == "report.updated":
                return data
        raise SmokeFailure("SSE report.updated event was not received")

    def close(self) -> None:
        if self.connection is not None:
            self.connection.close()

    def _raise_failure(self) -> None:
        try:
            error = self.failure.get_nowait()
        except queue.Empty:
            return
        raise SmokeFailure("SSE reader failed") from error

    def _run(self) -> None:
        try:
            connection = self.session._connection(timeout=40.0)
            self.connection = connection
            headers = {
                "Accept": "text/event-stream",
                "Cache-Control": "no-cache",
                "Cookie": self.session.cookie_header(),
                "User-Agent": "gilbom-container-report-smoke/1",
            }
            path = (
                self.session.prefix
                + "/api/v1/stream/reports?"
                + urlencode({"reportId": self.report_id})
            )
            connection.request("GET", path, headers=headers)
            response = connection.getresponse()
            if response.status != 200:
                raise SmokeFailure(
                    f"SSE returned HTTP {response.status}"
                )
            event_name = ""
            data_lines: list[str] = []
            while True:
                raw_line = response.readline()
                if not raw_line:
                    raise SmokeFailure("SSE connection closed")
                line = raw_line.decode("utf-8").rstrip("\r\n")
                if line == "":
                    if event_name and data_lines:
                        data = json.loads("\n".join(data_lines))
                        if event_name == "CONNECTED":
                            self.ready.set()
                        else:
                            self.events.put((event_name, data))
                    event_name = ""
                    data_lines = []
                    continue
                if line.startswith(":"):
                    continue
                if line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].strip())
        except BaseException as error:  # daemon reader reports to main flow
            if not self.ready.is_set():
                self.failure.put(error)
                self.ready.set()
            elif self.failure.empty():
                self.failure.put(error)


def authenticate(
    session: ApiSession,
    email: str,
    password: str,
    *,
    new_password: str | None,
    required_permission: str,
) -> dict[str, Any]:
    session.fetch_csrf()
    challenge = session.request(
        "POST",
        "/api/v1/auth/login",
        payload={"email": email, "password": password},
        expected=(202,),
        csrf=True,
    )
    if new_password is not None:
        require(
            challenge.get("loginState")
            == "PASSWORD_CHANGE_REQUIRED",
            "bootstrap author did not require password change",
        )
        challenge = session.request(
            "POST",
            "/api/v1/auth/password/initial-change",
            payload={"newPassword": new_password},
            expected=(202,),
            csrf=True,
        )
    require(
        challenge.get("loginState")
        == "MFA_ENROLLMENT_REQUIRED",
        f"{email} did not enter MFA enrollment",
    )
    enrollment = session.request(
        "POST",
        "/api/v1/auth/mfa/totp/enroll",
        csrf=True,
    )
    manual_key = enrollment.get("manualEntryKey")
    require(
        isinstance(manual_key, str) and bool(manual_key),
        "MFA enrollment key is unavailable",
    )
    confirmed = session.request(
        "POST",
        "/api/v1/auth/mfa/totp/confirm",
        payload={"code": totp(manual_key)},
        csrf=True,
    )
    csrf_token = confirmed.get("csrfToken")
    require(
        isinstance(csrf_token, str) and bool(csrf_token),
        "rotated CSRF token is unavailable",
    )
    session.csrf_token = csrf_token
    current = session.request("GET", "/api/v1/auth/me")
    permissions = current.get("permissions", [])
    require(
        required_permission in permissions,
        f"{email} lacks {required_permission}",
    )
    return current


def create_llm_report(session: ApiSession) -> dict[str, Any]:
    return session.request(
        "POST",
        "/api/v1/reports",
        payload={
            "generationMethod": "LLM",
            "reportType": "PERIODIC",
            "periodFrom": PERIOD_FROM,
            "periodTo": PERIOD_TO,
            # 운영 UI와 같은 단건 결함 생성 계약을 검증한다. LLM 보고서는
            # 이제 대상 결함 없이 생성할 수 없다.
            "filterCriteria": {"defectId": FIXTURE_DEFECT_ID},
        },
        expected=(201,),
        csrf=True,
        headers={"Idempotency-Key": str(uuid.uuid4())},
    )


def assert_fixture_snapshot(report: dict[str, Any]) -> None:
    current = report.get("currentVersion", {})
    statistics = current.get("statistics", {})
    aggregate = statistics.get("aggregate", {})
    require(
        aggregate.get("missionCount") == 1,
        "fixture mission was not included in report statistics",
    )
    require(
        aggregate.get("completedMissionCount") == 1,
        "completed mission count differs",
    )
    require(
        aggregate.get("defectCount") == 1,
        "fixture defect was not included in report statistics",
    )
    require(
        aggregate.get("observationCount") == 1,
        "fixture observation count differs",
    )
    evidence = current.get("evidenceSnapshot", {})
    require(
        FIXTURE_DEFECT_ID in evidence.get("defectIds", []),
        "fixture defect evidence is absent",
    )
    require(
        FIXTURE_MISSION_ID in evidence.get("missionIds", []),
        "fixture mission evidence is absent",
    )


def expect_report_state(
    report: dict[str, Any],
    status: str,
    version: int,
) -> None:
    require(
        report.get("reportStatus") == status,
        f"expected report status {status}",
    )
    require(
        report.get("currentVersionNo") == version,
        f"expected report version {version}",
    )


def run_full(args: argparse.Namespace) -> None:
    author_password = read_secret(args.author_password_file)
    author_new_password = read_secret(
        args.author_new_password_file
    )

    author = ApiSession(args.base_url)
    author_user = authenticate(
        author,
        AUTHOR_EMAIL,
        author_password,
        new_password=author_new_password,
        required_permission="REPORT_CREATE",
    )
    reviewer = ApiSession(args.base_url)
    reviewer_user = authenticate(
        reviewer,
        REVIEWER_EMAIL,
        author_password,
        new_password=None,
        required_permission="REPORT_APPROVE",
    )
    require(
        author_user.get("userId") != reviewer_user.get("userId"),
        "author and reviewer must be different users",
    )
    require(
        reviewer_user.get("userId") == REVIEWER_ID,
        "unexpected reviewer identity",
    )

    created = create_llm_report(author)
    expect_report_state(created, "DRAFT", 1)
    current = created.get("currentVersion", {})
    require(
        current.get("generationMethod") == "TEMPLATE",
        "disabled LLM did not use Template fallback",
    )
    require(
        current.get("generationFallbackReason")
        == "POLICY_DISABLED",
        "Template fallback reason differs",
    )
    assert_fixture_snapshot(created)
    report_id = created.get("reportId")
    require(
        isinstance(report_id, str) and bool(report_id),
        "created report ID is unavailable",
    )

    listener = ReportSseListener(author, report_id)
    listener.start()
    try:
        updated = author.request(
            "PATCH",
            f"/api/v1/reports/{report_id}",
            payload={
                "expectedVersionNo": 1,
                "title": "컨테이너 Smoke 수정 보고서",
                "content": "승인 흐름을 검증하는 수정 본문입니다.",
                "statistics": {"smokeStage": 2},
                "changeNote": "첫 수정",
            },
            csrf=True,
        )
        expect_report_state(updated, "DRAFT", 2)
        event = listener.wait_for_update()
        require(
            event.get("reportId") == report_id
            and event.get("reportStatus") == "DRAFT"
            and event.get("currentVersionNo") == 2
            and event.get("deleted") is False,
            "SSE update payload differs",
        )
        reloaded = author.request(
            "GET",
            f"/api/v1/reports/{report_id}",
        )
        expect_report_state(reloaded, "DRAFT", 2)
    finally:
        listener.close()

    submitted = author.request(
        "POST",
        f"/api/v1/reports/{report_id}/submit",
        payload={"expectedVersionNo": 2},
        csrf=True,
    )
    expect_report_state(submitted, "IN_REVIEW", 2)

    rejected = reviewer.request(
        "POST",
        f"/api/v1/reports/{report_id}/reject",
        payload={
            "expectedVersionNo": 2,
            "reason": "Smoke 반려 후 보완이 필요합니다.",
        },
        csrf=True,
    )
    expect_report_state(rejected, "REJECTED", 2)
    require(
        rejected.get("lastRejectionReason")
        == "Smoke 반려 후 보완이 필요합니다.",
        "rejection reason was not persisted",
    )

    revised = author.request(
        "PATCH",
        f"/api/v1/reports/{report_id}",
        payload={
            "expectedVersionNo": 2,
            "title": "컨테이너 Smoke 최종 보고서",
            "content": "반려 사유를 반영한 최종 승인 본문입니다.",
            "statistics": {"smokeStage": 3},
            "changeNote": "반려 보완",
        },
        csrf=True,
    )
    expect_report_state(revised, "DRAFT", 3)

    resubmitted = author.request(
        "POST",
        f"/api/v1/reports/{report_id}/submit",
        payload={"expectedVersionNo": 3},
        csrf=True,
    )
    expect_report_state(resubmitted, "IN_REVIEW", 3)

    approved = reviewer.request(
        "POST",
        f"/api/v1/reports/{report_id}/approve",
        payload={
            "expectedVersionNo": 3,
            "reason": "Smoke 승인 완료",
        },
        csrf=True,
    )
    expect_report_state(approved, "APPROVED", 3)
    require(
        approved.get("approvedVersionNo") == 3,
        "approved version pointer differs",
    )
    require(
        approved.get("approvedBy") == REVIEWER_ID,
        "report was not approved by the reviewer",
    )
    approved_pdf = approved.get("approvedPdf")
    require(
        isinstance(approved_pdf, dict)
        and approved_pdf.get("versionNo") == 3,
        "approved PDF version differs",
    )
    pdf_sha256 = approved_pdf.get("sha256")
    require(
        isinstance(pdf_sha256, str)
        and re.fullmatch(r"[0-9a-f]{64}", pdf_sha256) is not None,
        "approved PDF SHA-256 is invalid",
    )

    versions = author.request(
        "GET",
        f"/api/v1/reports/{report_id}/versions",
    )
    require(
        [
            item.get("versionNo")
            for item in versions.get("content", [])
        ]
        == [3, 2, 1],
        "report version history differs",
    )

    edit_denied = author.request(
        "PATCH",
        f"/api/v1/reports/{report_id}",
        payload={
            "expectedVersionNo": 3,
            "title": "승인 후 수정 시도",
        },
        expected=(409,),
        csrf=True,
    )
    require(
        edit_denied.get("code") == "REPORT_STATE_CONFLICT",
        "approved report edit was not rejected safely",
    )
    delete_denied = author.request(
        "DELETE",
        f"/api/v1/reports/{report_id}?expectedVersionNo=3",
        expected=(409,),
        csrf=True,
    )
    require(
        delete_denied.get("code") == "REPORT_STATE_CONFLICT",
        "approved report deletion was not rejected safely",
    )

    deletable_title = "container-smoke-deletable-" + uuid.uuid4().hex
    deletable = author.request(
        "POST",
        "/api/v1/reports",
        payload={
            "generationMethod": "TEMPLATE",
            "reportType": "ISSUE",
            "title": deletable_title,
            "summary": "soft-delete 검증",
            "periodFrom": PERIOD_FROM,
            "periodTo": PERIOD_TO,
            "content": "soft-delete 전용 보고서",
            "statistics": {"smokeDelete": True},
            "filterCriteria": {},
        },
        expected=(201,),
        csrf=True,
        headers={"Idempotency-Key": str(uuid.uuid4())},
    )
    deletable_id = deletable.get("reportId")
    query = urlencode({"search": deletable_title})
    before_delete = author.request(
        "GET",
        f"/api/v1/reports?{query}",
    )
    require(
        before_delete.get("totalElements") == 1
        and before_delete.get("content", [{}])[0].get("reportId")
        == deletable_id,
        "deletable draft was not listed",
    )
    author.request(
        "DELETE",
        f"/api/v1/reports/{deletable_id}?expectedVersionNo=1",
        expected=(204,),
        csrf=True,
    )
    after_delete = author.request(
        "GET",
        f"/api/v1/reports?{query}",
    )
    require(
        after_delete.get("totalElements") == 0
        and after_delete.get("content") == [],
        "soft-deleted draft remains in active report list",
    )

    state = {
        "session": author.serializable(),
        "reportId": report_id,
        "approvedVersionNo": 3,
        "pdfSha256": pdf_sha256,
    }
    state_path = Path(args.state_file)
    with state_path.open("x", encoding="utf-8") as output:
        json.dump(state, output, separators=(",", ":"))
    os.chmod(state_path, 0o600)
    print("Container report HTTP/SSE workflow: PASS")


def load_state(path: str) -> dict[str, Any]:
    with Path(path).open(encoding="utf-8") as source:
        state = json.load(source)
    require(isinstance(state, dict), "smoke state is invalid")
    return state


def session_from_state(
    base_url: str,
    state: dict[str, Any],
) -> ApiSession:
    serialized = state.get("session", {})
    return ApiSession(
        base_url,
        cookies=serialized.get("cookies", {}),
        csrf_token=serialized.get("csrfToken"),
    )


def run_persistence(args: argparse.Namespace) -> None:
    state = load_state(args.state_file)
    session = session_from_state(args.base_url, state)
    report_id = state.get("reportId")
    detail = session.request(
        "GET",
        f"/api/v1/reports/{report_id}",
    )
    expect_report_state(
        detail,
        "APPROVED",
        int(state.get("approvedVersionNo")),
    )
    approved_pdf = detail.get("approvedPdf", {})
    require(
        approved_pdf.get("sha256") == state.get("pdfSha256"),
        "approved PDF metadata changed after Backend restart",
    )
    version = session.request(
        "GET",
        f"/api/v1/reports/{report_id}/versions/"
        f"{state.get('approvedVersionNo')}",
    )
    require(
        version.get("versionNo") == state.get("approvedVersionNo"),
        "approved version is unavailable after Backend restart",
    )
    print("Container report restart persistence: PASS")


def run_canary(args: argparse.Namespace) -> None:
    state = load_state(args.state_file)
    session = session_from_state(args.base_url, state)
    created = create_llm_report(session)
    expect_report_state(created, "DRAFT", 1)
    current = created.get("currentVersion", {})
    require(
        current.get("generationMethod") == "LLM"
        and current.get("generationFallbackReason") is None,
        "GMS canary fell back instead of producing an LLM report",
    )
    require(
        current.get("llmModel") == args.model,
        "GMS canary model differs",
    )
    require(
        current.get("promptVersion") == args.prompt_version,
        "GMS canary prompt version differs",
    )
    assert_fixture_snapshot(created)
    print("Container report GMS canary: PASS")


def run_outbound_fallback(args: argparse.Namespace) -> None:
    state = load_state(args.state_file)
    session = session_from_state(args.base_url, state)
    report_id = state.get("reportId")
    existing = session.request(
        "GET",
        f"/api/v1/reports/{report_id}",
    )
    expect_report_state(existing, "APPROVED", 3)

    manual = session.request(
        "POST",
        "/api/v1/reports",
        payload={
            "generationMethod": "TEMPLATE",
            "reportType": "ISSUE",
            "title": "장애 격리 일반 API 보고서",
            "summary": "LLM egress 차단 중 일반 API 검증",
            "periodFrom": PERIOD_FROM,
            "periodTo": PERIOD_TO,
            "content": "외부 LLM과 무관한 일반 보고서 본문입니다.",
            "statistics": {"resilience": True},
            "filterCriteria": {},
        },
        expected=(201,),
        csrf=True,
        headers={"Idempotency-Key": str(uuid.uuid4())},
    )
    manual_id = manual.get("reportId")
    expect_report_state(manual, "DRAFT", 1)
    changed = session.request(
        "PATCH",
        f"/api/v1/reports/{manual_id}",
        payload={
            "expectedVersionNo": 1,
            "content": "LLM 단절 중에도 수정 transaction이 완료됐습니다.",
            "changeNote": "resilience 일반 API 수정",
        },
        csrf=True,
    )
    expect_report_state(changed, "DRAFT", 2)

    fallback = create_llm_report(session)
    expect_report_state(fallback, "DRAFT", 1)
    current = fallback.get("currentVersion", {})
    require(
        current.get("generationMethod") == "TEMPLATE",
        "blocked LLM egress did not use Template fallback",
    )
    require(
        current.get("generationFallbackReason") == "LLM_UNAVAILABLE",
        "blocked LLM egress fallback reason differs: "
        f"{current.get('generationFallbackReason')!r}",
    )
    assert_fixture_snapshot(fallback)
    print("Container report blocked-LLM fallback and general API: PASS")


def run_disabled_fallback(args: argparse.Namespace) -> None:
    state = load_state(args.state_file)
    session = session_from_state(args.base_url, state)
    existing = session.request(
        "GET",
        f"/api/v1/reports/{state.get('reportId')}",
    )
    expect_report_state(existing, "APPROVED", 3)
    fallback = create_llm_report(session)
    expect_report_state(fallback, "DRAFT", 1)
    current = fallback.get("currentVersion", {})
    require(
        current.get("generationMethod") == "TEMPLATE"
        and current.get("generationFallbackReason")
        == "POLICY_DISABLED",
        "missing LLM Secret did not stay in disabled fallback mode",
    )
    print("Container report missing-LLM-Secret fallback: PASS")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument(
        "mode",
        choices=(
            "full",
            "persistence",
            "canary",
            "outbound-fallback",
            "disabled-fallback",
        ),
    )
    result.add_argument("--base-url", required=True)
    result.add_argument("--state-file", required=True)
    result.add_argument("--author-password-file")
    result.add_argument("--author-new-password-file")
    result.add_argument("--model")
    result.add_argument(
        "--prompt-version",
        default="report-v1",
    )
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.mode == "full":
            require(
                bool(args.author_password_file)
                and bool(args.author_new_password_file),
                "full mode requires password files",
            )
            run_full(args)
        elif args.mode == "persistence":
            run_persistence(args)
        elif args.mode == "canary":
            require(bool(args.model), "canary mode requires model")
            run_canary(args)
        elif args.mode == "outbound-fallback":
            run_outbound_fallback(args)
        else:
            run_disabled_fallback(args)
        return 0
    except SmokeFailure as error:
        print(f"Container report smoke failed: {error}", file=os.sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
