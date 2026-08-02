#!/usr/bin/env python3
"""Send a pre-rendered JSON payload to a secret Mattermost Incoming Webhook."""

from __future__ import annotations

import argparse
import json
import os
import re
import ssl
import urllib.error
import urllib.request
from pathlib import Path


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"Mattermost notification failed: {message}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("payload", type=Path)
    args = parser.parse_args()

    webhook = os.environ.get("MATTERMOST_WEBHOOK_URL", "")
    if not re.fullmatch(
        r"https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/hooks/[A-Za-z0-9_-]+",
        webhook,
    ):
        fail("Webhook Credential must be an HTTPS /hooks URL")
    if not args.payload.is_file() or args.payload.is_symlink():
        fail("payload is missing or symlinked")
    try:
        document = json.loads(args.payload.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        fail("payload is not valid UTF-8 JSON")
    if not isinstance(document, dict) or not isinstance(document.get("text"), str):
        fail("payload must contain text")

    encoded = json.dumps(document, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        webhook,
        data=encoded,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(
            request,
            timeout=10,
            context=ssl.create_default_context(),
        ) as response:
            body = response.read(32).decode("utf-8", errors="replace").strip()
            if response.status != 200 or body.lower() != "ok":
                fail("server did not return HTTP 200 with ok")
    except (urllib.error.URLError, TimeoutError, ssl.SSLError):
        fail("HTTPS delivery failed")

    print("Mattermost notification delivery: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
