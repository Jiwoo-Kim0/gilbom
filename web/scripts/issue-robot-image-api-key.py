#!/usr/bin/env python3
"""Generate a robot image-upload API key and the SQL that registers it.

POST /api/v1/images authenticates robots with X-API-Key. The server stores only
the SHA-256 hash (storage.robot_image_api_key), so the plaintext key exists once:
in this script's output. There is no way to recover it later — if it is lost,
revoke the row and issue a new key.

Usage:

    python scripts/issue-robot-image-api-key.py --robot-id <uuid>
    python scripts/issue-robot-image-api-key.py --robot-id <uuid> --expires-in-days 30

The script only prints; it never connects to the database. Copy the SQL into
psql yourself so that key issuance stays a deliberate operator action.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import secrets
import sys
import uuid

#: 32 bytes of entropy. The hash is SHA-256 rather than a password KDF because
#: this is a server-generated random value, not a human-chosen secret.
KEY_BYTES = 32
KEY_NAMESPACE = "gik_"
#: Must match storage.robot_image_api_key.key_prefix (VARCHAR(12)).
PREFIX_LENGTH = 12


def generate_key() -> str:
    body = base64.urlsafe_b64encode(secrets.token_bytes(KEY_BYTES)).decode()
    return KEY_NAMESPACE + body.rstrip("=")


def parse_arguments(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Issue a robot image-upload API key",
    )
    parser.add_argument(
        "--robot-id",
        required=True,
        help="robot.robot.robot_id (UUID) the key authenticates as",
    )
    parser.add_argument(
        "--expires-in-days",
        type=int,
        default=None,
        help="optional expiry; omit for a key that does not expire",
    )
    return parser.parse_args(argv)


def build_sql(robot_id: str, key_prefix: str, key_hash: str, days: int | None) -> str:
    expires_at = "NULL" if days is None else f"NOW() + INTERVAL '{days} days'"
    return (
        "-- Revoke any existing active key first; only one ACTIVE key per robot is allowed.\n"
        "UPDATE storage.robot_image_api_key\n"
        "   SET key_status = 'REVOKED', revoked_at = NOW()\n"
        f" WHERE robot_id = '{robot_id}'\n"
        "   AND key_status = 'ACTIVE';\n"
        "\n"
        "INSERT INTO storage.robot_image_api_key (\n"
        "    robot_id, key_prefix, key_hash, expires_at\n"
        ") VALUES (\n"
        f"    '{robot_id}',\n"
        f"    '{key_prefix}',\n"
        f"    '{key_hash}',\n"
        f"    {expires_at}\n"
        ");"
    )


def main(argv: list[str]) -> int:
    arguments = parse_arguments(argv)
    try:
        robot_id = str(uuid.UUID(arguments.robot_id))
    except ValueError:
        print("--robot-id must be a UUID", file=sys.stderr)
        return 2
    if arguments.expires_in_days is not None and arguments.expires_in_days <= 0:
        print("--expires-in-days must be positive", file=sys.stderr)
        return 2

    key = generate_key()
    key_hash = hashlib.sha256(key.encode()).hexdigest()
    key_prefix = key[:PREFIX_LENGTH]

    print("=" * 72)
    print("API key (shown once — store it in the robot's config and nowhere else)")
    print("=" * 72)
    print(key)
    print()
    print(f"key_prefix : {key_prefix}")
    print(f"key_hash   : {key_hash}")
    print()
    print("-" * 72)
    print("Run this in psql as the migration owner:")
    print("-" * 72)
    print(build_sql(robot_id, key_prefix, key_hash, arguments.expires_in_days))
    print()
    print("Robot side (embedded/config.yaml):")
    print("  upload:")
    print("    url: https://<host>/api/v1/images")
    print(f"    api_key: {key}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
