#!/usr/bin/env python3
"""Collect fail-closed, non-mutating production security evidence.

The collector reads host state, validates the externally served TLS endpoint,
and calls only AWS Describe APIs. It never restarts a service, creates a
session, restores a backup, or changes a Security Group.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


PRODUCTION_ORIGIN = "https://i15d101.p.ssafy.io"
PRODUCTION_HOST = "i15d101.p.ssafy.io"
RELEASE_ROOT = Path("/var/lib/gilbom/releases")
ARTIFACT_ROOT = Path("/opt/gilbom/artifacts")
BACKUP_ROOT = Path("/opt/gilbom/backups")
RUNTIME_ENV = Path("/opt/gilbom/deploy/runtime.env")
COSIGN = Path("/usr/local/bin/cosign")
COSIGN_PUBLIC_KEY = Path("/etc/gilbom/cosign-release.pub")
STAGE15_COLLECTOR = Path("/usr/local/sbin/gilbom-stage15-host-evidence")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
BACKUP_BUNDLE = re.compile(r"^[0-9]{8}T[0-9]{6}Z-gilbom$")
HEX_64 = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_PUBLIC_PORTS = {80, 443}
ALLOWED_PUBLIC_PORTS = {22, 80, 443, 8883}
FORBIDDEN_EXTERNAL_PORTS = {3001, 5432, 8080, 8081, 9090, 9093, 3100}
REQUIRED_CONTAINERS = {
    "db": "gilbom-db-1",
    "mosquitto": "gilbom-mosquitto-1",
    "backend": "gilbom-backend-1",
    "frontend": "gilbom-frontend-1",
}


class EvidenceError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise EvidenceError(message)


def run(
    command: list[str],
    *,
    cwd: Path | None = None,
    timeout: int = 60,
) -> str:
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        fail(f"command could not run: {command[0]}: {error}")
    if completed.returncode != 0:
        detail = completed.stderr.strip().splitlines()
        suffix = f": {detail[-1]}" if detail else ""
        fail(f"command failed: {command[0]}{suffix}")
    return completed.stdout


def load_json(path: Path, label: str) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
        fail(f"{label} is missing, empty, or symlinked: {path}")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"{label} is not valid UTF-8 JSON: {path}: {error}")
    if not isinstance(document, dict):
        fail(f"{label} must be a JSON object")
    return document


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
    except OSError as error:
        fail(f"could not hash {path}: {error}")
    return digest.hexdigest()


def child_of(path: Path, root: Path, label: str) -> Path:
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to(root.resolve(strict=True))
    except (OSError, ValueError) as error:
        fail(f"{label} is outside its fixed root: {path}: {error}")
    if path.is_symlink():
        fail(f"{label} must not be a symlink: {path}")
    return resolved


def parse_env(path: Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink():
        fail(f"runtime environment file is missing or symlinked: {path}")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
            values[name] = value.strip().strip("\"'")
    return values


def parse_public_listeners(output: str) -> list[dict[str, object]]:
    listeners: set[tuple[str, int]] = set()
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 4:
            continue
        endpoint = fields[3]
        match = re.search(r"(?P<address>.+):(?P<port>[0-9]+)$", endpoint)
        if not match:
            continue
        address = match.group("address").strip("[]")
        if address not in {"0.0.0.0", "::", "*"}:
            continue
        listeners.add((address, int(match.group("port"))))
    return [
        {"address": address, "port": port}
        for address, port in sorted(listeners, key=lambda item: (item[1], item[0]))
    ]


def validate_public_listeners(
    listeners: list[dict[str, object]],
) -> dict[str, object]:
    ports = {int(item["port"]) for item in listeners}
    missing = sorted(EXPECTED_PUBLIC_PORTS - ports)
    forbidden = sorted(ports & FORBIDDEN_EXTERNAL_PORTS)
    if missing:
        fail(f"required HTTPS/ACME public listening ports are missing: {missing}")
    if forbidden:
        fail(f"internal service ports are publicly bound: {forbidden}")
    return {
        "status": "PASS",
        "listeners": listeners,
        "hostWildcardPorts": sorted(ports),
        "nonServiceWildcardPorts": sorted(ports - ALLOWED_PUBLIC_PORTS),
        "allowedPorts": sorted(ALLOWED_PUBLIC_PORTS),
        "requiredPorts": sorted(EXPECTED_PUBLIC_PORTS),
    }


def sources(permission: dict[str, Any]) -> list[str]:
    result: list[str] = []
    for item in permission.get("IpRanges", []):
        if isinstance(item, dict) and isinstance(item.get("CidrIp"), str):
            result.append(item["CidrIp"])
    for item in permission.get("Ipv6Ranges", []):
        if isinstance(item, dict) and isinstance(item.get("CidrIpv6"), str):
            result.append(item["CidrIpv6"])
    for item in permission.get("UserIdGroupPairs", []):
        if isinstance(item, dict) and isinstance(item.get("GroupId"), str):
            result.append(f"sg:{item['GroupId']}")
    for item in permission.get("PrefixListIds", []):
        if isinstance(item, dict) and isinstance(item.get("PrefixListId"), str):
            result.append(f"pl:{item['PrefixListId']}")
    return result


def permission_covers(permission: dict[str, Any], port: int) -> bool:
    protocol = str(permission.get("IpProtocol", ""))
    if protocol == "-1":
        return True
    if protocol not in {"tcp", "6"}:
        return False
    start = permission.get("FromPort")
    end = permission.get("ToPort")
    return isinstance(start, int) and isinstance(end, int) and start <= port <= end


def validate_security_groups(
    document: dict[str, Any],
    attached_group_ids: list[str],
) -> dict[str, object]:
    groups = document.get("SecurityGroups")
    if not isinstance(groups, list) or not groups:
        fail("AWS describe-security-groups returned no SecurityGroups")
    group_ids = {
        str(group.get("GroupId"))
        for group in groups
        if isinstance(group, dict) and isinstance(group.get("GroupId"), str)
    }
    if group_ids != set(attached_group_ids):
        fail("AWS Security Group evidence does not match groups attached to this EC2")

    world = {"0.0.0.0/0", "::/0"}
    public_80 = False
    public_443 = False
    allowed_tcp_ports: set[int] = set()
    normalized: list[dict[str, object]] = []
    for group in groups:
        if not isinstance(group, dict):
            fail("AWS Security Group entry is malformed")
        permissions = group.get("IpPermissions")
        if not isinstance(permissions, list):
            fail("AWS Security Group IpPermissions is malformed")
        for permission in permissions:
            if not isinstance(permission, dict):
                fail("AWS Security Group permission is malformed")
            rule_sources = sorted(set(sources(permission)))
            protocol = str(permission.get("IpProtocol", ""))
            start = permission.get("FromPort")
            end = permission.get("ToPort")
            normalized.append(
                {
                    "groupId": group.get("GroupId"),
                    "protocol": protocol,
                    "fromPort": start,
                    "toPort": end,
                    "sources": rule_sources,
                }
            )
            if any(permission_covers(permission, port) for port in FORBIDDEN_EXTERNAL_PORTS):
                if rule_sources:
                    fail(
                        "AWS Security Group exposes an internal service port: "
                        f"group={group.get('GroupId')}, ports={start}-{end}"
                    )
            world_open = bool(world & set(rule_sources))
            if protocol == "-1" and rule_sources:
                fail("AWS Security Group allows all protocols from an inbound source")
            if protocol not in {"tcp", "6", "-1"} and rule_sources:
                fail(
                    "AWS Security Group has an unauthorized non-TCP inbound rule: "
                    f"protocol={protocol}"
                )
            if protocol in {"tcp", "6"} and rule_sources:
                if (
                    not isinstance(start, int)
                    or not isinstance(end, int)
                    or start != end
                    or start not in ALLOWED_PUBLIC_PORTS
                ):
                    fail(
                        "AWS Security Group has an unauthorized inbound TCP range: "
                        f"{start}-{end}"
                    )
                allowed_tcp_ports.add(start)
            if world_open:
                if protocol not in {"tcp", "6"} or not isinstance(start, int) or not isinstance(end, int):
                    fail("AWS Security Group has a world-open non-TCP or unbounded rule")
                if start != end or start not in {80, 443}:
                    fail(
                        "AWS Security Group has an unauthorized world-open port range: "
                        f"{start}-{end}"
                    )
                public_80 = public_80 or start == 80
                public_443 = public_443 or start == 443
    if not public_80 or not public_443:
        fail("AWS Security Groups do not prove public TCP/80 and TCP/443")
    return {
        "status": "PASS",
        "attachedGroupIds": sorted(attached_group_ids),
        "rules": normalized,
        "allowedInboundTcpPorts": sorted(allowed_tcp_ports),
        "worldOpenAllowedPorts": [80, 443],
        "forbiddenPorts": sorted(FORBIDDEN_EXTERNAL_PORTS),
    }


def correlate_external_ports(
    host_evidence: dict[str, object],
    security_group_evidence: dict[str, object],
) -> dict[str, object]:
    host_ports = {
        int(port)
        for port in host_evidence.get("hostWildcardPorts", [])
        if isinstance(port, int)
    }
    inbound_ports = {
        int(port)
        for port in security_group_evidence.get("allowedInboundTcpPorts", [])
        if isinstance(port, int)
    }
    externally_reachable = sorted(host_ports & inbound_ports)
    unauthorized = sorted(set(externally_reachable) - ALLOWED_PUBLIC_PORTS)
    if unauthorized:
        fail(f"unauthorized externally reachable TCP ports: {unauthorized}")
    if not EXPECTED_PUBLIC_PORTS <= set(externally_reachable):
        fail("TCP/80 and TCP/443 are not both listener-and-SG reachable")
    result = dict(host_evidence)
    result.update(
        {
            "status": "PASS",
            "sgAllowedTcpPorts": sorted(inbound_ports),
            "externallyReachableTcpPorts": externally_reachable,
            "hostBoundButSgBlockedTcpPorts": sorted(host_ports - inbound_ports),
            "sgAllowedWithoutHostListenerTcpPorts": sorted(inbound_ports - host_ports),
        }
    )
    return result


def unverified_security_group_evidence(
    attached_group_ids: list[str],
) -> dict[str, object]:
    return {
        "status": "NOT_VERIFIED",
        "reason": (
            "EC2 has no AWS API authentication; IMDSv2 exposes attached "
            "Security Group IDs but not their inbound rules"
        ),
        "attachedGroupIds": sorted(attached_group_ids),
        "rules": None,
    }


def uncorrelated_external_ports(
    host_evidence: dict[str, object],
) -> dict[str, object]:
    result = dict(host_evidence)
    result.update(
        {
            "status": "PARTIAL",
            "externallyReachableTcpPorts": None,
            "hostBoundButSgBlockedTcpPorts": None,
            "reason": (
                "host wildcard listeners were verified, but effective external "
                "reachability requires AWS Security Group rules or an off-host probe"
            ),
        }
    )
    return result


def imds_request(path: str, token: str | None = None, method: str = "GET") -> str:
    url = f"http://169.254.169.254/latest/{path.lstrip('/')}"
    headers: dict[str, str] = {}
    if token:
        headers["X-aws-ec2-metadata-token"] = token
    if method == "PUT":
        headers["X-aws-ec2-metadata-token-ttl-seconds"] = "60"
    request = urllib.request.Request(url, headers=headers, method=method)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=3) as response:
            return response.read(1024 * 1024).decode("utf-8")
    except (OSError, UnicodeError, urllib.error.URLError) as error:
        fail(f"EC2 IMDSv2 request failed: {path}: {error}")


def ec2_identity() -> tuple[str, str, list[str]]:
    token = imds_request("api/token", method="PUT")
    identity = json.loads(imds_request("dynamic/instance-identity/document", token))
    instance_id = identity.get("instanceId")
    region = identity.get("region")
    if not isinstance(instance_id, str) or not isinstance(region, str):
        fail("EC2 identity document is missing instanceId or region")
    macs = [
        item.strip().rstrip("/")
        for item in imds_request("meta-data/network/interfaces/macs/", token).splitlines()
        if item.strip()
    ]
    group_ids: set[str] = set()
    for mac in macs:
        data = imds_request(
            f"meta-data/network/interfaces/macs/{mac}/security-group-ids",
            token,
        )
        group_ids.update(item.strip() for item in data.splitlines() if item.strip())
    if not group_ids or not all(re.fullmatch(r"sg-[0-9a-f]+", item) for item in group_ids):
        fail("EC2 IMDSv2 did not return valid attached Security Group IDs")
    return instance_id, region, sorted(group_ids)


def fetch_security_groups(
    path: Path | None,
    region: str,
    group_ids: list[str],
) -> dict[str, Any]:
    if path is not None:
        return load_json(path, "AWS Security Group evidence")
    output = run(
        [
            "aws",
            "ec2",
            "describe-security-groups",
            "--region",
            region,
            "--group-ids",
            *group_ids,
            "--output",
            "json",
            "--no-cli-pager",
        ],
        timeout=30,
    )
    try:
        document = json.loads(output)
    except json.JSONDecodeError as error:
        fail(f"AWS CLI output is not JSON: {error}")
    if not isinstance(document, dict):
        fail("AWS CLI Security Group output must be an object")
    return document


def tls_evidence(host: str) -> dict[str, object]:
    context = ssl.create_default_context()
    try:
        with socket.create_connection((host, 443), timeout=15) as raw:
            with context.wrap_socket(raw, server_hostname=host) as tls:
                certificate = tls.getpeercert()
                protocol = tls.version()
                cipher = tls.cipher()
    except (OSError, ssl.SSLError) as error:
        fail(f"verified TLS connection failed: {error}")
    expires = certificate.get("notAfter")
    if not isinstance(expires, str):
        fail("served certificate has no notAfter")
    expires_epoch = ssl.cert_time_to_seconds(expires)
    now_epoch = dt.datetime.now(dt.timezone.utc).timestamp()
    remaining_days = int((expires_epoch - now_epoch) // 86400)
    if remaining_days < 14:
        fail(f"served certificate expires too soon: {remaining_days} days")
    return {
        "status": "PASS",
        "hostnameVerification": "PASS",
        "chainVerification": "PASS",
        "protocol": protocol,
        "cipher": cipher[0] if cipher else "unknown",
        "expiresAtUtc": dt.datetime.fromtimestamp(
            expires_epoch, tz=dt.timezone.utc
        ).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "remainingDays": remaining_days,
    }


def https_request(
    method: str,
    path: str,
    headers: dict[str, str] | None = None,
) -> tuple[int, dict[str, str], bytes]:
    connection = http.client.HTTPSConnection(
        PRODUCTION_HOST,
        443,
        timeout=20,
        context=ssl.create_default_context(),
    )
    try:
        connection.request(method, path, headers=headers or {})
        response = connection.getresponse()
        body = response.read(1024 * 1024)
        response_headers = {name.lower(): value for name, value in response.getheaders()}
        return response.status, response_headers, body
    except (OSError, ssl.SSLError, http.client.HTTPException) as error:
        fail(f"{method} {path} failed: {error}")
    finally:
        connection.close()


def validate_security_headers(headers: dict[str, str]) -> dict[str, str]:
    required_exact = {
        "x-content-type-options": "nosniff",
        "x-frame-options": "DENY",
        "referrer-policy": "no-referrer",
    }
    for name, expected in required_exact.items():
        if headers.get(name, "").lower() != expected.lower():
            fail(f"HTTPS response security header is invalid: {name}")
    hsts = headers.get("strict-transport-security", "")
    match = re.search(r"(?:^|;)\s*max-age=([0-9]+)", hsts, re.IGNORECASE)
    if not match or int(match.group(1)) < 31_536_000:
        fail("HSTS max-age is missing or shorter than one year")
    csp = headers.get("content-security-policy", "")
    for directive in ("default-src 'self'", "object-src 'none'", "frame-ancestors 'none'"):
        if directive not in csp:
            fail(f"CSP directive is missing: {directive}")
    permissions = headers.get("permissions-policy", "")
    if "camera=()" not in permissions or "microphone=()" not in permissions:
        fail("Permissions-Policy does not disable camera and microphone")
    return {
        "csp": "PASS",
        "hsts": "PASS",
        "xContentTypeOptions": "PASS",
        "xFrameOptions": "PASS",
        "referrerPolicy": "PASS",
        "permissionsPolicy": "PASS",
    }


def cors_evidence(runtime: dict[str, str]) -> dict[str, object]:
    enabled = runtime.get("GILBOM_CORS_ENABLED", "false").lower() == "true"
    untrusted = "https://untrusted.invalid"
    status, headers, _ = https_request(
        "OPTIONS",
        "/api/v1/auth/csrf-token",
        {
            "Origin": untrusted,
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "Content-Type",
        },
    )
    if headers.get("access-control-allow-origin"):
        fail("untrusted Origin received Access-Control-Allow-Origin")
    evidence: dict[str, object] = {
        "status": "PASS",
        "enabled": enabled,
        "untrustedOriginRejected": True,
        "untrustedProbeStatus": status,
    }
    if enabled:
        allowed = [
            item.strip()
            for item in runtime.get("GILBOM_CORS_ALLOWED_ORIGINS", "").split(",")
            if item.strip()
        ]
        if not allowed or any(item == "*" for item in allowed):
            fail("enabled production CORS has no exact allowlist")
        allowed_origin = allowed[0]
        allowed_status, allowed_headers, _ = https_request(
            "OPTIONS",
            "/api/v1/auth/csrf-token",
            {
                "Origin": allowed_origin,
                "Access-Control-Request-Method": "GET",
                "Access-Control-Request-Headers": "Content-Type",
            },
        )
        if allowed_headers.get("access-control-allow-origin") != allowed_origin:
            fail("allowed Origin did not receive its exact ACAO value")
        if allowed_headers.get("access-control-allow-credentials", "").lower() != "true":
            fail("credentialed CORS did not return allow-credentials=true")
        evidence.update(
            {
                "allowedOrigin": allowed_origin,
                "allowedProbeStatus": allowed_status,
                "credentialed": True,
            }
        )
    return evidence


def collect(args: argparse.Namespace) -> dict[str, object]:
    if os.geteuid() != 0:
        fail("run this collector as root")
    if PRODUCTION_ORIGIN != f"https://{PRODUCTION_HOST}":
        fail("fixed production origin is internally inconsistent")

    stage15 = json.loads(run([str(STAGE15_COLLECTOR)], timeout=60))
    if stage15.get("status") != "PASS":
        fail("Stage 15 host evidence is not PASS")

    current_sha = str(stage15.get("production", {}).get("currentSha", ""))
    previous_sha = str(stage15.get("production", {}).get("previousSha", ""))
    if not FULL_SHA.fullmatch(current_sha) or not FULL_SHA.fullmatch(previous_sha):
        fail("current.sha or previous.sha is not a full SHA")
    release_dir = child_of(RELEASE_ROOT / current_sha, RELEASE_ROOT, "release record")
    manifest_path = release_dir / "release-manifest.json"
    preflight_path = release_dir / "deploy-preflight.json"
    manifest = load_json(manifest_path, "Release Manifest")
    preflight = load_json(preflight_path, "deploy preflight")
    if manifest.get("releaseSha") != current_sha or preflight.get("releaseSha") != current_sha:
        fail("release evidence does not match current.sha")

    containers: dict[str, object] = {}
    backend_environment: list[str] = []
    for service, name in REQUIRED_CONTAINERS.items():
        inspected = json.loads(run(["docker", "inspect", name]))
        if not isinstance(inspected, list) or len(inspected) != 1:
            fail(f"container inspect is malformed: {name}")
        container = inspected[0]
        state = container.get("State", {})
        health = state.get("Health", {}) if isinstance(state, dict) else {}
        labels = container.get("Config", {}).get("Labels", {})
        if (
            not isinstance(state, dict)
            or state.get("Running") is not True
            or not isinstance(health, dict)
            or health.get("Status") != "healthy"
            or not isinstance(labels, dict)
            or labels.get("com.docker.compose.project") != "gilbom"
            or labels.get("com.docker.compose.service") != service
        ):
            fail(f"production container is not healthy or belongs to wrong project: {name}")
        containers[service] = {"name": name, "status": "healthy"}
        if service == "backend":
            environment = container.get("Config", {}).get("Env", [])
            if isinstance(environment, list):
                backend_environment = [str(item) for item in environment]

    profile = next(
        (
            item.split("=", 1)[1]
            for item in backend_environment
            if item.startswith("SPRING_PROFILES_ACTIVE=")
        ),
        "",
    )
    profiles = {item.strip() for item in profile.split(",") if item.strip()}
    if not {"ec2", "docker"} <= profiles:
        fail("production Backend does not run with the ec2,docker profiles")

    artifact = manifest.get("artifact")
    if not isinstance(artifact, dict):
        fail("Release Manifest artifact evidence is missing")
    preflight_artifact = preflight.get("artifact")
    if not isinstance(preflight_artifact, dict):
        fail("deploy preflight artifact evidence is missing")
    artifact_dir_value = artifact.get("directory")
    if not isinstance(artifact_dir_value, str) or not artifact_dir_value.startswith("/"):
        fail("Release Manifest artifact directory is invalid")
    artifact_dir = child_of(Path(artifact_dir_value), ARTIFACT_ROOT, "release artifact")
    checksum_file = artifact_dir / "SHA256SUMS"
    archive = artifact_dir / "images.tar.zst"
    bundle = artifact_dir / "images.tar.zst.sigstore.json"
    for path in (checksum_file, archive, bundle, COSIGN, COSIGN_PUBLIC_KEY):
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            fail(f"required release verification file is missing or unsafe: {path}")
    run(["sha256sum", "--check", "SHA256SUMS"], cwd=artifact_dir, timeout=120)
    run(
        [
            str(COSIGN),
            "verify-blob",
            "--insecure-ignore-tlog",
            "--key",
            str(COSIGN_PUBLIC_KEY),
            "--bundle",
            str(bundle),
            str(archive),
        ],
        timeout=120,
    )
    cosign_version_output = run([str(COSIGN), "version"])
    signature = artifact.get("signature")
    if not isinstance(signature, dict) or signature.get("status") != "PASS":
        fail("Release Manifest Cosign status is not PASS")
    cosign_version = signature.get("cosignVersion")
    if (
        cosign_version != "v3.1.2"
        or f"GitVersion:    {cosign_version}" not in cosign_version_output
    ):
        fail("installed Cosign version differs from the Release Manifest pin")
    public_key_sha256 = sha256(COSIGN_PUBLIC_KEY)
    bundle_sha256 = sha256(bundle)
    checksum_sha256 = sha256(checksum_file)
    archive_sha256 = sha256(archive)
    if (
        signature.get("publicKeySha256") != public_key_sha256
        or signature.get("bundleSha256") != bundle_sha256
        or artifact.get("sha256SumsSha256") != checksum_sha256
        or preflight_artifact.get("archiveSha256") != archive_sha256
    ):
        fail("Release evidence Cosign/archive/checksum hashes differ from actual files")

    vulnerabilities = preflight.get("vulnerabilities")
    if not isinstance(vulnerabilities, dict):
        fail("vulnerability policy evidence is missing")
    critical = vulnerabilities.get("critical")
    high = vulnerabilities.get("high")
    excepted_high = vulnerabilities.get("exceptedHigh")
    unapproved_high = vulnerabilities.get("unapprovedHigh")
    if (
        critical != 0
        or unapproved_high != 0
        or not isinstance(high, int)
        or not isinstance(excepted_high, int)
        or high != excepted_high
    ):
        fail("Critical/High vulnerability policy is not PASS")

    bundles = [
        path
        for path in BACKUP_ROOT.iterdir()
        if path.is_dir() and not path.is_symlink() and BACKUP_BUNDLE.fullmatch(path.name)
    ]
    if not bundles:
        fail("no production volume backup bundle exists")
    latest_backup = max(bundles, key=lambda path: path.stat().st_mtime)
    backup_checksum = latest_backup / "SHA256SUMS"
    if not backup_checksum.is_file() or backup_checksum.is_symlink():
        fail("latest backup has no safe SHA256SUMS")
    run(["sha256sum", "--check", "SHA256SUMS"], cwd=latest_backup, timeout=120)

    listener_output = run(["ss", "-H", "-lnt"])
    host_port_evidence = validate_public_listeners(
        parse_public_listeners(listener_output)
    )

    status, headers, body = https_request("GET", "/")
    if status != 200 or b'<div id="root"></div>' not in body:
        fail("production HTTPS root did not return the expected SPA")
    header_evidence = validate_security_headers(headers)
    runtime = parse_env(RUNTIME_ENV)
    cors = cors_evidence(runtime)
    tls = tls_evidence(PRODUCTION_HOST)

    instance_id, region, group_ids = ec2_identity()
    if args.skip_aws_security_groups:
        aws_security_groups = unverified_security_group_evidence(group_ids)
        port_evidence = uncorrelated_external_ports(host_port_evidence)
        overall_status = "PARTIAL"
        unverified_items = [
            "AWS_SECURITY_GROUP_INBOUND",
            "EFFECTIVE_EXTERNAL_PORT_REACHABILITY",
        ]
    else:
        security_group_document = fetch_security_groups(
            args.aws_security_groups,
            region,
            group_ids,
        )
        aws_security_groups = validate_security_groups(
            security_group_document,
            group_ids,
        )
        port_evidence = correlate_external_ports(
            host_port_evidence,
            aws_security_groups,
        )
        overall_status = "PASS"
        unverified_items = []

    captured_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    return {
        "schemaVersion": 1,
        "status": overall_status,
        "unverifiedItems": unverified_items,
        "capturedAtUtc": captured_at.isoformat().replace("+00:00", "Z"),
        "mode": "READ_ONLY_NON_DESTRUCTIVE",
        "productionOrigin": PRODUCTION_ORIGIN,
        "network": {
            "externalPorts": port_evidence,
            "awsSecurityGroups": aws_security_groups,
            "ec2": {"instanceId": instance_id, "region": region},
            "s3": {"status": "N/A", "reason": "local persistent volumes are used"},
        },
        "https": {
            "tls": tls,
            "cors": cors,
            "cookie": {
                "status": "PASS",
                "evidenceType": "SIGNED_IMAGE_ACTIVE_PROFILE",
                "profile": "ec2,docker",
                "name": "__Host-GILBOM_SESSION",
                "secure": True,
                "httpOnly": True,
                "sameSite": "Lax",
                "path": "/",
                "domainConfigured": False,
                "anonymousSessionCreated": False,
            },
            "headers": header_evidence,
        },
        "release": {
            "currentSha": current_sha,
            "previousSha": previous_sha,
            "manifest": {
                "status": "PASS",
                "path": str(manifest_path),
                "sha256": sha256(manifest_path),
            },
            "cosign": {
                "status": "PASS",
                "version": cosign_version,
                "publicKeySha256": public_key_sha256,
                "bundleSha256": bundle_sha256,
                "archiveSha256": archive_sha256,
            },
            "vulnerabilities": {
                "status": "PASS",
                "critical": critical,
                "high": high,
                "exceptedHigh": excepted_high,
                "unapprovedHigh": unapproved_high,
            },
        },
        "backup": {
            "status": "PASS",
            "bundle": str(latest_backup),
            "sha256SumsSha256": sha256(backup_checksum),
            "checksumVerification": "PASS",
        },
        "runtime": {
            "containers": containers,
            "deployDockerGroupMember": False,
            "jenkinsDockerSocketMounted": False,
            "jenkinsDindTls": True,
            "stage15HostEvidence": "PASS",
        },
        "prohibitedActions": {
            "loginLockTriggered": False,
            "databaseFailureInjected": False,
            "backupRestored": False,
            "loadGenerated": False,
            "ec2Rebooted": False,
            "certificateReplaced": False,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    security_group_source = parser.add_mutually_exclusive_group()
    security_group_source.add_argument(
        "--aws-security-groups",
        type=Path,
        help=(
            "Optional AWS describe-security-groups JSON. When omitted, the "
            "collector uses EC2 IMDSv2 and the read-only AWS CLI."
        ),
    )
    security_group_source.add_argument(
        "--skip-aws-security-groups",
        action="store_true",
        help=(
            "Collect every other check and emit PARTIAL evidence when this EC2 "
            "has neither AWS API credentials nor supplied Security Group JSON."
        ),
    )
    args = parser.parse_args()
    try:
        evidence = collect(args)
    except (EvidenceError, OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"Production read-only verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
