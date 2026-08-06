#!/usr/bin/env python3
"""Run Mission REST -> TLS MQTT -> Mock -> DB/SSE -> React E2E.

The harness stops only the normal local backend, creates an ephemeral database,
binds a temporary backend to the same localhost:8080 port, runs the real Mock
Robot and Chromium scenario, and restores the normal backend in a finally block.
The normal local database is never used for the test flow.
"""

from __future__ import annotations

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


PROJECT_DIR = Path(__file__).resolve().parents[1]
FRONTEND_DIR = PROJECT_DIR / "frontend" / "gilbom"
MOCK_ROBOT = PROJECT_DIR / "tools" / "mock-robot" / "mock_robot.py"
CA_CERTIFICATE = (
    PROJECT_DIR
    / "infra"
    / "mosquitto"
    / "local"
    / "runtime"
    / "certs"
    / "local-ca-cert.pem"
)
BACKEND_URL = "http://127.0.0.1:8080"
SESSION_COOKIE_NAME = "GILBOM_SESSION"


class E2EFailure(RuntimeError):
    pass


def ensure(condition: bool, check: str) -> None:
    if not condition:
        raise E2EFailure(check)


def command(
    arguments: list[str],
    *,
    input_text: str | None = None,
    allow_failure: bool = False,
    environment: dict[str, str] | None = None,
    cwd: Path = PROJECT_DIR,
) -> str:
    completed = subprocess.run(
        arguments,
        cwd=cwd,
        env=environment,
        input=input_text,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0 and not allow_failure:
        detail = completed.stderr.strip().splitlines()
        suffix = f": {detail[-1]}" if detail else ""
        raise E2EFailure("command_failed_" + arguments[0] + suffix)
    return completed.stdout.strip()


def compose(*arguments: str, allow_failure: bool = False) -> str:
    return command(
        ["docker", "compose", *arguments],
        allow_failure=allow_failure,
    )


def container_environment(container: str) -> dict[str, str]:
    inspected = json.loads(command(["docker", "inspect", container]))
    entries = inspected[0]["Config"]["Env"]
    return {
        key: value
        for key, value in (
            entry.split("=", 1)
            for entry in entries
            if "=" in entry
        )
    }


def psql(
    container: str,
    database: str,
    sql: str,
    *,
    tuples_only: bool = True,
) -> str:
    formatting = "--tuples-only --no-align --quiet" if tuples_only else ""
    shell = (
        'PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")" psql '
        '--host 127.0.0.1 --username "$POSTGRES_USER" '
        f'--dbname "$1" --no-password --set ON_ERROR_STOP=1 {formatting}'
    )
    return command(
        ["docker", "exec", "-i", container, "sh", "-c", shell, "sh", database],
        input_text=sql,
    )


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


class ApiClient:
    def __init__(self) -> None:
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )
        self.csrf_token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        csrf: bool = False,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, Any]:
        if csrf and self.csrf_token is None:
            self.issue_csrf()
        headers = {
            "Accept": "application/json",
            "User-Agent": "gilbom-mission-mock-e2e",
        }
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if csrf:
            headers["X-CSRF-TOKEN"] = str(self.csrf_token)
        if extra_headers is not None:
            headers.update(extra_headers)
        request = urllib.request.Request(
            BACKEND_URL + path,
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

    def issue_csrf(self) -> None:
        status, body = self.request("GET", "/api/v1/auth/csrf-token")
        ensure(status == 200 and isinstance(body, dict), "csrf_response")
        token = body.get("token")
        ensure(isinstance(token, str) and bool(token), "csrf_token")
        self.csrf_token = token

    def session_cookie(self) -> str:
        for cookie in self.cookies:
            if cookie.name == SESSION_COOKIE_NAME:
                return cookie.value
        raise E2EFailure("authenticated_session_cookie")


def expect_status(
    result: tuple[int, Any],
    expected: int,
    operation: str,
) -> Any:
    status, body = result
    if status != expected:
        code = body.get("code") if isinstance(body, dict) else None
        raise E2EFailure(
            f"{operation}_http_{status}"
            + (f"_{code}" if isinstance(code, str) else "")
        )
    return body


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


def wait_for_health(container: str, timeout_seconds: int = 90) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(
                BACKEND_URL + "/actuator/health/readiness",
                timeout=3,
            ) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        running = command(
            ["docker", "inspect", "-f", "{{.State.Running}}", container],
            allow_failure=True,
        )
        if running == "false":
            break
        time.sleep(1)
    raise E2EFailure("temporary_backend_health")


def register_and_authenticate(
    db_container: str,
    database: str,
    mqtt_id: str,
    suffix: str,
) -> tuple[ApiClient, str, str]:
    email = f"mission-mock-{suffix}@example.invalid"
    password = "Mission9!" + secrets.token_hex(6)
    client = ApiClient()
    expect_status(
        client.request(
            "POST",
            "/api/v1/auth/registrations",
            {
                "email": email,
                "password": password,
                "displayName": "Mission Mock E2E",
            },
            csrf=True,
        ),
        201,
        "registration",
    )
    user_id = psql(
        db_container,
        database,
        f"""
        SELECT user_id
        FROM auth.admin_user
        WHERE normalized_email = LOWER({sql_literal(email)});
        """,
    )
    ensure(bool(user_id), "registered_user")
    robot_id = str(uuid.uuid4())
    psql(
        db_container,
        database,
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
            user_id, role_id, is_primary, is_active, assigned_by
        )
        SELECT
            {sql_literal(user_id)}::uuid,
            role_id,
            TRUE,
            TRUE,
            {sql_literal(user_id)}::uuid
        FROM auth.role
        -- MQTT Credential 발급은 ROBOT_REGISTER가 필요한 전역 관리 작업이다.
        -- 전체 E2E 계정은 제품 권한 계약과 동일하게 ADMIN 역할로 실행한다.
        WHERE role_code = 'ADMIN'
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
            {sql_literal(mqtt_id)},
            'Mission Mock E2E Robot',
            {sql_literal("MISSION-MOCK-" + suffix)},
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
            'mission mock e2e',
            {sql_literal(user_id)}::uuid
        );

        INSERT INTO telemetry.robot_current_state (
            robot_id,
            connectivity_status
        ) VALUES (
            {sql_literal(robot_id)}::uuid,
            'OFFLINE'
        );
        COMMIT;
        """,
    )

    login = expect_status(
        client.request(
            "POST",
            "/api/v1/auth/login",
            {"email": email, "password": password},
            csrf=True,
        ),
        202,
        "login",
    )
    ensure(
        isinstance(login, dict)
        and login.get("loginState") == "MFA_ENROLLMENT_REQUIRED",
        "mfa_enrollment_state",
    )
    client.issue_csrf()
    enrollment = expect_status(
        client.request(
            "POST",
            "/api/v1/auth/mfa/totp/enroll",
            csrf=True,
        ),
        200,
        "mfa_enrollment",
    )
    manual_key = enrollment.get("manualEntryKey")
    ensure(isinstance(manual_key, str) and bool(manual_key), "mfa_key")
    confirmation = expect_status(
        client.request(
            "POST",
            "/api/v1/auth/mfa/totp/confirm",
            {
                "code": totp(
                    decode_totp_secret(manual_key),
                    math.floor(time.time() / 30),
                )
            },
            csrf=True,
        ),
        200,
        "mfa_confirmation",
    )
    ensure(
        isinstance(confirmation, dict)
        and isinstance(confirmation.get("csrfToken"), str),
        "mfa_confirmation_contract",
    )
    client.csrf_token = confirmation["csrfToken"]
    credential = expect_status(
        client.request(
            "POST",
            f"/api/v1/robots/{robot_id}/credentials",
            csrf=True,
            extra_headers={"Idempotency-Key": str(uuid.uuid4())},
        ),
        201,
        "mqtt_credential_issuance",
    )
    mqtt = credential.get("mqtt") if isinstance(credential, dict) else None
    ensure(
        isinstance(mqtt, dict)
        and mqtt.get("username") == mqtt_id
        and isinstance(mqtt.get("password"), str)
        and bool(mqtt["password"]),
        "mqtt_credential_contract",
    )
    return client, robot_id, mqtt["password"]


def create_mission_fixture(
    client: ApiClient,
    robot_id: str,
    suffix: str,
) -> dict[str, str]:
    area_code = "AREA-MOCK-" + suffix.upper()
    area = expect_status(
        client.request(
            "POST",
            "/api/v1/patrol-areas",
            {
                "areaCode": area_code,
                "areaName": "Mission Mock E2E Area",
                "boundary": {
                    "type": "Polygon",
                    "coordinates": [[
                        [127.03, 37.49],
                        [127.05, 37.49],
                        [127.05, 37.51],
                        [127.03, 37.51],
                        [127.03, 37.49],
                    ]],
                },
                "lifecycleStatus": "ACTIVE",
            },
            csrf=True,
        ),
        201,
        "patrol_area_creation",
    )
    area_id = area["patrolAreaId"]
    routes = expect_status(
        client.request(
            "GET",
            f"/api/v1/patrol-areas/{area_id}/routes",
        ),
        200,
        "autonomous_route_query",
    )
    autonomous = next(
        (
            route
            for route in routes["routes"]
            if route["routeCode"] == "AUTONOMOUS_DEFAULT"
        ),
        None,
    )
    ensure(autonomous is not None, "autonomous_default_route")
    mission = expect_status(
        client.request(
            "POST",
            "/api/v1/missions",
            {
                "robotId": robot_id,
                "patrolAreaId": area_id,
                "patrolRouteId": autonomous["patrolRouteId"],
                "plannedDurationSec": 12,
                "scheduledStartAt": None,
            },
            csrf=True,
        ),
        201,
        "mission_creation",
    )
    return {
        "area_code": area_code,
        "mission_id": mission["missionId"],
        "mission_code": mission["missionCode"],
    }


def start_mock_robot(
    mqtt_id: str,
    password: str,
) -> subprocess.Popen[str]:
    environment = os.environ.copy()
    environment["MQTT_PASSWORD"] = password
    return subprocess.Popen(
        [
            sys.executable,
            "-u",
            str(MOCK_ROBOT),
            "--mqtt-id",
            mqtt_id,
            "--ca-certificate",
            str(CA_CERTIFICATE),
            "--telemetry-interval",
            "0.5",
            "--position-step-m",
            "1.5",
            "--position-update-interval",
            "0.5",
            "--normal-detection-step",
            "1",
            "--incident-event-interval",
            "1.5",
            "--mission-auto-complete-sec",
            "12",
            "--latitude",
            "37.5013",
            "--longitude",
            "127.0396",
        ],
        cwd=PROJECT_DIR,
        env=environment,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def wait_for_robot_online(
    db_container: str,
    database: str,
    robot_id: str,
    mock_process: subprocess.Popen[str],
) -> None:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if mock_process.poll() is not None:
            raise E2EFailure("mock_robot_exited")
        count = psql(
            db_container,
            database,
            f"""
            SELECT COUNT(*)
            FROM telemetry.robot_current_state
            WHERE robot_id = {sql_literal(robot_id)}::uuid
              AND connectivity_status = 'ONLINE';
            """,
        )
        if count == "1":
            return
        time.sleep(0.25)
    raise E2EFailure("mock_robot_online")


def run_playwright(
    session_cookie: str,
    robot_id: str,
    fixture: dict[str, str],
) -> None:
    environment = os.environ.copy()
    environment.update({
        "MISSION_E2E_SESSION_COOKIE": session_cookie,
        "MISSION_E2E_ROBOT_ID": robot_id,
        "MISSION_E2E_MISSION_ID": fixture["mission_id"],
        "MISSION_E2E_MISSION_CODE": fixture["mission_code"],
        "MISSION_E2E_AREA_CODE": fixture["area_code"],
    })
    npx = "npx.cmd" if os.name == "nt" else "npx"
    completed = subprocess.run(
        [
            npx,
            "playwright",
            "test",
            "e2e/mission-live.spec.ts",
            "--config=playwright.config.ts",
            "--output=test-results/mission-mock-live",
        ],
        cwd=FRONTEND_DIR,
        env=environment,
        check=False,
    )
    ensure(completed.returncode == 0, "playwright_mission_live")


def verify_database(
    db_container: str,
    database: str,
    mission_id: str,
    robot_id: str,
) -> None:
    result = psql(
        db_container,
        database,
        f"""
        SELECT
            status
            || '|' || progress_percent
            || '|' || COALESCE(completion_reason, '')
            || '|' || (
                SELECT COUNT(*)
                FROM robot.mission_status_history history
                WHERE history.mission_id = mission.mission_id
            )
            || '|' || (
                SELECT COUNT(*)
                FROM telemetry.robot_telemetry trajectory
                WHERE trajectory.mission_id = mission.mission_id
            )
            || '|' || (
                SELECT COUNT(*)
                FROM robot.robot_command robot_command
                WHERE robot_command.robot_id =
                    {sql_literal(robot_id)}::uuid
                  AND robot_command.command_status = 'COMPLETED'
            )
            || '|' || COALESCE(result_incident_count::TEXT, '')
            || '|' || COALESCE(result_normal_detection_count::TEXT, '')
        FROM robot.mission mission
        WHERE mission_id = {sql_literal(mission_id)}::uuid;
        """,
    )
    parts = result.split("|")
    ensure(len(parts) == 8, "database_evidence_shape")
    ensure(parts[:4] == [
        "COMPLETED",
        "100.00",
        "MOCK_PLANNED_DURATION_REACHED",
        "5",
    ], "mission_completion_evidence")
    ensure(int(parts[4]) > 0, "mission_trajectory_evidence")
    ensure(parts[5] == "3", "command_completion_evidence")
    ensure(int(parts[6]) > 0, "mission_incident_count_evidence")
    ensure(int(parts[7]) > 0, "mission_normal_detection_count_evidence")

    defect_types = psql(
        db_container,
        database,
        f"""
        SELECT COALESCE(
            STRING_AGG(DISTINCT defect.defect_type, ','),
            ''
        )
        FROM defect.defect defect
        JOIN defect.defect_observation observation
          ON observation.defect_id = defect.defect_id
        JOIN telemetry.robot_event event
          ON event.event_id = observation.event_id
         AND event.robot_id = observation.robot_id
        WHERE event.mission_id = {sql_literal(mission_id)}::uuid;
        """,
    )
    ensure(
        set(defect_types.split(",")) == {"DAMAGED", "MISSING"},
        "mission_defect_types_evidence",
    )


def main() -> int:
    suffix = secrets.token_hex(5)
    database = "gilbom_mission_mock_e2e_" + suffix
    temporary_backend = "gilbom-mission-mock-e2e-" + suffix
    db_container = ""
    main_backend_was_running = False
    database_created = False
    temporary_backend_started = False
    mock_process: subprocess.Popen[str] | None = None
    mock_output = ""

    try:
        db_container = compose("ps", "-q", "db")
        ensure(bool(db_container), "database_container")
        ensure(
            bool(compose("ps", "--status", "running", "-q", "mosquitto")),
            "mosquitto_running",
        )
        mqtt_id = "robot-test-" + suffix
        ensure(CA_CERTIFICATE.is_file(), "mqtt_ca_certificate")

        if os.getenv("MISSION_MOCK_E2E_SKIP_BACKEND_BUILD") != "1":
            print("[mission-mock-e2e] building the current backend image")
            compose("build", "backend")
        main_backend_was_running = bool(
            compose("ps", "--status", "running", "-q", "backend")
        )
        if main_backend_was_running:
            print("[mission-mock-e2e] stopping the normal backend temporarily")
            compose("stop", "backend")

        db_environment = container_environment(db_container)
        flyway_user = db_environment["POSTGRES_FLYWAY_USER"]
        app_user = db_environment["POSTGRES_APP_USER"]
        ensure(
            flyway_user.replace("_", "").isalnum()
            and app_user.replace("_", "").isalnum(),
            "database_role_names",
        )
        psql(
            db_container,
            "postgres",
            f"CREATE DATABASE {database} OWNER {flyway_user};",
        )
        database_created = True
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
            temporary_backend,
            "-p",
            "127.0.0.1:8080:8080",
            "-e",
            f"SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/{database}",
            "-e",
            f"MQTT_CLIENT_ID={temporary_backend}",
            "-e",
            "INITIAL_ADMIN_BOOTSTRAP_ENABLED=false",
            "backend",
        )
        temporary_backend_started = True
        wait_for_health(temporary_backend)

        client, robot_id, mqtt_password = register_and_authenticate(
            db_container,
            database,
            mqtt_id,
            suffix,
        )
        fixture = create_mission_fixture(client, robot_id, suffix)
        mock_process = start_mock_robot(mqtt_id, mqtt_password)
        wait_for_robot_online(
            db_container,
            database,
            robot_id,
            mock_process,
        )
        print(
            "[mission-mock-e2e] running Chromium through REST, "
            "TLS MQTT, DB, SSE, and React"
        )
        run_playwright(
            client.session_cookie(),
            robot_id,
            fixture,
        )
        verify_database(
            db_container,
            database,
            fixture["mission_id"],
            robot_id,
        )
        print(
            "[mission-mock-e2e] PASS: CREATED -> RUNNING -> PAUSED -> "
            "RUNNING -> COMPLETED, progress 100, history 5, trajectory, "
            "DAMAGED/MISSING defects, detection counts, and three completed "
            "commands"
        )
        return 0
    except (
        E2EFailure,
        KeyError,
        OSError,
        subprocess.SubprocessError,
        urllib.error.URLError,
    ) as error:
        print(f"[mission-mock-e2e] FAIL: {error}", file=sys.stderr)
        if temporary_backend_started:
            logs = command(
                ["docker", "logs", "--tail", "100", temporary_backend],
                allow_failure=True,
            )
            if logs:
                print(
                    "[mission-mock-e2e] temporary backend log tail:\n" + logs,
                    file=sys.stderr,
                )
        return 1
    finally:
        if mock_process is not None:
            if mock_process.poll() is None:
                mock_process.terminate()
            try:
                mock_output, _ = mock_process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                mock_process.kill()
                mock_output, _ = mock_process.communicate(timeout=5)
        if temporary_backend_started:
            command(
                ["docker", "rm", "-f", temporary_backend],
                allow_failure=True,
            )
        if database_created and db_container:
            psql(
                db_container,
                "postgres",
                f"DROP DATABASE IF EXISTS {database} WITH (FORCE);",
            )
        if main_backend_was_running:
            compose("start", "backend", allow_failure=True)
            try:
                wait_for_health(
                    compose("ps", "-q", "backend"),
                    timeout_seconds=90,
                )
            except (E2EFailure, urllib.error.URLError):
                print(
                    "[mission-mock-e2e] WARNING: normal backend restart "
                    "health check failed",
                    file=sys.stderr,
                )
        if mock_output and "[mission-mock-e2e] FAIL" in mock_output:
            print(mock_output, file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
