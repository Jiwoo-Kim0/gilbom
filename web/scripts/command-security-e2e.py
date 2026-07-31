#!/usr/bin/env python3
"""Run Command permission, scope, CSRF, and audit Browser E2E."""

from __future__ import annotations

import importlib.util
import math
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.error
from typing import Any


ROUNDTRIP_PATH = Path(__file__).with_name("command-roundtrip-e2e.py")
SPEC = importlib.util.spec_from_file_location(
    "gilbom_command_security_roundtrip_support",
    ROUNDTRIP_PATH,
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load command-roundtrip-e2e.py")
roundtrip = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = roundtrip
SPEC.loader.exec_module(roundtrip)
support = roundtrip.support


def authenticate_security_user(
    db_container: str,
    database: str,
    robot_id: str,
    owner_user_id: str,
    suffix: str,
    label: str,
    assignment_type: str,
    *,
    deny_execute: bool = False,
) -> tuple[Any, str]:
    email = f"command-security-{label}-{suffix}@example.invalid"
    password = "Command9!" + secrets.token_hex(6)
    client = support.ApiClient()
    support.expect_status(
        client.request(
            "POST",
            "/api/v1/auth/registrations",
            {
                "email": email,
                "password": password,
                "displayName": f"Command Security {label}",
            },
            csrf=True,
        ),
        201,
        f"{label}_registration",
    )
    user_id = support.psql(
        db_container,
        database,
        f"""
        SELECT user_id
        FROM auth.admin_user
        WHERE normalized_email = LOWER({support.sql_literal(email)});
        """,
    )
    support.ensure(bool(user_id), f"{label}_registered_user")

    deny_sql = ""
    if deny_execute:
        deny_sql = f"""
        INSERT INTO auth.user_permission_override (
            user_id,
            permission_id,
            override_effect,
            reason,
            granted_by
        )
        SELECT
            {support.sql_literal(user_id)}::uuid,
            permission_id,
            'DENY',
            'command security e2e',
            {support.sql_literal(owner_user_id)}::uuid
        FROM auth.permission
        WHERE permission_code = 'ROBOT_COMMAND_EXECUTE';
        """

    support.psql(
        db_container,
        database,
        f"""
        BEGIN;
        UPDATE auth.admin_user
        SET account_status = 'ACTIVE',
            is_active = TRUE,
            password_change_required = FALSE
        WHERE user_id = {support.sql_literal(user_id)}::uuid;

        UPDATE auth.user_role
        SET is_primary = FALSE
        WHERE user_id = {support.sql_literal(user_id)}::uuid
          AND is_primary = TRUE;

        INSERT INTO auth.user_role (
            user_id, role_id, is_primary, is_active, assigned_by
        )
        SELECT
            {support.sql_literal(user_id)}::uuid,
            role_id,
            TRUE,
            TRUE,
            {support.sql_literal(owner_user_id)}::uuid
        FROM auth.role
        WHERE role_code = 'OPERATOR'
        ON CONFLICT (user_id, role_id) DO UPDATE
        SET is_primary = TRUE,
            is_active = TRUE,
            ended_at = NULL;

        INSERT INTO auth.user_robot_assignment (
            user_id,
            robot_id,
            assignment_type,
            reason,
            assigned_by
        ) VALUES (
            {support.sql_literal(user_id)}::uuid,
            {support.sql_literal(robot_id)}::uuid,
            {support.sql_literal(assignment_type)},
            'command security e2e',
            {support.sql_literal(owner_user_id)}::uuid
        );

        {deny_sql}
        COMMIT;
        """,
    )

    login = support.expect_status(
        client.request(
            "POST",
            "/api/v1/auth/login",
            {"email": email, "password": password},
            csrf=True,
        ),
        202,
        f"{label}_login",
    )
    support.ensure(
        isinstance(login, dict)
        and login.get("loginState") == "MFA_ENROLLMENT_REQUIRED",
        f"{label}_mfa_enrollment_state",
    )
    client.issue_csrf()
    enrollment = support.expect_status(
        client.request(
            "POST",
            "/api/v1/auth/mfa/totp/enroll",
            csrf=True,
        ),
        200,
        f"{label}_mfa_enrollment",
    )
    manual_key = enrollment.get("manualEntryKey")
    support.ensure(
        isinstance(manual_key, str) and bool(manual_key),
        f"{label}_mfa_key",
    )
    confirmation = support.expect_status(
        client.request(
            "POST",
            "/api/v1/auth/mfa/totp/confirm",
            {
                "code": support.totp(
                    support.decode_totp_secret(manual_key),
                    math.floor(time.time() / 30),
                )
            },
            csrf=True,
        ),
        200,
        f"{label}_mfa_confirmation",
    )
    support.ensure(
        isinstance(confirmation, dict)
        and isinstance(confirmation.get("csrfToken"), str),
        f"{label}_mfa_confirmation_contract",
    )
    client.csrf_token = confirmation["csrfToken"]
    return client, user_id


def run_playwright(
    robot_id: str,
    denied_session: str,
    viewer_session: str,
    operator_session: str,
) -> None:
    environment = os.environ.copy()
    environment.update({
        "COMMAND_SECURITY_E2E_FRONTEND_URL": roundtrip.FRONTEND_URL,
        "COMMAND_SECURITY_E2E_ROBOT_ID": robot_id,
        "COMMAND_SECURITY_E2E_DENIED_SESSION": denied_session,
        "COMMAND_SECURITY_E2E_VIEWER_SESSION": viewer_session,
        "COMMAND_SECURITY_E2E_OPERATOR_SESSION": operator_session,
    })
    npx = "npx.cmd" if os.name == "nt" else "npx"
    completed = subprocess.run(
        [
            npx,
            "playwright",
            "test",
            "e2e/command-security-live.spec.ts",
            "--config=playwright.live.config.ts",
            "--output=test-results/command-security-live",
        ],
        cwd=roundtrip.FRONTEND_DIR,
        env=environment,
        check=False,
    )
    support.ensure(
        completed.returncode == 0,
        "playwright_command_security_live",
    )


def verify_database(
    db_container: str,
    database: str,
    robot_id: str,
    denied_user_id: str,
    viewer_user_id: str,
    operator_user_id: str,
) -> None:
    evidence = support.psql(
        db_container,
        database,
        f"""
        SELECT
            (
                SELECT COUNT(*)
                FROM robot.robot_command command_record
                WHERE command_record.robot_id =
                    {support.sql_literal(robot_id)}::uuid
            )
            || '|' || (
                SELECT COUNT(*)
                FROM robot.robot_command command_record
                WHERE command_record.robot_id =
                    {support.sql_literal(robot_id)}::uuid
                  AND command_record.requested_by_user_id =
                    {support.sql_literal(operator_user_id)}::uuid
                  AND command_record.command_type = 'START_MISSION'
                  AND command_record.command_status = 'COMPLETED'
                  AND command_record.publish_attempt_count = 1
            )
            || '|' || (
                SELECT COUNT(*)
                FROM robot.robot_command command_record
                WHERE command_record.requested_by_user_id IN (
                    {support.sql_literal(denied_user_id)}::uuid,
                    {support.sql_literal(viewer_user_id)}::uuid
                )
            )
            || '|' || (
                SELECT COUNT(*)
                FROM audit.audit_log audit_record
                JOIN robot.robot_command command_record
                  ON command_record.command_id = audit_record.target_id
                WHERE audit_record.action_type = 'ROBOT_COMMAND_ISSUED'
                  AND audit_record.result = 'SUCCESS'
                  AND audit_record.actor_user_id =
                    {support.sql_literal(operator_user_id)}::uuid
                  AND command_record.robot_id =
                    {support.sql_literal(robot_id)}::uuid
            )
            || '|' || (
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE action_type = 'AUTH_CSRF_FAILED'
                  AND result = 'DENIED'
                  AND actor_user_id =
                    {support.sql_literal(operator_user_id)}::uuid
                  AND after_value ->> 'requestPath' =
                    {support.sql_literal(
                        f"/api/v1/robots/{robot_id}/commands"
                    )}
            )
            || '|' || (
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE action_type = 'AUTH_ACCESS_DENIED'
                  AND result = 'DENIED'
                  AND actor_user_id =
                    {support.sql_literal(denied_user_id)}::uuid
                  AND after_value ->> 'requestPath' =
                    {support.sql_literal(
                        f"/api/v1/robots/{robot_id}/commands"
                    )}
            )
            || '|' || (
                SELECT COUNT(*)
                FROM request_control.idempotency_request
                WHERE request_path =
                    {support.sql_literal(
                        f"/api/v1/robots/{robot_id}/commands"
                    )}
                  AND request_method = 'POST'
            );
        """,
    )
    support.ensure(
        evidence.split("|") == ["1", "1", "0", "1", "2", "1", "1"],
        "command_security_database_evidence",
    )


def main() -> int:
    suffix = secrets.token_hex(5)
    database = "gilbom_command_security_e2e_" + suffix
    temporary_backend = "gilbom-command-security-e2e-" + suffix
    db_container = ""
    main_backend_was_running = False
    database_created = False
    temporary_backend_started = False
    mock_process: subprocess.Popen[str] | None = None
    frontend_process: subprocess.Popen[str] | None = None
    mock_output = ""
    frontend_output = ""

    try:
        db_container = support.compose("ps", "-q", "db")
        support.ensure(bool(db_container), "database_container")
        mqtt_init = support.compose("ps", "-aq", "mqtt-init")
        support.ensure(bool(mqtt_init), "mqtt_init_container")
        support.ensure(
            bool(support.compose("ps", "--status", "running", "-q", "mosquitto")),
            "mosquitto_running",
        )
        mqtt_environment = support.container_environment(mqtt_init)
        mqtt_id = mqtt_environment.get("MQTT_ROBOT_USERNAME", "")
        mqtt_password = mqtt_environment.get("MQTT_ROBOT_PASSWORD", "")
        support.ensure(
            bool(mqtt_id) and bool(mqtt_password),
            "mqtt_robot_credentials",
        )
        support.ensure(
            support.CA_CERTIFICATE.is_file(),
            "mqtt_ca_certificate",
        )

        if os.getenv("COMMAND_SECURITY_E2E_SKIP_BACKEND_BUILD") != "1":
            print("[command-security-e2e] building the current backend image")
            support.compose("build", "backend")
        main_backend_was_running = bool(
            support.compose("ps", "--status", "running", "-q", "backend")
        )
        if main_backend_was_running:
            print(
                "[command-security-e2e] stopping the normal backend "
                "temporarily"
            )
            support.compose("stop", "backend")

        db_environment = support.container_environment(db_container)
        flyway_user = db_environment["POSTGRES_FLYWAY_USER"]
        app_user = db_environment["POSTGRES_APP_USER"]
        support.ensure(
            flyway_user.replace("_", "").isalnum()
            and app_user.replace("_", "").isalnum(),
            "database_role_names",
        )
        support.psql(
            db_container,
            "postgres",
            f"CREATE DATABASE {database} OWNER {flyway_user};",
        )
        database_created = True
        support.psql(
            db_container,
            database,
            f"""
            CREATE EXTENSION IF NOT EXISTS pgcrypto;
            CREATE EXTENSION IF NOT EXISTS postgis;
            GRANT CONNECT ON DATABASE {database} TO {app_user};
            """,
        )

        support.compose(
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
        support.wait_for_health(temporary_backend)
        frontend_process = roundtrip.start_frontend()
        roundtrip.wait_for_frontend(frontend_process)

        owner_client, robot_id = support.register_and_authenticate(
            db_container,
            database,
            mqtt_id,
            suffix,
        )
        owner_user_id = support.psql(
            db_container,
            database,
            f"""
            SELECT registered_by
            FROM robot.robot
            WHERE robot_id = {support.sql_literal(robot_id)}::uuid;
            """,
        )
        support.ensure(bool(owner_user_id), "owner_user_id")
        roundtrip.create_command_fixture(
            owner_client,
            robot_id,
            suffix,
        )

        denied_client, denied_user_id = authenticate_security_user(
            db_container,
            database,
            robot_id,
            owner_user_id,
            suffix,
            "denied",
            "OPERATOR",
            deny_execute=True,
        )
        viewer_client, viewer_user_id = authenticate_security_user(
            db_container,
            database,
            robot_id,
            owner_user_id,
            suffix,
            "viewer",
            "VIEWER",
        )
        operator_client, operator_user_id = authenticate_security_user(
            db_container,
            database,
            robot_id,
            owner_user_id,
            suffix,
            "operator",
            "OPERATOR",
        )

        mock_process = roundtrip.start_mock_robot(mqtt_id, mqtt_password)
        support.wait_for_robot_online(
            db_container,
            database,
            robot_id,
            mock_process,
        )
        print(
            "[command-security-e2e] running permission, Robot scope, "
            "CSRF, idempotency, and audit through Chromium"
        )
        run_playwright(
            robot_id,
            denied_client.session_cookie(),
            viewer_client.session_cookie(),
            operator_client.session_cookie(),
        )
        verify_database(
            db_container,
            database,
            robot_id,
            denied_user_id,
            viewer_user_id,
            operator_user_id,
        )
        print(
            "[command-security-e2e] PASS: anonymous 401, permission 403, "
            "VIEWER scope 404, CSRF denials, one idempotent command, and "
            "required audit evidence"
        )
        return 0
    except (
        support.E2EFailure,
        KeyError,
        OSError,
        subprocess.SubprocessError,
        urllib.error.URLError,
    ) as error:
        print(f"[command-security-e2e] FAIL: {error}", file=sys.stderr)
        if temporary_backend_started:
            logs = support.command(
                ["docker", "logs", "--tail", "160", temporary_backend],
                allow_failure=True,
            )
            if logs:
                print(
                    "[command-security-e2e] temporary backend log tail:\n"
                    + logs,
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
        if frontend_process is not None:
            if frontend_process.poll() is None:
                frontend_process.terminate()
            try:
                frontend_output, _ = frontend_process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                frontend_process.kill()
                frontend_output, _ = frontend_process.communicate(timeout=5)
        support.command(
            ["docker", "rm", "-f", temporary_backend],
            allow_failure=True,
        )
        if database_created and db_container:
            support.psql(
                db_container,
                "postgres",
                f"DROP DATABASE IF EXISTS {database} WITH (FORCE);",
            )
        if main_backend_was_running:
            support.compose(
                "up",
                "-d",
                "--no-deps",
                "--force-recreate",
                "backend",
                allow_failure=True,
            )
            try:
                support.wait_for_health(
                    support.compose("ps", "-q", "backend"),
                    timeout_seconds=90,
                )
            except (support.E2EFailure, urllib.error.URLError):
                print(
                    "[command-security-e2e] WARNING: normal backend "
                    "restart health check failed",
                    file=sys.stderr,
                )
        if mock_output and "[command-security-e2e] FAIL" in mock_output:
            print(mock_output, file=sys.stderr)
        if frontend_output and "error" in frontend_output.lower():
            print(
                "[command-security-e2e] frontend output:\n"
                + frontend_output[-2000:],
                file=sys.stderr,
            )


if __name__ == "__main__":
    raise SystemExit(main())
