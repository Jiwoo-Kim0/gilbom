#!/usr/bin/env python3
"""Run abnormal Command states through REST, TLS MQTT, DB, SSE, and React."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from typing import Any

import paho.mqtt.client as mqtt


ROUNDTRIP_PATH = Path(__file__).with_name("command-roundtrip-e2e.py")
SPEC = importlib.util.spec_from_file_location(
    "gilbom_command_roundtrip_e2e_support",
    ROUNDTRIP_PATH,
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load command-roundtrip-e2e.py")
roundtrip = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = roundtrip
SPEC.loader.exec_module(roundtrip)
support = roundtrip.support


class ResultObserver:

    def __init__(
        self,
        mqtt_id: str,
        username: str,
        password: str,
        suffix: str,
    ) -> None:
        self.mqtt_id = mqtt_id
        self.result_topic = (
            f"tbs/v2/robots/{mqtt_id}/commands/result"
        )
        self.request_topic = (
            f"tbs/v2/robots/{mqtt_id}/commands/request"
        )
        self.connected = threading.Event()
        self.subscribed = threading.Event()
        self.condition = threading.Condition()
        self.messages: list[dict[str, Any]] = []
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"command-abnormal-observer-{suffix}",
            protocol=mqtt.MQTTv5,
        )
        self.client.username_pw_set(username, password)
        self.client.tls_set_context(
            ssl.create_default_context(
                cafile=str(support.CA_CERTIFICATE.resolve())
            )
        )
        self.client.on_connect = self._on_connect
        self.client.on_subscribe = self._on_subscribe
        self.client.on_message = self._on_message

    def _on_connect(
        self,
        client: mqtt.Client,
        userdata: Any,
        flags: mqtt.ConnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        del userdata, flags, properties
        if reason_code.is_failure:
            return
        self.connected.set()
        client.subscribe(self.result_topic, qos=1)

    def _on_subscribe(
        self,
        client: mqtt.Client,
        userdata: Any,
        mid: int,
        reason_code_list: list[mqtt.ReasonCode],
        properties: mqtt.Properties | None,
    ) -> None:
        del client, userdata, mid, properties
        if reason_code_list and not any(
            reason.is_failure for reason in reason_code_list
        ):
            self.subscribed.set()

    def _on_message(
        self,
        client: mqtt.Client,
        userdata: Any,
        message: mqtt.MQTTMessage,
    ) -> None:
        del client, userdata
        try:
            payload = json.loads(message.payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return
        if not isinstance(payload, dict):
            return
        with self.condition:
            self.messages.append(payload)
            self.condition.notify_all()

    def start(self) -> None:
        self.client.connect("127.0.0.1", 8883, keepalive=30)
        self.client.loop_start()
        support.ensure(
            self.connected.wait(timeout=10),
            "result_observer_connected",
        )
        support.ensure(
            self.subscribed.wait(timeout=10),
            "result_observer_subscribed",
        )

    def stop(self) -> None:
        self.client.disconnect()
        self.client.loop_stop()

    def matching(
        self,
        command_id: str,
        status: str | None = None,
    ) -> list[dict[str, Any]]:
        return [
            payload
            for payload in self.messages
            if payload.get("command_id") == command_id
            and (status is None or payload.get("status") == status)
        ]

    def wait_for_count(
        self,
        command_id: str,
        status: str,
        count: int,
        timeout_seconds: float = 15,
    ) -> list[dict[str, Any]]:
        deadline = time.monotonic() + timeout_seconds
        with self.condition:
            while time.monotonic() < deadline:
                matches = self.matching(command_id, status)
                if len(matches) >= count:
                    return matches
                self.condition.wait(
                    timeout=min(0.5, deadline - time.monotonic())
                )
        raise support.E2EFailure(
            f"result_observer_{command_id}_{status}_{count}"
        )

    def publish_duplicate(self, payload: dict[str, Any]) -> None:
        published = self.client.publish(
            self.request_topic,
            json.dumps(
                payload,
                separators=(",", ":"),
                ensure_ascii=False,
            ),
            qos=1,
            retain=False,
        )
        published.wait_for_publish(timeout=10)
        support.ensure(
            published.is_published(),
            "duplicate_command_published",
        )


def create_command(
    client: Any,
    robot_id: str,
    command_type: str,
    parameters: dict[str, str],
) -> dict[str, Any]:
    if client.csrf_token is None:
        client.issue_csrf()
    request = urllib.request.Request(
        support.BACKEND_URL + f"/api/v1/robots/{robot_id}/commands",
        data=json.dumps({
            "commandType": command_type,
            "parameters": parameters,
        }).encode("utf-8"),
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Idempotency-Key": str(uuid.uuid4()),
            "X-CSRF-TOKEN": str(client.csrf_token),
            "User-Agent": "gilbom-command-abnormal-e2e",
        },
        method="POST",
    )
    try:
        response = client.opener.open(request, timeout=20)
        raw = response.read()
        status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read()
        status = error.code
    body: Any = None
    if raw:
        body = json.loads(raw.decode("utf-8"))
    if status != 201 or not isinstance(body, dict):
        raise support.E2EFailure(
            f"command_create_http_{status}_{body!r}"
        )
    return body


def create_publish_failed_command(
    client: Any,
    robot_id: str,
    fixture: dict[str, str],
    temporary_backend: str,
) -> str:
    try:
        print(
            "[command-abnormal-e2e] stopping broker to create a real "
            "PUBLISH_FAILED command"
        )
        support.compose("stop", "mosquitto")
        time.sleep(3)
        command = create_command(
            client,
            robot_id,
            "START_MISSION",
            {
                "missionId": fixture["mission_code"],
                "zoneId": fixture["area_code"],
            },
        )
        support.ensure(
            command.get("commandStatus") == "PUBLISH_FAILED",
            "publish_failed_command_status",
        )
        command_id = command.get("commandId")
        support.ensure(isinstance(command_id, str), "publish_failed_command_id")
        return command_id
    finally:
        support.compose("start", "mosquitto", allow_failure=True)
        support.command(["docker", "restart", temporary_backend])
        support.wait_for_health(temporary_backend)


def start_scenario_robot(
    mqtt_id: str,
    password: str,
) -> subprocess.Popen[str]:
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
            "--command-outcome",
            "PAUSE_MISSION=FAILED:E2E_FORCED_FAILURE",
            "--command-outcome",
            "EMERGENCY_STOP=REJECTED:E2E_SAFETY_REJECTED",
            "--command-delay-sec",
            "CANCEL_MISSION=31",
        ],
        cwd=support.PROJECT_DIR,
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
    timeout_seconds: int = 60,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if mock_process.poll() is not None:
            raise support.E2EFailure("mock_robot_exited")
        count = support.psql(
            db_container,
            database,
            f"""
            SELECT COUNT(*)
            FROM telemetry.robot_current_state
            WHERE robot_id = {support.sql_literal(robot_id)}::uuid
              AND connectivity_status = 'ONLINE';
            """,
        )
        if count == "1":
            return
        time.sleep(0.5)
    raise support.E2EFailure("mock_robot_online_after_broker_restart")


def run_playwright(
    session_cookie: str,
    robot_id: str,
    publish_failed_command_id: str,
) -> None:
    environment = os.environ.copy()
    environment.update({
        "COMMAND_E2E_SESSION_COOKIE": session_cookie,
        "COMMAND_E2E_ROBOT_ID": robot_id,
        "COMMAND_E2E_PUBLISH_FAILED_COMMAND_ID": publish_failed_command_id,
        "MISSION_LIVE_FRONTEND_URL": roundtrip.FRONTEND_URL,
    })
    npx = "npx.cmd" if os.name == "nt" else "npx"
    completed = subprocess.run(
        [
            npx,
            "playwright",
            "test",
            "e2e/command-abnormal-live.spec.ts",
            "--config=playwright.live.config.ts",
            "--output=test-results/command-abnormal-live",
        ],
        cwd=roundtrip.FRONTEND_DIR,
        env=environment,
        check=False,
    )
    support.ensure(
        completed.returncode == 0,
        "playwright_command_abnormal_live",
    )


def verify_duplicate_and_late_results(
    db_container: str,
    database: str,
    robot_id: str,
    observer: ResultObserver,
) -> None:
    completed_evidence = support.psql(
        db_container,
        database,
        f"""
        SELECT
            command_record.command_id
            || '|' || result_record.result_id
            || '|' || JSONB_BUILD_OBJECT(
                'schema_version', command_record.schema_version,
                'command_id', command_record.command_id,
                'command_type', command_record.command_type,
                'issued_at', command_record.issued_at,
                'expires_at', command_record.expires_at,
                'requested_by', JSONB_BUILD_OBJECT(
                    'actor_type', command_record.requested_by_type,
                    'actor_id', admin_user.user_code
                ),
                'parameters', command_record.parameters
            )::text
        FROM robot.robot_command command_record
        JOIN auth.admin_user admin_user
          ON admin_user.user_id = command_record.requested_by_user_id
        JOIN robot.robot_command_result result_record
          ON result_record.command_id = command_record.command_id
         AND result_record.result_status = 'COMPLETED'
        WHERE command_record.robot_id =
                {support.sql_literal(robot_id)}::uuid
          AND command_record.command_type = 'START_MISSION'
          AND command_record.command_status = 'COMPLETED';
        """,
    )
    completed_parts = completed_evidence.split("|", 2)
    support.ensure(
        len(completed_parts) == 3,
        "duplicate_command_evidence_shape",
    )
    completed_command_id, completed_result_id, raw_request = completed_parts
    request_payload = json.loads(raw_request)

    initial_completed = observer.wait_for_count(
        completed_command_id,
        "COMPLETED",
        1,
    )
    support.ensure(
        initial_completed[0].get("result_id") == completed_result_id,
        "initial_completed_result_id",
    )
    observer.publish_duplicate(request_payload)
    replayed_completed = observer.wait_for_count(
        completed_command_id,
        "COMPLETED",
        2,
    )
    support.ensure(
        {
            payload.get("result_id")
            for payload in replayed_completed
        } == {completed_result_id},
        "duplicate_command_replayed_cached_result",
    )

    expired_command_id = support.psql(
        db_container,
        database,
        f"""
        SELECT command_id
        FROM robot.robot_command
        WHERE robot_id = {support.sql_literal(robot_id)}::uuid
          AND command_type = 'CANCEL_MISSION'
          AND command_status = 'EXPIRED';
        """,
    )
    support.ensure(bool(expired_command_id), "expired_command_id")
    observer.wait_for_count(
        expired_command_id,
        "ACCEPTED",
        1,
    )
    observer.wait_for_count(
        expired_command_id,
        "COMPLETED",
        1,
    )
    time.sleep(1)


def verify_database(
    db_container: str,
    database: str,
    robot_id: str,
    publish_failed_command_id: str,
) -> None:
    statuses = support.psql(
        db_container,
        database,
        f"""
        SELECT command_type || ':' || command_status
        FROM robot.robot_command
        WHERE robot_id = {support.sql_literal(robot_id)}::uuid
        ORDER BY created_at;
        """,
    ).splitlines()
    support.ensure(
        statuses == [
            "START_MISSION:PUBLISH_FAILED",
            "START_MISSION:COMPLETED",
            "PAUSE_MISSION:FAILED",
            "EMERGENCY_STOP:REJECTED",
            "CANCEL_MISSION:EXPIRED",
        ],
        "abnormal_command_statuses",
    )

    result_summary = support.psql(
        db_container,
        database,
        f"""
        SELECT command_record.command_status
            || ':' || COUNT(result_record.result_id)
            || ':' || COALESCE(
                STRING_AGG(
                    result_record.result_status,
                    ',' ORDER BY result_record.server_received_at
                ),
                ''
            )
        FROM robot.robot_command command_record
        LEFT JOIN robot.robot_command_result result_record
          ON result_record.command_id = command_record.command_id
        WHERE command_record.robot_id =
            {support.sql_literal(robot_id)}::uuid
        GROUP BY
            command_record.command_id,
            command_record.command_status,
            command_record.created_at
        ORDER BY command_record.created_at;
        """,
    ).splitlines()
    support.ensure(
        result_summary == [
            "PUBLISH_FAILED:0:",
            "COMPLETED:2:ACCEPTED,COMPLETED",
            "FAILED:2:ACCEPTED,FAILED",
            "REJECTED:2:ACCEPTED,REJECTED",
            "EXPIRED:0:",
        ],
        "abnormal_command_results",
    )

    publish_attempt = support.psql(
        db_container,
        database,
        f"""
        SELECT command_status || ':' || publish_attempt_count
            || ':' || COALESCE(last_error_code, '')
        FROM robot.robot_command
        WHERE command_id =
            {support.sql_literal(publish_failed_command_id)}::uuid;
        """,
    )
    support.ensure(
        publish_attempt.startswith("PUBLISH_FAILED:1:"),
        "publish_failed_database_evidence",
    )

    duplicate_evidence = support.psql(
        db_container,
        database,
        f"""
        WITH target AS (
            SELECT command_id, publish_attempt_count
            FROM robot.robot_command
            WHERE robot_id = {support.sql_literal(robot_id)}::uuid
              AND command_type = 'PAUSE_MISSION'
              AND command_status = 'FAILED'
        )
        SELECT COUNT(*) || ':'
            || COALESCE(MAX(publish_attempt_count), 0) || ':'
            || (
                SELECT COUNT(*)
                FROM robot.robot_command_result result_record
                WHERE result_record.command_id = (
                    SELECT command_id
                    FROM target
                    LIMIT 1
                )
            )
        FROM target;
        """,
    )
    support.ensure(
        duplicate_evidence == "1:1:2",
        "rest_idempotency_database_evidence",
    )

    completed_duplicate_evidence = support.psql(
        db_container,
        database,
        f"""
        SELECT COUNT(*) || ':' || COUNT(DISTINCT result_id)
        FROM robot.robot_command_result result_record
        JOIN robot.robot_command command_record
          ON command_record.command_id = result_record.command_id
        WHERE command_record.robot_id =
                {support.sql_literal(robot_id)}::uuid
          AND command_record.command_type = 'START_MISSION'
          AND command_record.command_status = 'COMPLETED';
        """,
    )
    support.ensure(
        completed_duplicate_evidence == "2:2",
        "mqtt_duplicate_result_database_evidence",
    )


def main() -> int:
    suffix = secrets.token_hex(5)
    database = "gilbom_command_abnormal_e2e_" + suffix
    temporary_backend = "gilbom-command-abnormal-e2e-" + suffix
    db_container = ""
    main_backend_was_running = False
    database_created = False
    temporary_backend_started = False
    mock_process: subprocess.Popen[str] | None = None
    frontend_process: subprocess.Popen[str] | None = None
    observer: ResultObserver | None = None
    mock_output = ""
    frontend_output = ""

    try:
        db_container = support.compose("ps", "-q", "db")
        support.ensure(bool(db_container), "database_container")
        mqtt_init = support.compose("ps", "-aq", "mqtt-init")
        support.ensure(bool(mqtt_init), "mqtt_init_container")
        support.ensure(
            bool(support.compose("ps", "--status", "running", "-q", "mosquitto")),
            "mosquitto_container",
        )
        mqtt_environment = support.container_environment(mqtt_init)
        mqtt_id = mqtt_environment.get("MQTT_ROBOT_USERNAME", "")
        mqtt_password = mqtt_environment.get("MQTT_ROBOT_PASSWORD", "")
        backend_mqtt_username = mqtt_environment.get("MQTT_USERNAME", "")
        backend_mqtt_password = mqtt_environment.get("MQTT_PASSWORD", "")
        support.ensure(
            bool(mqtt_id)
            and bool(mqtt_password)
            and bool(backend_mqtt_username)
            and bool(backend_mqtt_password),
            "mqtt_robot_credentials",
        )
        support.ensure(
            support.CA_CERTIFICATE.is_file(),
            "mqtt_ca_certificate",
        )

        if os.getenv("COMMAND_ABNORMAL_E2E_SKIP_BACKEND_BUILD") != "1":
            print("[command-abnormal-e2e] building the current backend image")
            support.compose("build", "backend")
        main_backend_was_running = bool(
            support.compose("ps", "--status", "running", "-q", "backend")
        )
        if main_backend_was_running:
            print(
                "[command-abnormal-e2e] stopping the normal backend "
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

        client, robot_id = support.register_and_authenticate(
            db_container,
            database,
            mqtt_id,
            suffix,
        )
        fixture = roundtrip.create_command_fixture(
            client,
            robot_id,
            suffix,
        )
        mock_process = start_scenario_robot(mqtt_id, mqtt_password)
        wait_for_robot_online(
            db_container,
            database,
            robot_id,
            mock_process,
            timeout_seconds=20,
        )
        publish_failed_command_id = create_publish_failed_command(
            client,
            robot_id,
            fixture,
            temporary_backend,
        )
        wait_for_robot_online(
            db_container,
            database,
            robot_id,
            mock_process,
        )
        observer = ResultObserver(
            mqtt_id,
            backend_mqtt_username,
            backend_mqtt_password,
            suffix,
        )
        observer.start()
        print(
            "[command-abnormal-e2e] running abnormal states through React, "
            "REST, TLS MQTT, DB, and SSE"
        )
        run_playwright(
            client.session_cookie(),
            robot_id,
            publish_failed_command_id,
        )
        verify_duplicate_and_late_results(
            db_container,
            database,
            robot_id,
            observer,
        )
        verify_database(
            db_container,
            database,
            robot_id,
            publish_failed_command_id,
        )
        print(
            "[command-abnormal-e2e] PASS: PUBLISH_FAILED, COMPLETED, "
            "FAILED, REJECTED, EXPIRED, REST/MQTT duplicates, and "
            "late Result protection"
        )
        return 0
    except (
        support.E2EFailure,
        json.JSONDecodeError,
        KeyError,
        OSError,
        subprocess.SubprocessError,
        urllib.error.URLError,
    ) as error:
        print(f"[command-abnormal-e2e] FAIL: {error}", file=sys.stderr)
        if temporary_backend_started:
            logs = support.command(
                ["docker", "logs", "--tail", "160", temporary_backend],
                allow_failure=True,
            )
            if logs:
                print(
                    "[command-abnormal-e2e] temporary backend log tail:\n"
                    + logs,
                    file=sys.stderr,
                )
        return 1
    finally:
        if observer is not None:
            observer.stop()
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
        support.compose("start", "mosquitto", allow_failure=True)
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
            support.compose("start", "backend", allow_failure=True)
            try:
                support.wait_for_health(
                    support.compose("ps", "-q", "backend"),
                    timeout_seconds=90,
                )
            except (support.E2EFailure, urllib.error.URLError):
                print(
                    "[command-abnormal-e2e] WARNING: normal backend "
                    "restart health check failed",
                    file=sys.stderr,
                )
        if mock_output:
            print(
                "[command-abnormal-e2e] mock robot output tail:\n"
                + mock_output[-3000:],
            )
        if frontend_output and "error" in frontend_output.lower():
            print(
                "[command-abnormal-e2e] frontend output:\n"
                + frontend_output[-2000:],
                file=sys.stderr,
            )


if __name__ == "__main__":
    raise SystemExit(main())
