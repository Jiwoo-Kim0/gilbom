#!/usr/bin/env python3
"""Authenticate against the running backend and print the defect SSE stream.

The manual curl flow for this is long-winded: the login challenge expires in five
minutes, the CSRF token is bound to the session and rotates after authentication,
and MFA is mandatory. This script performs the whole sequence in one shot so the
only thing being tested is the stream itself.

Credentials come from the environment and are never printed:

    GILBOM_EMAIL          required
    GILBOM_PASSWORD       required
    GILBOM_TOTP_SECRET    Base32 secret from TOTP enrolment (code is derived)
    GILBOM_TOTP_CODE      six digits, if the secret is not available
    GILBOM_RECOVERY_CODE  single-use fallback, consumes one of the ten codes
    GILBOM_BASE_URL       defaults to http://localhost:8080
    GILBOM_PATROL_AREA_ID optional stream filter

Read-only: it authenticates and subscribes, and changes no defect data.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import http.cookiejar
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request
from typing import Any

BASE_URL = os.environ.get("GILBOM_BASE_URL", "http://localhost:8080").rstrip("/")
STREAM_PATH = "/api/v1/stream/defects"


class Client:
    """Keeps the session cookie and the current CSRF token together."""

    def __init__(self, base_url: str) -> None:
        self.base_url = base_url
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar)
        )
        self.csrf_token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        csrf: bool = False,
    ) -> tuple[int, Any]:
        headers = {
            "Accept": "application/json",
            "User-Agent": "gilbom-defect-sse-verify",
        }
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if csrf:
            if self.csrf_token is None:
                self.issue_csrf()
            headers["X-CSRF-TOKEN"] = str(self.csrf_token)

        request = urllib.request.Request(
            self.base_url + path, data=data, headers=headers, method=method
        )
        try:
            response = self.opener.open(request, timeout=15)
            raw = response.read()
            status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
        try:
            return status, json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return status, None

    def issue_csrf(self) -> None:
        status, body = self.request("GET", "/api/v1/auth/csrf-token")
        if status != 200 or not isinstance(body, dict):
            fail(f"CSRF 토큰을 받지 못했습니다 (status={status}).")
        self.csrf_token = body["token"]


def fail(message: str) -> None:
    print(f"실패: {message}", file=sys.stderr)
    raise SystemExit(1)


def totp_from_secret(manual_key: str) -> str:
    """Standard TOTP: SHA-1, 30 second step, six digits."""
    padding = "=" * ((8 - len(manual_key) % 8) % 8)
    secret = base64.b32decode(manual_key + padding, casefold=True)
    step = int(time.time()) // 30
    digest = hmac.new(secret, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    binary = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return f"{binary % 1_000_000:06d}"


def authenticate(client: Client) -> None:
    email = os.environ.get("GILBOM_EMAIL")
    password = os.environ.get("GILBOM_PASSWORD")
    if not email or not password:
        fail("GILBOM_EMAIL과 GILBOM_PASSWORD를 환경변수로 설정하세요.")

    client.issue_csrf()
    status, body = client.request(
        "POST",
        "/api/v1/auth/login",
        {"email": email, "password": password},
        csrf=True,
    )
    if status != 202 or not isinstance(body, dict):
        fail(f"로그인이 거부됐습니다 (status={status}, body={body}).")

    state = body.get("loginState")
    print(f"로그인 단계 통과. loginState={state}")

    if state == "PASSWORD_CHANGE_REQUIRED":
        fail(
            "초기 비밀번호 변경이 필요합니다. "
            "POST /api/v1/auth/password/initial-change를 먼저 수행하세요."
        )
    if state == "MFA_ENROLLMENT_REQUIRED":
        fail(
            "TOTP 등록이 필요합니다. "
            "/api/v1/auth/mfa/totp/enroll과 /confirm을 먼저 수행하세요."
        )
    if state != "MFA_REQUIRED":
        fail(f"예상하지 못한 loginState입니다: {state}")

    secret = os.environ.get("GILBOM_TOTP_SECRET")
    code = os.environ.get("GILBOM_TOTP_CODE")
    recovery = os.environ.get("GILBOM_RECOVERY_CODE")

    if secret:
        status, body = client.request(
            "POST",
            "/api/v1/auth/mfa/totp/verify",
            {"code": totp_from_secret(secret)},
            csrf=True,
        )
        method = "TOTP(시크릿에서 생성)"
    elif code:
        status, body = client.request(
            "POST",
            "/api/v1/auth/mfa/totp/verify",
            {"code": code},
            csrf=True,
        )
        method = "TOTP(직접 입력)"
    elif recovery:
        # 복구 코드는 1회용이므로 다른 수단이 없을 때만 쓴다.
        status, body = client.request(
            "POST",
            "/api/v1/auth/mfa/recovery",
            {"recoveryCode": recovery},
            csrf=True,
        )
        method = "복구 코드(1회 소모)"
    else:
        fail(
            "MFA 수단이 없습니다. GILBOM_TOTP_SECRET 또는 GILBOM_TOTP_CODE, "
            "또는 GILBOM_RECOVERY_CODE 중 하나를 설정하세요."
        )

    if status != 200 or not isinstance(body, dict):
        fail(f"{method} 검증이 거부됐습니다 (status={status}, body={body}).")

    # 인증 성공 시 세션이 재발급되며 CSRF 토큰도 함께 바뀐다.
    if body.get("csrfToken"):
        client.csrf_token = body["csrfToken"]
    print(f"MFA 통과. 수단={method}")

    status, body = client.request("GET", "/api/v1/auth/session-state")
    actual = body.get("authenticationState") if isinstance(body, dict) else None
    if actual != "AUTHENTICATED":
        fail(f"세션이 인증 상태가 아닙니다: {actual}")
    print("세션 상태 AUTHENTICATED 확인")


def stream(client: Client) -> None:
    path = STREAM_PATH
    patrol_area_id = os.environ.get("GILBOM_PATROL_AREA_ID")
    if patrol_area_id:
        path += f"?patrolAreaId={patrol_area_id}"

    request = urllib.request.Request(
        client.base_url + path,
        headers={
            "Accept": "text/event-stream",
            "User-Agent": "gilbom-defect-sse-verify",
        },
        method="GET",
    )
    try:
        response = client.opener.open(request, timeout=None)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        fail(f"스트림 연결이 거부됐습니다 (status={error.code}): {detail}")

    print(f"스트림 연결됨: {path}")
    print("Ctrl+C로 종료합니다. 이제 결함을 변경하면 이벤트가 도착합니다.\n")

    event_name = None
    try:
        for raw_line in response:
            line = raw_line.decode("utf-8", "replace").rstrip("\n")
            if line.startswith("event:"):
                event_name = line[len("event:"):].strip()
            elif line.startswith("data:"):
                payload = line[len("data:"):].strip()
                stamp = time.strftime("%H:%M:%S")
                if event_name == "HEARTBEAT":
                    print(f"[{stamp}] HEARTBEAT")
                else:
                    print(f"[{stamp}] {event_name}")
                    try:
                        parsed = json.loads(payload)
                        print(json.dumps(parsed, ensure_ascii=False, indent=2))
                    except ValueError:
                        print(f"  {payload}")
                event_name = None
    except KeyboardInterrupt:
        print("\n종료합니다.")


def main() -> None:
    print(f"대상: {BASE_URL}")
    client = Client(BASE_URL)
    authenticate(client)
    stream(client)


if __name__ == "__main__":
    main()
