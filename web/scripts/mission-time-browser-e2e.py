#!/usr/bin/env python3
"""Run the time-based Mission browser E2E against the live Docker stack."""

from __future__ import annotations

import argparse
import base64
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
import uuid
from typing import Any


class E2EFailure(RuntimeError):
    """Raised when the live E2E precondition or assertion fails."""


class ApiClient:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )
        self.csrf_token: str | None = None

    def issue_csrf(self) -> None:
        status, body = self.request("GET", "/api/v1/auth/csrf-token")
        if status != 200 or not isinstance(body, dict):
            raise E2EFailure("The backend did not issue a CSRF token")
        token = body.get("token")
        if not isinstance(token, str) or not token:
            raise E2EFailure("The CSRF token response was invalid")
        self.csrf_token = token

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        csrf: bool = False,
    ) -> tuple[int, Any]:
        if csrf and self.csrf_token is None:
            self.issue_csrf()

        headers = {
            "Accept": "application/json",
            "User-Agent": "gilbom-mission-time-browser-e2e",
        }
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if csrf:
            headers["X-CSRF-TOKEN"] = str(self.csrf_token)

        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            headers=headers,
            method=method,
        )
        try:
            response = self.opener.open(request, timeout=20)
            raw = response.read()
            status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code

        parsed: Any = None
        if raw:
            try:
                parsed = json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError:
                parsed = raw.decode("utf-8", errors="replace")
        return status, parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--backend-url",
        default="http://127.0.0.1:8080",
    )
    parser.add_argument(
        "--frontend-url",
        default="http://127.0.0.1:3000",
    )
    parser.add_argument("--db-container", default="gilbom-db-1")
    parser.add_argument("--database", default="gilbom")
    parser.add_argument("--db-user", default="gilbom_db_admin")
    return parser.parse_args()


def psql(args: argparse.Namespace, sql: str) -> str:
    result = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            args.db_container,
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            args.db_user,
            "-d",
            args.database,
            "-At",
        ],
        input=sql,
        text=True,
        encoding="utf-8",
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise E2EFailure(
            "PostgreSQL command failed: " + result.stderr.strip()
        )
    return result.stdout.strip()


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


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


def expect_status(
    actual: tuple[int, Any],
    expected: int,
    operation: str,
) -> Any:
    status, body = actual
    if status != expected:
        code = body.get("code") if isinstance(body, dict) else None
        detail = f" ({code})" if isinstance(code, str) else ""
        raise E2EFailure(
            f"{operation} returned HTTP {status}, expected {expected}{detail}"
        )
    return body


def seed_database(
    args: argparse.Namespace,
    *,
    email: str,
    user_id: str,
    robot_id: str,
    area_id: str,
    route_id: str,
    suffix: str,
) -> None:
    psql(
        args,
        f"""
        BEGIN;
        UPDATE auth.admin_user
        SET account_status = 'ACTIVE',
            is_active = TRUE,
            password_change_required = FALSE
        WHERE user_id = {sql_literal(user_id)}::uuid;

        UPDATE auth.user_role
        SET is_primary = FALSE
        WHERE user_id = {sql_literal(user_id)}::uuid
          AND is_primary = TRUE;

        INSERT INTO auth.user_role (
            user_id,
            role_id,
            is_primary,
            is_active,
            assigned_by
        )
        SELECT
            {sql_literal(user_id)}::uuid,
            role_id,
            TRUE,
            TRUE,
            {sql_literal(user_id)}::uuid
        FROM auth.role
        WHERE role_code = 'MANAGER'
          AND is_active = TRUE
        ON CONFLICT (user_id, role_id) DO UPDATE
        SET is_primary = TRUE,
            is_active = TRUE,
            ended_at = NULL;

        INSERT INTO robot.robot (
            robot_id,
            mqtt_id,
            robot_name,
            serial_number,
            operational_status,
            registered_by
        ) VALUES (
            {sql_literal(robot_id)}::uuid,
            {sql_literal("mission-live-" + suffix)},
            'Mission Live E2E Robot',
            {sql_literal("MISSION-LIVE-" + suffix)},
            'ACTIVE',
            {sql_literal(user_id)}::uuid
        );

        INSERT INTO auth.user_robot_assignment (
            user_id,
            robot_id,
            assignment_type,
            reason,
            assigned_by
        ) VALUES (
            {sql_literal(user_id)}::uuid,
            {sql_literal(robot_id)}::uuid,
            'OWNER',
            'time mission live browser e2e',
            {sql_literal(user_id)}::uuid
        );

        INSERT INTO telemetry.robot_current_state (
            robot_id,
            connectivity_status
        ) VALUES (
            {sql_literal(robot_id)}::uuid,
            'OFFLINE'
        );

        INSERT INTO robot.patrol_area (
            patrol_area_id,
            area_code,
            area_name,
            boundary,
            lifecycle_status,
            created_by,
            updated_by
        ) VALUES (
            {sql_literal(area_id)}::uuid,
            {sql_literal("AREA-LIVE-" + suffix)},
            'Mission Live E2E Area',
            ST_GeomFromText(
                'POLYGON((127.00 37.00,127.02 37.00,127.02 37.02,'
                || '127.00 37.02,127.00 37.00))',
                4326
            ),
            'ACTIVE',
            {sql_literal(user_id)}::uuid,
            {sql_literal(user_id)}::uuid
        );

        INSERT INTO robot.patrol_route (
            patrol_route_id,
            patrol_area_id,
            route_code,
            route_name,
            route_version,
            route_geometry,
            lifecycle_status,
            created_by,
            updated_by
        ) VALUES (
            {sql_literal(route_id)}::uuid,
            {sql_literal(area_id)}::uuid,
            'AUTONOMOUS_DEFAULT',
            'Mission Live E2E Autonomous Route',
            1,
            ST_GeomFromText(
                'LINESTRING(127.001 37.001,127.019 37.019)',
                4326
            ),
            'ACTIVE',
            {sql_literal(user_id)}::uuid,
            {sql_literal(user_id)}::uuid
        );
        COMMIT;
        """,
    )
    registered = psql(
        args,
        f"""
        SELECT COUNT(*)
        FROM auth.admin_user
        WHERE user_id = {sql_literal(user_id)}::uuid
          AND normalized_email = LOWER({sql_literal(email)})
          AND account_status = 'ACTIVE';
        """,
    )
    if registered != "1":
        raise E2EFailure("The disposable E2E account was not activated")


def enroll_mfa(
    backend_url: str,
    email: str,
    password: str,
) -> tuple[str, int]:
    client = ApiClient(backend_url)
    login_body = expect_status(
        client.request(
            "POST",
            "/api/v1/auth/login",
            {"email": email, "password": password},
            csrf=True,
        ),
        202,
        "MFA enrollment login",
    )
    if (
        not isinstance(login_body, dict)
        or login_body.get("loginState") != "MFA_ENROLLMENT_REQUIRED"
    ):
        raise E2EFailure("The disposable account did not enter MFA enrollment")

    client.issue_csrf()
    enrollment = expect_status(
        client.request(
            "POST",
            "/api/v1/auth/mfa/totp/enroll",
            csrf=True,
        ),
        200,
        "TOTP enrollment",
    )
    manual_key = (
        enrollment.get("manualEntryKey")
        if isinstance(enrollment, dict)
        else None
    )
    if not isinstance(manual_key, str) or not manual_key:
        raise E2EFailure("The TOTP enrollment response did not contain a key")

    secret = decode_totp_secret(manual_key)
    enrollment_step = math.floor(time.time() / 30)
    confirmation = expect_status(
        client.request(
            "POST",
            "/api/v1/auth/mfa/totp/confirm",
            {"code": totp(secret, enrollment_step)},
            csrf=True,
        ),
        200,
        "TOTP enrollment confirmation",
    )
    if (
        not isinstance(confirmation, dict)
        or not isinstance(confirmation.get("csrfToken"), str)
    ):
        raise E2EFailure("The TOTP confirmation response was invalid")
    return manual_key, enrollment_step


def wait_for_fresh_totp(enrollment_step: int) -> None:
    while math.floor(time.time() / 30) <= enrollment_step:
        time.sleep(0.25)


def run_playwright(
    frontend_dir: Path,
    frontend_url: str,
    *,
    email: str,
    password: str,
    manual_key: str,
    robot_id: str,
    area_id: str,
    route_id: str,
) -> None:
    environment = os.environ.copy()
    environment.update({
        "MISSION_LIVE_FRONTEND_URL": frontend_url,
        "MISSION_LIVE_EMAIL": email,
        "MISSION_LIVE_PASSWORD": password,
        "MISSION_LIVE_TOTP_SECRET": manual_key,
        "MISSION_LIVE_ROBOT_ID": robot_id,
        "MISSION_LIVE_AREA_ID": area_id,
        "MISSION_LIVE_ROUTE_ID": route_id,
    })
    npm = "npm.cmd" if os.name == "nt" else "npm"
    result = subprocess.run(
        [npm, "run", "test:e2e:mission:live"],
        cwd=frontend_dir,
        env=environment,
        check=False,
    )
    if result.returncode != 0:
        raise E2EFailure("Playwright live Mission E2E failed")


def verify_database(
    args: argparse.Namespace,
    *,
    user_id: str,
    robot_id: str,
    area_id: str,
    route_id: str,
) -> str:
    row = psql(
        args,
        f"""
        SELECT
            mission_code
            || '|' || planned_duration_sec
            || '|' || status
            || '|' || progress_percent
        FROM robot.mission
        WHERE created_by = {sql_literal(user_id)}::uuid
          AND robot_id = {sql_literal(robot_id)}::uuid
          AND patrol_area_id = {sql_literal(area_id)}::uuid
          AND patrol_route_id = {sql_literal(route_id)}::uuid;
        """,
    )
    parts = row.split("|")
    if (
        len(parts) != 4
        or not parts[0].startswith("MIS-")
        or parts[1:] != ["2700", "CREATED", "0.00"]
    ):
        raise E2EFailure(
            "The browser-created Mission was not persisted with the expected "
            "time contract"
        )
    return parts[0]


def cleanup_database(
    args: argparse.Namespace,
    *,
    user_id: str | None,
    email: str,
    robot_id: str,
    area_id: str,
) -> None:
    resolved_user = user_id
    if resolved_user is None:
        candidate = psql(
            args,
            f"""
            SELECT user_id
            FROM auth.admin_user
            WHERE normalized_email = LOWER({sql_literal(email)});
            """,
        )
        resolved_user = candidate or None
    if resolved_user is None:
        return

    psql(
        args,
        f"""
        BEGIN;
        ALTER TABLE robot.mission_status_history
            DISABLE TRIGGER tr_mission_status_history_append_only;
        DELETE FROM robot.mission_status_history
        WHERE mission_id IN (
            SELECT mission_id
            FROM robot.mission
            WHERE created_by = {sql_literal(resolved_user)}::uuid
        );
        ALTER TABLE robot.mission_status_history
            ENABLE TRIGGER tr_mission_status_history_append_only;
        DELETE FROM robot.mission
        WHERE created_by = {sql_literal(resolved_user)}::uuid;
        DELETE FROM auth.user_robot_assignment
        WHERE user_id = {sql_literal(resolved_user)}::uuid
          AND robot_id = {sql_literal(robot_id)}::uuid;
        DELETE FROM telemetry.robot_current_state
        WHERE robot_id = {sql_literal(robot_id)}::uuid;
        DELETE FROM robot.patrol_route
        WHERE patrol_area_id = {sql_literal(area_id)}::uuid;
        DELETE FROM robot.patrol_area
        WHERE patrol_area_id = {sql_literal(area_id)}::uuid;
        DELETE FROM robot.robot
        WHERE robot_id = {sql_literal(robot_id)}::uuid;
        DELETE FROM session_store.spring_session
        WHERE primary_id IN (
            SELECT session_primary_id
            FROM session_store.session_security_state
            WHERE user_id = {sql_literal(resolved_user)}::uuid
        );
        ALTER TABLE audit.audit_log
            DISABLE TRIGGER tr_audit_log_append_only;
        DELETE FROM audit.audit_log
        WHERE actor_user_id = {sql_literal(resolved_user)}::uuid;
        ALTER TABLE audit.audit_log
            ENABLE TRIGGER tr_audit_log_append_only;
        DELETE FROM auth.admin_user
        WHERE user_id = {sql_literal(resolved_user)}::uuid;
        COMMIT;
        """,
    )


def main() -> int:
    args = parse_args()
    frontend_dir = (
        Path(__file__).resolve().parents[1] / "frontend" / "gilbom"
    )
    suffix = secrets.token_hex(6)
    email = f"mission-live-{suffix}@example.invalid"
    password = "Mission9!" + secrets.token_hex(5)
    robot_id = str(uuid.uuid4())
    area_id = str(uuid.uuid4())
    route_id = str(uuid.uuid4())
    user_id: str | None = None
    exit_code = 1

    print("[mission-live-e2e] checking Docker frontend and backend")
    try:
        for url in (args.frontend_url, args.backend_url + "/actuator/health"):
            with urllib.request.urlopen(url, timeout=20) as response:
                if response.status != 200:
                    raise E2EFailure(f"Health check failed for {url}")

        registration = ApiClient(args.backend_url)
        expect_status(
            registration.request(
                "POST",
                "/api/v1/auth/registrations",
                {
                    "email": email,
                    "password": password,
                    "displayName": "Mission Live E2E",
                },
                csrf=True,
            ),
            201,
            "Disposable account registration",
        )
        user_id = psql(
            args,
            f"""
            SELECT user_id
            FROM auth.admin_user
            WHERE normalized_email = LOWER({sql_literal(email)});
            """,
        )
        if not user_id:
            raise E2EFailure("The registered disposable account was not found")

        seed_database(
            args,
            email=email,
            user_id=user_id,
            robot_id=robot_id,
            area_id=area_id,
            route_id=route_id,
            suffix=suffix,
        )
        manual_key, enrollment_step = enroll_mfa(
            args.backend_url,
            email,
            password,
        )
        print("[mission-live-e2e] waiting for a non-replayed TOTP time step")
        wait_for_fresh_totp(enrollment_step)
        print("[mission-live-e2e] running real browser login and Mission creation")
        run_playwright(
            frontend_dir,
            args.frontend_url,
            email=email,
            password=password,
            manual_key=manual_key,
            robot_id=robot_id,
            area_id=area_id,
            route_id=route_id,
        )
        mission_code = verify_database(
            args,
            user_id=user_id,
            robot_id=robot_id,
            area_id=area_id,
            route_id=route_id,
        )
        print(
            "[mission-live-e2e] PASS: browser, backend, and PostgreSQL "
            f"confirmed {mission_code} with plannedDurationSec=2700"
        )
        exit_code = 0
    except (
        E2EFailure,
        OSError,
        subprocess.SubprocessError,
        urllib.error.URLError,
    ) as error:
        print(f"[mission-live-e2e] FAIL: {error}", file=sys.stderr)
    finally:
        try:
            cleanup_database(
                args,
                user_id=user_id,
                email=email,
                robot_id=robot_id,
                area_id=area_id,
            )
            print("[mission-live-e2e] disposable data cleaned")
        except (E2EFailure, OSError, subprocess.SubprocessError) as error:
            print(
                f"[mission-live-e2e] CLEANUP FAILED: {error}",
                file=sys.stderr,
            )
            exit_code = 1
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
