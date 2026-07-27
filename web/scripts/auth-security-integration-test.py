#!/usr/bin/env python3
"""Run destructive authentication checks only against an ephemeral database/backend.

The harness inherits the Compose backend's secret environment without printing it,
creates a dedicated PostgreSQL database, starts a temporary backend container, and
drops both in a finally block. It never changes the normal local gilbom database.
"""

from __future__ import annotations

import base64
import concurrent.futures
import hashlib
import hmac
import http.cookiejar
import json
import math
import os
from pathlib import Path
import secrets
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


PROJECT_DIR = Path(__file__).resolve().parents[1]
SESSION_COOKIE_NAME = "GILBOM_SESSION"


class CheckFailure(RuntimeError):
    pass


def ensure(condition: bool, check: str) -> None:
    if not condition:
        raise CheckFailure(check)


def command(
    arguments: list[str],
    *,
    input_text: str | None = None,
    allow_failure: bool = False,
) -> str:
    completed = subprocess.run(
        arguments,
        cwd=PROJECT_DIR,
        input=input_text,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0 and not allow_failure:
        raise CheckFailure("docker_or_database_command")
    return completed.stdout.strip()


def compose(*arguments: str, allow_failure: bool = False) -> str:
    return command(
        ["docker", "compose", *arguments],
        allow_failure=allow_failure,
    )


def db_environment(container: str, name: str) -> str:
    value = command(["docker", "exec", container, "printenv", name])
    ensure(bool(value), f"database_environment_{name.lower()}")
    return value


def psql(
    container: str,
    database: str,
    sql: str,
    *,
    tuples_only: bool = True,
) -> str:
    formatting = "--tuples-only --no-align --quiet" if tuples_only else ""
    shell = (
        'PGPASSWORD="$POSTGRES_PASSWORD" psql '
        '--host 127.0.0.1 --username "$POSTGRES_USER" '
        f'--dbname "$1" --no-password --set ON_ERROR_STOP=1 {formatting}'
    )
    return command(
        ["docker", "exec", "-i", container, "sh", "-c", shell, "sh", database],
        input_text=sql,
    )


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: Any
    headers: Any


class ApiClient:
    def __init__(self, base_url: str, client_ip: str) -> None:
        self.base_url = base_url
        self.client_ip = client_ip
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )
        self.csrf_token: str | None = None

    def session_cookie(self) -> str:
        for cookie in self.cookies:
            if cookie.name == SESSION_COOKIE_NAME:
                return cookie.value
        raise CheckFailure("session_cookie_missing")

    def issue_csrf(self) -> str:
        result = self.request("GET", "/api/v1/auth/csrf-token")
        ensure(result.status == 200, "csrf_status")
        ensure(
            isinstance(result.body, dict)
            and result.body.get("headerName") == "X-CSRF-TOKEN"
            and isinstance(result.body.get("token"), str)
            and bool(result.body["token"]),
            "csrf_contract",
        )
        self.csrf_token = result.body["token"]
        return self.csrf_token

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        csrf: bool = False,
    ) -> HttpResult:
        headers = {
            "Accept": "application/json",
            "User-Agent": "gilbom-auth-security-integration",
            "X-Forwarded-For": self.client_ip,
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
            self.base_url + path,
            data=data,
            headers=headers,
            method=method,
        )
        try:
            response = self.opener.open(request, timeout=15)
            raw = response.read()
            status = response.status
            response_headers = response.headers
        except TimeoutError as error:
            safe_path = path.strip("/").replace("/", "_").replace("-", "_")
            raise CheckFailure(
                f"http_timeout_{method.lower()}_{safe_path}"
            ) from error
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
            response_headers = error.headers

        parsed: Any = None
        if raw:
            try:
                parsed = json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError:
                parsed = raw.decode("utf-8", errors="replace")

        if status in (401, 403):
            self.csrf_token = None
        return HttpResult(status, parsed, response_headers)


def replay_get(
    base_url: str,
    cookie_value: str,
    path: str = "/api/v1/auth/me",
) -> HttpResult:
    request = urllib.request.Request(
        base_url + path,
        headers={
            "Accept": "application/json",
            "Cookie": f"{SESSION_COOKIE_NAME}={cookie_value}",
            "X-Forwarded-For": "198.51.100.249",
        },
        method="GET",
    )
    try:
        response = urllib.request.urlopen(request, timeout=15)
        return HttpResult(
            response.status,
            json.loads(response.read().decode("utf-8")),
            response.headers,
        )
    except urllib.error.HTTPError as error:
        return HttpResult(
            error.code,
            json.loads(error.read().decode("utf-8")),
            error.headers,
        )


def decode_totp_secret(manual_key: str) -> bytes:
    padding = "=" * ((8 - len(manual_key) % 8) % 8)
    return base64.b32decode(manual_key + padding, casefold=True)


def totp(secret: bytes, step: int) -> str:
    digest = hmac.new(
        secret,
        struct.pack(">Q", step),
        hashlib.sha1,
    ).digest()
    offset = digest[-1] & 0x0F
    binary = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return f"{binary % 1_000_000:06d}"


def expect_error(result: HttpResult, status: int, code: str, check: str) -> None:
    ensure(result.status == status, check + "_status")
    ensure(
        isinstance(result.body, dict) and result.body.get("code") == code,
        check + "_code",
    )


def login(
    client: ApiClient,
    email: str,
    password: str,
    expected_state: str,
) -> HttpResult:
    result = client.request(
        "POST",
        "/api/v1/auth/login",
        {"email": email, "password": password},
        csrf=True,
    )
    ensure(result.status == 202, "login_accepted")
    ensure(
        isinstance(result.body, dict)
        and result.body.get("loginState") == expected_state
        and "authenticated" not in result.body
        and "csrfToken" not in result.body,
        "login_pre_auth_contract",
    )
    return result


def authenticate_recovery(client: ApiClient, recovery_code: str) -> HttpResult:
    result = client.request(
        "POST",
        "/api/v1/auth/mfa/recovery",
        {"recoveryCode": recovery_code},
        csrf=True,
    )
    ensure(result.status == 200, "recovery_authentication")
    ensure(
        isinstance(result.body, dict)
        and result.body.get("recoveryCodeUsed") is True
        and isinstance(result.body.get("csrfToken"), str),
        "recovery_response_contract",
    )
    client.csrf_token = result.body["csrfToken"]
    return result


def database_session_policy(
    db_container: str,
    database: str,
    user_id: str,
) -> tuple[int, int, int]:
    value = psql(
        db_container,
        database,
        f"""
        SELECT
            COUNT(*),
            MIN(session.max_inactive_interval),
            ROUND(MIN(
                security_state.absolute_expiry_time
                - EXTRACT(EPOCH FROM clock_timestamp()) * 1000
            ) / 1000)
        FROM session_store.spring_session session
        JOIN session_store.session_security_state security_state
          ON security_state.session_primary_id = session.primary_id
        WHERE security_state.user_id = {sql_literal(user_id)}::uuid
          AND security_state.authentication_state = 'AUTHENTICATED';
        """,
    )
    parts = value.split("|")
    ensure(len(parts) == 3, "session_policy_query")
    return int(parts[0]), int(parts[1]), int(parts[2])


def wait_until_healthy(base_url: str, container_name: str) -> None:
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(
                base_url + "/actuator/health/readiness",
                timeout=3,
            ) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        state = command(
            ["docker", "inspect", "-f", "{{.State.Running}}", container_name],
            allow_failure=True,
        )
        if state == "false":
            break
        time.sleep(1)
    raise CheckFailure("temporary_backend_health")


def run_integration(base_url: str, db_container: str, database: str) -> list[str]:
    checks: list[str] = []
    email = f"security-{secrets.token_hex(8)}@example.invalid"
    unknown_email = f"missing-{secrets.token_hex(8)}@example.invalid"
    old_password = secrets.token_urlsafe(12)[:16]
    new_password = secrets.token_urlsafe(12)[:16]
    wrong_password = secrets.token_urlsafe(12)[:16]
    ensure(len({old_password, new_password, wrong_password}) == 3, "test_secret_uniqueness")

    registration_clients = [
        ApiClient(base_url, "198.51.100.10"),
        ApiClient(base_url, "198.51.100.11"),
    ]
    for client in registration_clients:
        client.issue_csrf()

    def register(client: ApiClient) -> HttpResult:
        return client.request(
            "POST",
            "/api/v1/auth/registrations",
            {
                "email": email,
                "password": old_password,
                "displayName": "통합 보안 테스트",
            },
            csrf=True,
        )

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        registration_results = list(executor.map(register, registration_clients))
    ensure(
        sorted(result.status for result in registration_results) == [201, 409],
        "email_unique_concurrent_http",
    )
    account_count = int(psql(
        db_container,
        database,
        f"""
        SELECT COUNT(*)
        FROM auth.admin_user
        WHERE normalized_email = LOWER({sql_literal(email)});
        """,
    ))
    ensure(account_count == 1, "email_unique_database")
    checks.append("email_unique_race")

    user_id = psql(
        db_container,
        database,
        f"""
        UPDATE auth.admin_user
        SET account_status = 'ACTIVE',
            is_active = TRUE,
            password_change_required = FALSE
        WHERE normalized_email = LOWER({sql_literal(email)})
        RETURNING user_id;
        """,
    )
    ensure(bool(user_id), "test_account_activation")

    known_failure_client = ApiClient(base_url, "198.51.100.20")
    known_failure = known_failure_client.request(
        "POST",
        "/api/v1/auth/login",
        {"email": email, "password": wrong_password},
        csrf=True,
    )
    unknown_failure_client = ApiClient(base_url, "198.51.100.21")
    unknown_failure = unknown_failure_client.request(
        "POST",
        "/api/v1/auth/login",
        {"email": unknown_email, "password": wrong_password},
        csrf=True,
    )
    for result in (known_failure, unknown_failure):
        expect_error(
            result,
            401,
            "AUTH_UNAUTHENTICATED",
            "generic_login_failure",
        )
    ensure(
        {
            key: known_failure.body.get(key)
            for key in ("status", "code", "message", "details", "path")
        }
        == {
            key: unknown_failure.body.get(key)
            for key in ("status", "code", "message", "details", "path")
        },
        "account_enumeration_response",
    )
    checks.append("generic_login_failure")

    first = ApiClient(base_url, "198.51.100.30")
    login(first, email, old_password, "MFA_ENROLLMENT_REQUIRED")
    expect_error(
        first.request("GET", "/api/v1/auth/me"),
        401,
        "AUTH_UNAUTHENTICATED",
        "pre_auth_current_user",
    )
    expect_error(
        first.request("GET", "/api/v1/my/profile"),
        401,
        "AUTH_UNAUTHENTICATED",
        "pre_auth_profile",
    )
    checks.append("password_only_cannot_access")

    first.issue_csrf()
    enrollment = first.request(
        "POST",
        "/api/v1/auth/mfa/totp/enroll",
        csrf=True,
    )
    ensure(enrollment.status == 200, "mfa_enrollment")
    ensure(
        isinstance(enrollment.body, dict)
        and isinstance(enrollment.body.get("manualEntryKey"), str),
        "mfa_enrollment_contract",
    )
    manual_key = enrollment.body["manualEntryKey"]
    secret = decode_totp_secret(manual_key)
    enrollment_step = math.floor(time.time() / 30)
    enrollment_code = totp(secret, enrollment_step)
    old_session_cookie = first.session_cookie()
    old_csrf = str(first.csrf_token)
    confirmation = first.request(
        "POST",
        "/api/v1/auth/mfa/totp/confirm",
        {"code": enrollment_code},
        csrf=True,
    )
    ensure(confirmation.status == 200, "mfa_confirmation")
    ensure(
        isinstance(confirmation.body, dict)
        and len(confirmation.body.get("recoveryCodes", [])) == 10
        and isinstance(confirmation.body.get("csrfToken"), str),
        "mfa_confirmation_contract",
    )
    recovery_codes = list(confirmation.body["recoveryCodes"])
    rotated_csrf = confirmation.body["csrfToken"]
    first.csrf_token = rotated_csrf
    rotated_session_cookie = first.session_cookie()
    ensure(rotated_session_cookie != old_session_cookie, "session_id_rotation")
    ensure(first.csrf_token != old_csrf, "csrf_rotation")
    expect_error(
        replay_get(base_url, old_session_cookie),
        401,
        "AUTH_UNAUTHENTICATED",
        "old_pre_auth_session_replay",
    )
    ensure(first.request("GET", "/api/v1/auth/me").status == 200, "mfa_promoted")
    checks.extend(["mfa_gate", "session_and_csrf_rotation"])

    second = ApiClient(base_url, "198.51.100.31")
    login(second, email, old_password, "MFA_REQUIRED")
    authenticate_recovery(second, recovery_codes[0])
    expect_error(
        first.request("GET", "/api/v1/auth/me"),
        401,
        "AUTH_UNAUTHENTICATED",
        "concurrent_session_first_revoked",
    )

    third = ApiClient(base_url, "198.51.100.32")
    login(third, email, old_password, "MFA_REQUIRED")
    expect_error(
        third.request(
            "POST",
            "/api/v1/auth/mfa/recovery",
            {"recoveryCode": recovery_codes[0]},
            csrf=True,
        ),
        401,
        "AUTH_UNAUTHENTICATED",
        "recovery_code_reuse",
    )
    authenticate_recovery(third, recovery_codes[1])
    expect_error(
        second.request("GET", "/api/v1/auth/me"),
        401,
        "AUTH_UNAUTHENTICATED",
        "concurrent_session_second_revoked",
    )
    checks.extend(["single_concurrent_session", "recovery_code_reuse"])

    fourth = ApiClient(base_url, "198.51.100.33")
    login(fourth, email, old_password, "MFA_REQUIRED")
    expect_error(
        fourth.request(
            "POST",
            "/api/v1/auth/mfa/totp/verify",
            {"code": enrollment_code},
            csrf=True,
        ),
        401,
        "AUTH_UNAUTHENTICATED",
        "totp_step_reuse",
    )
    next_step = max(enrollment_step + 1, math.floor(time.time() / 30))
    next_code = totp(secret, next_step)
    totp_success = fourth.request(
        "POST",
        "/api/v1/auth/mfa/totp/verify",
        {"code": next_code},
        csrf=True,
    )
    ensure(totp_success.status == 200, "new_totp_step")
    fourth.csrf_token = totp_success.body["csrfToken"]
    expect_error(
        third.request("GET", "/api/v1/auth/me"),
        401,
        "AUTH_UNAUTHENTICATED",
        "totp_concurrent_session_revocation",
    )
    count, idle_seconds, remaining_absolute = database_session_policy(
        db_container,
        database,
        user_id,
    )
    ensure(count == 1, "database_single_session")
    ensure(idle_seconds == 1800, "database_idle_timeout")
    ensure(28_700 <= remaining_absolute <= 28_810, "database_absolute_timeout")
    checks.extend(["totp_step_reuse", "session_lifetimes"])
    ensure(
        fourth.request("GET", "/api/v1/auth/me").status == 200,
        "authenticated_before_pending_challenge",
    )

    pending = ApiClient(base_url, "198.51.100.34")
    login(pending, email, old_password, "MFA_REQUIRED")
    ensure(
        fourth.request("GET", "/api/v1/auth/me").status == 200,
        "authenticated_after_pending_challenge",
    )
    authenticated_cookie_before_password_change = fourth.session_cookie()
    password_change = fourth.request(
        "PATCH",
        "/api/v1/my/password",
        {
            "currentPassword": old_password,
            "newPassword": new_password,
        },
        csrf=True,
    )
    if password_change.status != 204:
        public_code = (
            password_change.body.get("code", "NO_CODE")
            if isinstance(password_change.body, dict)
            else "NON_JSON"
        )
        public_category = (
            "CSRF"
            if isinstance(password_change.body, dict)
            and password_change.body.get("message")
                == "요청 검증에 실패했습니다."
            else "ACCESS"
        )
        raise CheckFailure(
            f"password_change_http_{password_change.status}_"
            + f"{public_code}_{public_category}"
        )
    session_count_after_password_change = int(psql(
        db_container,
        database,
        f"""
        SELECT COUNT(*)
        FROM session_store.session_security_state
        WHERE user_id = {sql_literal(user_id)}::uuid;
        """,
    ))
    ensure(session_count_after_password_change == 0, "password_change_all_sessions")
    expect_error(
        replay_get(base_url, authenticated_cookie_before_password_change),
        401,
        "AUTH_UNAUTHENTICATED",
        "password_change_old_session_replay",
    )
    pending_reuse = pending.request(
        "POST",
        "/api/v1/auth/mfa/recovery",
        {"recoveryCode": recovery_codes[2]},
        csrf=True,
    )
    ensure(pending_reuse.status in (401, 403), "password_change_pre_auth_revoked")

    after_change = ApiClient(base_url, "198.51.100.35")
    expect_error(
        after_change.request(
            "POST",
            "/api/v1/auth/login",
            {"email": email, "password": old_password},
            csrf=True,
        ),
        401,
        "AUTH_UNAUTHENTICATED",
        "old_password_rejected",
    )
    login(after_change, email, new_password, "MFA_REQUIRED")
    authenticate_recovery(after_change, recovery_codes[2])
    checks.append("password_change_all_sessions")

    logout_cookie = after_change.session_cookie()
    logout_result = after_change.request(
        "POST",
        "/api/v1/auth/logout",
        csrf=True,
    )
    ensure(logout_result.status == 204, "logout")
    expect_error(
        replay_get(base_url, logout_cookie),
        401,
        "AUTH_UNAUTHENTICATED",
        "logout_session_replay",
    )
    checks.append("logout_replay")

    absolute_client = ApiClient(base_url, "198.51.100.36")
    login(absolute_client, email, new_password, "MFA_REQUIRED")
    authenticate_recovery(absolute_client, recovery_codes[3])
    psql(
        db_container,
        database,
        f"""
        UPDATE session_store.session_security_state
        SET absolute_expiry_time =
            FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint - 1
        WHERE user_id = {sql_literal(user_id)}::uuid
          AND authentication_state = 'AUTHENTICATED';
        """,
    )
    expect_error(
        absolute_client.request("GET", "/api/v1/auth/me"),
        401,
        "AUTH_UNAUTHENTICATED",
        "absolute_expiry",
    )

    idle_client = ApiClient(base_url, "198.51.100.37")
    login(idle_client, email, new_password, "MFA_REQUIRED")
    authenticate_recovery(idle_client, recovery_codes[4])
    psql(
        db_container,
        database,
        f"""
        UPDATE session_store.spring_session session
        SET creation_time =
                FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint
                    - 1900000,
            last_access_time =
                FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint
                    - 1801000,
            expiry_time =
                FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint
                    - 1000
        FROM session_store.session_security_state security_state
        WHERE security_state.session_primary_id = session.primary_id
          AND security_state.user_id = {sql_literal(user_id)}::uuid
          AND security_state.authentication_state = 'AUTHENTICATED';
        """,
    )
    expect_error(
        idle_client.request("GET", "/api/v1/auth/me"),
        401,
        "AUTH_UNAUTHENTICATED",
        "idle_expiry",
    )
    checks.append("absolute_and_idle_expiry")

    account_limit_client = ApiClient(base_url, "198.51.100.40")
    for attempt in range(5):
        failure = account_limit_client.request(
            "POST",
            "/api/v1/auth/login",
            {"email": email, "password": wrong_password},
            csrf=True,
        )
        expect_error(
            failure,
            401,
            "AUTH_UNAUTHENTICATED",
            f"account_failure_{attempt + 1}",
        )
    locked = psql(
        db_container,
        database,
        f"""
        SELECT failed_login_count || '|' || (locked_until > clock_timestamp())
        FROM auth.admin_user
        WHERE user_id = {sql_literal(user_id)}::uuid;
        """,
    )
    ensure(locked in ("5|t", "5|true"), "database_account_lock")
    account_limited = account_limit_client.request(
        "POST",
        "/api/v1/auth/login",
        # 올바른 비밀번호도 잠금 만료 전에는 인증을 재개하지 못해야 한다.
        {"email": email, "password": new_password},
        csrf=True,
    )
    expect_error(
        account_limited,
        429,
        "COMMON_RATE_LIMIT_EXCEEDED",
        "account_rate_limit",
    )

    ip_statuses: list[int] = []
    ip_client = ApiClient(base_url, "198.51.100.41")
    for _ in range(21):
        result = ip_client.request(
            "POST",
            "/api/v1/auth/login",
            {"email": unknown_email, "password": wrong_password},
            csrf=False,
        )
        ip_statuses.append(result.status)
    ensure(ip_statuses[:20] == [403] * 20, "ip_rate_limit_first_twenty")
    ensure(ip_statuses[20] == 429, "ip_rate_limit_twenty_first")
    checks.append("ip_and_account_rate_limits")

    non_argon_passwords = int(psql(
        db_container,
        database,
        """
        SELECT COUNT(*)
        FROM auth.admin_user
        WHERE password_hash NOT LIKE '{argon2}$argon2id$%';
        """,
    ))
    non_argon_recovery = int(psql(
        db_container,
        database,
        """
        SELECT COUNT(*)
        FROM auth.mfa_recovery_code
        WHERE code_hash NOT LIKE '{argon2}$argon2id$%';
        """,
    ))
    ensure(non_argon_passwords == 0, "database_password_argon2_only")
    ensure(non_argon_recovery == 0, "database_recovery_argon2_only")
    checks.append("argon2_only_storage")

    audit_text = psql(
        db_container,
        database,
        f"""
        SELECT COALESCE(string_agg(row_to_json(log)::text, E'\\n'), '')
        FROM audit.audit_log log
        WHERE log.actor_user_id = {sql_literal(user_id)}::uuid
           OR log.target_id = {sql_literal(user_id)}::uuid;
        """,
    )
    container_name = os.environ["GILBOM_SECURITY_TEST_CONTAINER"]
    backend_logs = command(["docker", "logs", container_name])
    forbidden_values = [
        old_password,
        new_password,
        wrong_password,
        manual_key,
        old_csrf,
        rotated_csrf,
        old_session_cookie,
        rotated_session_cookie,
        logout_cookie,
        enrollment_code,
        next_code,
        *recovery_codes,
    ]
    for forbidden in forbidden_values:
        if forbidden:
            ensure(forbidden not in audit_text, "audit_secret_leak")
            ensure(forbidden not in backend_logs, "log_secret_leak")
    checks.append("audit_and_log_secret_absence")

    return checks


def main() -> int:
    suffix = secrets.token_hex(5)
    database = f"gilbom_security_test_{suffix}"
    container_name = f"gilbom-auth-security-test-{suffix}"
    os.environ["GILBOM_SECURITY_TEST_CONTAINER"] = container_name
    db_created = False
    backend_started = False
    db_container = ""

    try:
        db_container = compose("ps", "-q", "db")
        ensure(bool(db_container), "database_container")
        flyway_user = db_environment(db_container, "POSTGRES_FLYWAY_USER")
        app_user = db_environment(db_container, "POSTGRES_APP_USER")
        ensure(
            flyway_user.replace("_", "").isalnum()
            and app_user.replace("_", "").isalnum(),
            "database_role_identifier",
        )
        psql(
            db_container,
            "postgres",
            f"CREATE DATABASE {database} OWNER {flyway_user};",
        )
        db_created = True
        psql(
            db_container,
            database,
            f"""
            CREATE EXTENSION IF NOT EXISTS pgcrypto;
            CREATE EXTENSION IF NOT EXISTS postgis;
            GRANT CONNECT ON DATABASE {database} TO {app_user};
            """,
        )

        compose(
            "run",
            "-d",
            "--no-deps",
            "--name",
            container_name,
            "-p",
            "127.0.0.1::8080",
            "-e",
            f"SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/{database}",
            "-e",
            f"MQTT_CLIENT_ID={container_name}",
            "-e",
            "INITIAL_ADMIN_BOOTSTRAP_ENABLED=false",
            "backend",
        )
        backend_started = True
        port_binding = command(
            ["docker", "port", container_name, "8080/tcp"]
        )
        ensure(port_binding.startswith("127.0.0.1:"), "temporary_backend_port")
        port = port_binding.rsplit(":", 1)[1]
        base_url = f"http://127.0.0.1:{port}"
        wait_until_healthy(base_url, container_name)

        checks = run_integration(base_url, db_container, database)
        for check in checks:
            print(f"PASS {check}")
        print(f"auth_security_integration=passed checks={len(checks)}")
        return 0
    except CheckFailure as failure:
        print(f"auth_security_integration=failed check={failure}", file=sys.stderr)
        if backend_started:
            diagnostic_logs = command(
                ["docker", "logs", "--tail", "120", container_name],
                allow_failure=True,
            )
            if diagnostic_logs:
                print(
                    "temporary_backend_log_tail_begin\n"
                    + diagnostic_logs
                    + "\ntemporary_backend_log_tail_end",
                    file=sys.stderr,
                )
        return 1
    except Exception as exception:
        print(
            "auth_security_integration=failed "
            + "check=unexpected_harness_error "
            + f"type={type(exception).__name__}",
            file=sys.stderr,
        )
        return 1
    finally:
        if backend_started:
            command(
                ["docker", "rm", "-f", container_name],
                allow_failure=True,
            )
        if db_created and db_container:
            psql(
                db_container,
                "postgres",
                f"DROP DATABASE IF EXISTS {database} WITH (FORCE);",
            )


if __name__ == "__main__":
    raise SystemExit(main())
