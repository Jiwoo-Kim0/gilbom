#!/usr/bin/env python3
"""Verify a production defect image URL, tamper rejection, and expiration.

Authentication inputs are read only from the environment and are never printed.
The login/MFA variables are the same as scripts/verify-defect-sse.py:

  GILBOM_EMAIL, GILBOM_PASSWORD, and one of
  GILBOM_TOTP_SECRET, GILBOM_TOTP_CODE, GILBOM_RECOVERY_CODE

Additional inputs:

  GILBOM_BASE_URL        defaults to https://i15d101.p.ssafy.io
  GILBOM_DEFECT_ID       defect UUID printed by verify-day8-persistence.sh
  GILBOM_VERIFY_URL_EXPIRY=true waits until the five-minute URL expires

For production, put authentication values in mode-0600 files and use
GILBOM_PASSWORD_FILE plus one of GILBOM_TOTP_SECRET_FILE,
GILBOM_TOTP_CODE_FILE, or GILBOM_RECOVERY_CODE_FILE.
"""

from __future__ import annotations

import datetime
import importlib.util
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


SCRIPT_DIR = Path(__file__).resolve().parent
BASE_URL = os.environ.get(
    "GILBOM_BASE_URL", "https://i15d101.p.ssafy.io"
).rstrip("/")
DEFECT_ID = os.environ.get("GILBOM_DEFECT_ID", "")
VERIFY_EXPIRY = (
    os.environ.get("GILBOM_VERIFY_URL_EXPIRY", "true").lower() == "true"
)


def fail(message: str) -> None:
    print(f"Day 8 signed image verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_auth_module():
    path = SCRIPT_DIR / "verify-defect-sse.py"
    spec = importlib.util.spec_from_file_location("gilbom_defect_auth", path)
    if spec is None or spec.loader is None:
        fail("could not load the authentication helper")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_secret_environment(name: str) -> None:
    path = os.environ.get(name + "_FILE")
    if not path:
        return
    try:
        value = Path(path).read_text(encoding="utf-8").strip()
    except OSError as error:
        fail(f"could not read {name}_FILE: {error}")
    if not value:
        fail(f"{name}_FILE is empty")
    os.environ[name] = value


def fetch(url: str) -> tuple[int, bytes, object]:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "image/jpeg",
            "User-Agent": "gilbom-day8-signed-image",
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


def tamper(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    tokens = query.get("token")
    if not tokens or not tokens[0]:
        fail("issued URL has no token")
    token = tokens[0]
    token = token[:-1] + ("A" if token[-1] != "A" else "B")
    query["token"] = [token]
    return urllib.parse.urlunsplit(
        (
            parsed.scheme,
            parsed.netloc,
            parsed.path,
            urllib.parse.urlencode(query, doseq=True),
            parsed.fragment,
        )
    )


def expiry_epoch(value: str) -> float:
    try:
        return datetime.datetime.fromisoformat(
            value.replace("Z", "+00:00")
        ).timestamp()
    except ValueError as error:
        fail(f"invalid expiresAt: {error}")


def main() -> int:
    if not DEFECT_ID:
        fail("GILBOM_DEFECT_ID is required")

    for secret_name in (
        "GILBOM_PASSWORD",
        "GILBOM_TOTP_SECRET",
        "GILBOM_TOTP_CODE",
        "GILBOM_RECOVERY_CODE",
    ):
        load_secret_environment(secret_name)

    auth = load_auth_module()
    client = auth.Client(BASE_URL)
    auth.authenticate(client)

    status, body = client.request(
        "GET", f"/api/v1/defects/{DEFECT_ID}/image-url"
    )
    if status != 200 or not isinstance(body, dict):
        fail(f"image URL issuance failed: status={status}")

    for field in ("imageId", "imageUrl", "expiresAt"):
        if not body.get(field):
            fail(f"image URL response is missing {field}")
    image_url = urllib.parse.urljoin(BASE_URL + "/", body["imageUrl"])

    valid_status, content, headers = fetch(image_url)
    if valid_status != 200:
        fail(f"valid signed URL returned {valid_status}")
    if headers.get_content_type() != "image/jpeg":
        fail("valid signed URL did not return image/jpeg")
    if not content.startswith(b"\xff\xd8") or not content.endswith(b"\xff\xd9"):
        fail("valid signed URL did not return a JPEG")
    if "no-store" not in headers.get("Cache-Control", ""):
        fail("private image response is cacheable")
    if headers.get("X-Content-Type-Options", "").lower() != "nosniff":
        fail("private image response is missing nosniff")
    print(
        "signed-image-valid=PASS "
        f"image_id={body['imageId']} bytes={len(content)}"
    )

    tampered_status, _, _ = fetch(tamper(image_url))
    if tampered_status not in (401, 403):
        fail(f"tampered URL was not rejected: status={tampered_status}")
    print("signed-image-tamper=PASS")

    if not VERIFY_EXPIRY:
        print("signed-image-expiry=SKIPPED")
        print(
            "Set GILBOM_VERIFY_URL_EXPIRY=true for the Day 8 completion gate.",
            file=sys.stderr,
        )
        return 2

    wait_seconds = max(0, int(expiry_epoch(body["expiresAt"]) - time.time()) + 2)
    if wait_seconds > 360:
        fail(f"issued URL TTL is unexpectedly long: {wait_seconds}s")
    if wait_seconds:
        print(f"waiting-for-expiry-seconds={wait_seconds}")
        time.sleep(wait_seconds)
    expired_status, _, _ = fetch(image_url)
    if expired_status not in (401, 403):
        fail(f"expired URL was not rejected: status={expired_status}")
    print("signed-image-expiry=PASS")
    print("Day 8 signed image URL: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
