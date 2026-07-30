#!/usr/bin/env python3
"""Run six normal Command flows through REST, TLS MQTT, DB, SSE, and React."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
from typing import Any


PROJECT_DIR = Path(__file__).resolve().parents[1]
FRONTEND_DIR = PROJECT_DIR / "frontend" / "gilbom"
FRONTEND_URL = "http://127.0.0.1:4173"
SUPPORT_PATH = Path(__file__).with_name("mission-mock-e2e.py")

SPEC = importlib.util.spec_from_file_location(
    "gilbom_mission_mock_e2e_support",
    SUPPORT_PATH,
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load mission-mock-e2e.py")
support = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = support
SPEC.loader.exec_module(support)


def create_command_fixture(
    client: Any,
    robot_id: str,
    suffix: str,
) -> dict[str, str]:
    area_code = "AREA-COMMAND-" + suffix.upper()
    area = support.expect_status(
        client.request(
            "POST",
            "/api/v1/patrol-areas",
            {
                "areaCode": area_code,
                "areaName": "Command Roundtrip E2E Area",
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
    routes = support.expect_status(
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
    support.ensure(autonomous is not None, "autonomous_default_route")
    mission = support.expect_status(
        client.request(
            "POST",
            "/api/v1/missions",
            {
                "robotId": robot_id,
                "patrolAreaId": area_id,
                "patrolRouteId": autonomous["patrolRouteId"],
                "plannedDurationSec": 3600,
                "scheduledStartAt": None,
            },
            csrf=True,
        ),
        201,
        "mission_creation",
    )
    mission = support.expect_status(
        client.request(
            "PATCH",
            f"/api/v1/missions/{mission['missionId']}",
            {
                "status": "READY",
            },
            csrf=True,
        ),
        200,
        "mission_ready_transition",
    )
    support.ensure(mission["status"] == "READY", "mission_ready_status")
    return {
        "area_id": area_id,
        "area_code": area_code,
        "mission_id": mission["missionId"],
        "mission_code": mission["missionCode"],
    }


def start_mock_robot(mqtt_id: str, password: str) -> subprocess.Popen[str]:
    environment = os.environ.copy()
    environment["MQTT_PASSWORD"] = password
    return subprocess.Popen(
        [
            sys.executable,
            "-u",
            str(support.MOCK_ROBOT),
            "--mqtt-id",
            mqtt_id,
            "--ca-certificate",
            str(support.CA_CERTIFICATE),
            "--telemetry-interval",
            "0.5",
            "--mission-auto-complete-sec",
            "0",
            "--latitude",
            "37.5013",
            "--longitude",
            "127.0396",
        ],
        cwd=support.PROJECT_DIR,
        env=environment,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def start_frontend() -> subprocess.Popen[str]:
    vite_entrypoint = (
        FRONTEND_DIR
        / "node_modules"
        / "vite"
        / "bin"
        / "vite.js"
    )
    support.ensure(vite_entrypoint.is_file(), "vite_entrypoint")
    return subprocess.Popen(
        [
            "node",
            str(vite_entrypoint),
            "--host",
            "127.0.0.1",
            "--port",
            "4173",
            "--strictPort",
        ],
        cwd=FRONTEND_DIR,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def wait_for_frontend(
    process: subprocess.Popen[str],
    timeout_seconds: int = 60,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            output, _ = process.communicate(timeout=5)
            raise support.E2EFailure(
                "frontend_process\n" + output[-2000:]
            )
        try:
            with urllib.request.urlopen(FRONTEND_URL, timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.25)
    raise support.E2EFailure("frontend_health")


def run_playwright(session_cookie: str, robot_id: str) -> None:
    environment = os.environ.copy()
    environment.update({
        "COMMAND_E2E_SESSION_COOKIE": session_cookie,
        "COMMAND_E2E_ROBOT_ID": robot_id,
        "MISSION_LIVE_FRONTEND_URL": FRONTEND_URL,
    })
    npx = "npx.cmd" if os.name == "nt" else "npx"
    completed = subprocess.run(
        [
            npx,
            "playwright",
            "test",
            "e2e/command-roundtrip-live.spec.ts",
            "--config=playwright.live.config.ts",
            "--output=test-results/command-roundtrip-live",
        ],
        cwd=FRONTEND_DIR,
        env=environment,
        check=False,
    )
    support.ensure(
        completed.returncode == 0,
        "playwright_command_roundtrip_live",
    )


def verify_database(
    db_container: str,
    database: str,
    mission_id: str,
    robot_id: str,
) -> None:
    result = support.psql(
        db_container,
        database,
        f"""
        SELECT
            (
                SELECT COUNT(*)
                FROM robot.robot_command command_record
                WHERE command_record.robot_id =
                    {support.sql_literal(robot_id)}::uuid
                  AND command_record.command_status = 'COMPLETED'
            )
            || '|' || (
                SELECT COUNT(DISTINCT command_type)
                FROM robot.robot_command command_record
                WHERE command_record.robot_id =
                    {support.sql_literal(robot_id)}::uuid
            )
            || '|' || (
                SELECT COUNT(*)
                FROM robot.robot_command_result result_record
                WHERE result_record.robot_id =
                    {support.sql_literal(robot_id)}::uuid
                  AND result_record.result_status = 'ACCEPTED'
            )
            || '|' || (
                SELECT COUNT(*)
                FROM robot.robot_command_result result_record
                WHERE result_record.robot_id =
                    {support.sql_literal(robot_id)}::uuid
                  AND result_record.result_status = 'COMPLETED'
            )
            || '|' || (
                SELECT status
                FROM robot.mission
                WHERE mission_id =
                    {support.sql_literal(mission_id)}::uuid
            )
            || '|' || COALESCE(state.robot_mode, '')
            || '|' || COALESCE(state.mission_status, '')
        FROM telemetry.robot_current_state state
        WHERE state.robot_id = {support.sql_literal(robot_id)}::uuid;
        """,
    )
    parts = result.split("|")
    support.ensure(
        parts == ["6", "6", "6", "6", "CANCELLED", "READY", ""],
        "command_roundtrip_database_evidence",
    )


def main() -> int:
    suffix = secrets.token_hex(5)
    database = "gilbom_command_roundtrip_e2e_" + suffix
    temporary_backend = "gilbom-command-roundtrip-e2e-" + suffix
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

        if os.getenv("COMMAND_ROUNDTRIP_E2E_SKIP_BACKEND_BUILD") != "1":
            print("[command-roundtrip-e2e] building the current backend image")
            support.compose("build", "backend")
        main_backend_was_running = bool(
            support.compose("ps", "--status", "running", "-q", "backend")
        )
        if main_backend_was_running:
            print(
                "[command-roundtrip-e2e] stopping the normal backend "
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
        frontend_process = start_frontend()
        wait_for_frontend(frontend_process)

        client, robot_id = support.register_and_authenticate(
            db_container,
            database,
            mqtt_id,
            suffix,
        )
        fixture = create_command_fixture(client, robot_id, suffix)
        mock_process = start_mock_robot(mqtt_id, mqtt_password)
        support.wait_for_robot_online(
            db_container,
            database,
            robot_id,
            mock_process,
        )
        print(
            "[command-roundtrip-e2e] running six commands through React, "
            "REST, TLS MQTT, DB, and SSE"
        )
        run_playwright(client.session_cookie(), robot_id)
        verify_database(
            db_container,
            database,
            fixture["mission_id"],
            robot_id,
        )
        print(
            "[command-roundtrip-e2e] PASS: START, PAUSE, RESUME, "
            "EMERGENCY_STOP, CLEAR_EMERGENCY_STOP, CANCEL each produced "
            "ACCEPTED and COMPLETED"
        )
        return 0
    except (
        support.E2EFailure,
        KeyError,
        OSError,
        subprocess.SubprocessError,
        urllib.error.URLError,
    ) as error:
        print(f"[command-roundtrip-e2e] FAIL: {error}", file=sys.stderr)
        if temporary_backend_started:
            logs = support.command(
                ["docker", "logs", "--tail", "120", temporary_backend],
                allow_failure=True,
            )
            if logs:
                print(
                    "[command-roundtrip-e2e] temporary backend log tail:\n"
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
                    "[command-roundtrip-e2e] WARNING: normal backend "
                    "restart health check failed",
                    file=sys.stderr,
                )
        if mock_output and "[command-roundtrip-e2e] FAIL" in mock_output:
            print(mock_output, file=sys.stderr)
        if frontend_output and "error" in frontend_output.lower():
            print(
                "[command-roundtrip-e2e] frontend output:\n"
                + frontend_output[-2000:],
                file=sys.stderr,
            )


if __name__ == "__main__":
    raise SystemExit(main())
