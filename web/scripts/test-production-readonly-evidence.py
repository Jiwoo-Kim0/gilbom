#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/collect-production-readonly-evidence.py"
SPEC = importlib.util.spec_from_file_location("production_readonly", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def security_groups(*permissions: dict[str, object]) -> dict[str, object]:
    return {
        "SecurityGroups": [
            {
                "GroupId": "sg-0123456789abcdef0",
                "IpPermissions": list(permissions),
            }
        ]
    }


def tcp(port: int, cidr: str) -> dict[str, object]:
    return {
        "IpProtocol": "tcp",
        "FromPort": port,
        "ToPort": port,
        "IpRanges": [{"CidrIp": cidr}],
        "Ipv6Ranges": [],
        "UserIdGroupPairs": [],
        "PrefixListIds": [],
    }


def udp(port: int, cidr: str) -> dict[str, object]:
    return {
        "IpProtocol": "udp",
        "FromPort": port,
        "ToPort": port,
        "IpRanges": [{"CidrIp": cidr}],
        "Ipv6Ranges": [],
        "UserIdGroupPairs": [],
        "PrefixListIds": [],
    }


class ProductionReadonlyEvidenceTest(unittest.TestCase):
    def test_expected_public_listeners_pass(self) -> None:
        listeners = MODULE.parse_public_listeners(
            "LISTEN 0 4096 0.0.0.0:80 0.0.0.0:*\n"
            "LISTEN 0 4096 0.0.0.0:443 0.0.0.0:*\n"
            "LISTEN 0 128 127.0.0.1:9090 0.0.0.0:*\n"
        )
        evidence = MODULE.validate_public_listeners(listeners)
        self.assertEqual(evidence["status"], "PASS")
        self.assertEqual(
            {item["port"] for item in evidence["listeners"]},
            {80, 443},
        )

    def test_public_internal_port_fails(self) -> None:
        listeners = MODULE.parse_public_listeners(
            "LISTEN 0 4096 0.0.0.0:80 0.0.0.0:*\n"
            "LISTEN 0 4096 0.0.0.0:443 0.0.0.0:*\n"
            "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n"
        )
        with self.assertRaises(MODULE.EvidenceError):
            MODULE.validate_public_listeners(listeners)

    def test_unknown_wildcard_listener_is_deferred_to_sg_correlation(self) -> None:
        listeners = MODULE.parse_public_listeners(
            "LISTEN 0 4096 0.0.0.0:80 0.0.0.0:*\n"
            "LISTEN 0 4096 0.0.0.0:443 0.0.0.0:*\n"
            "LISTEN 0 4096 0.0.0.0:8988 0.0.0.0:*\n"
        )
        host = MODULE.validate_public_listeners(listeners)
        sg = {
            "allowedInboundTcpPorts": [22, 80, 443, 8883],
        }
        evidence = MODULE.correlate_external_ports(host, sg)
        self.assertEqual(evidence["externallyReachableTcpPorts"], [80, 443])
        self.assertEqual(evidence["hostBoundButSgBlockedTcpPorts"], [8988])

    def test_restricted_ssh_and_mqtt_security_groups_pass(self) -> None:
        document = security_groups(
            tcp(80, "0.0.0.0/0"),
            tcp(443, "0.0.0.0/0"),
            tcp(22, "203.0.113.10/32"),
            tcp(8883, "198.51.100.0/24"),
        )
        evidence = MODULE.validate_security_groups(
            document,
            ["sg-0123456789abcdef0"],
        )
        self.assertEqual(evidence["status"], "PASS")

    def test_world_open_ssh_fails(self) -> None:
        document = security_groups(
            tcp(80, "0.0.0.0/0"),
            tcp(443, "0.0.0.0/0"),
            tcp(22, "0.0.0.0/0"),
        )
        with self.assertRaises(MODULE.EvidenceError):
            MODULE.validate_security_groups(
                document,
                ["sg-0123456789abcdef0"],
            )

    def test_restricted_unknown_inbound_port_fails(self) -> None:
        document = security_groups(
            tcp(80, "0.0.0.0/0"),
            tcp(443, "0.0.0.0/0"),
            tcp(8988, "203.0.113.10/32"),
        )
        with self.assertRaises(MODULE.EvidenceError):
            MODULE.validate_security_groups(
                document,
                ["sg-0123456789abcdef0"],
            )

    def test_restricted_udp_inbound_rule_fails(self) -> None:
        document = security_groups(
            tcp(80, "0.0.0.0/0"),
            tcp(443, "0.0.0.0/0"),
            udp(8883, "203.0.113.10/32"),
        )
        with self.assertRaises(MODULE.EvidenceError):
            MODULE.validate_security_groups(
                document,
                ["sg-0123456789abcdef0"],
            )

    def test_database_port_rule_fails_even_when_restricted(self) -> None:
        document = security_groups(
            tcp(80, "0.0.0.0/0"),
            tcp(443, "0.0.0.0/0"),
            tcp(5432, "10.0.0.0/8"),
        )
        with self.assertRaises(MODULE.EvidenceError):
            MODULE.validate_security_groups(
                document,
                ["sg-0123456789abcdef0"],
            )

    def test_required_security_headers_pass(self) -> None:
        evidence = MODULE.validate_security_headers(
            {
                "content-security-policy": (
                    "default-src 'self'; object-src 'none'; "
                    "frame-ancestors 'none'"
                ),
                "strict-transport-security": "max-age=31536000",
                "x-content-type-options": "nosniff",
                "x-frame-options": "DENY",
                "referrer-policy": "no-referrer",
                "permissions-policy": "camera=(), microphone=()",
            }
        )
        self.assertEqual(evidence["csp"], "PASS")

    def test_short_hsts_fails(self) -> None:
        with self.assertRaises(MODULE.EvidenceError):
            MODULE.validate_security_headers(
                {
                    "content-security-policy": (
                        "default-src 'self'; object-src 'none'; "
                        "frame-ancestors 'none'"
                    ),
                    "strict-transport-security": "max-age=300",
                    "x-content-type-options": "nosniff",
                    "x-frame-options": "DENY",
                    "referrer-policy": "no-referrer",
                    "permissions-policy": "camera=(), microphone=()",
                }
            )


if __name__ == "__main__":
    unittest.main()
