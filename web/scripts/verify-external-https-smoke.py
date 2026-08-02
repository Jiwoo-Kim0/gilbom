#!/usr/bin/env python3
"""Run certificate-validating production HTTPS smoke checks."""

from __future__ import annotations

import datetime as dt
import json
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"External production HTTPS smoke failed: {message}")


if len(sys.argv) != 3:
    fail("usage: verify-external-https-smoke.py BASE_URL OUTPUT_JSON")

base_url = sys.argv[1]
output_path = Path(sys.argv[2])
parsed = urllib.parse.urlsplit(base_url)
if (
    parsed.scheme != "https"
    or not parsed.hostname
    or parsed.username
    or parsed.password
    or parsed.query
    or parsed.fragment
    or parsed.path not in {"", "/"}
    or not re.fullmatch(r"[A-Za-z0-9.-]+", parsed.hostname)
):
    fail("BASE_URL must be a credential-free HTTPS origin")

origin = urllib.parse.urlunsplit(
    ("https", parsed.netloc, "", "", "")
)
context = ssl.create_default_context()
responses: dict[str, object] = {}


def fetch(path: str) -> bytes:
    request = urllib.request.Request(
        origin + path,
        headers={"User-Agent": "gilbom-jenkins-production-smoke/1"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(
            request, timeout=20, context=context
        ) as response:
            if response.geturl() != origin + path:
                fail(f"{path} redirected outside its fixed production URL")
            if response.status != 200:
                fail(f"{path} returned HTTP {response.status}")
            body = response.read(1024 * 1024)
            responses[path] = {
                "status": response.status,
                "bytes": len(body),
            }
            return body
    except (urllib.error.URLError, TimeoutError, ssl.SSLError) as error:
        fail(f"{path} request failed: {error}")


healthz = fetch("/healthz").decode("utf-8").strip()
if healthz != "ok":
    fail("/healthz response was not exactly ok")

try:
    api_health = json.loads(fetch("/api/health"))
except (UnicodeDecodeError, json.JSONDecodeError) as error:
    fail(f"/api/health did not return valid JSON: {error}")
if api_health.get("status") != "UP":
    fail("/api/health status was not UP")

document = {
    "schemaVersion": 1,
    "baseUrl": origin,
    "checkedAtUtc": dt.datetime.now(dt.timezone.utc)
    .replace(microsecond=0)
    .isoformat()
    .replace("+00:00", "Z"),
    "tlsVerification": "PASS",
    "checks": responses,
    "status": "PASS",
}
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(
    json.dumps(document, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print("External production HTTPS smoke: PASS")
