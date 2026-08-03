#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "infra/scripts/generate-database-content-manifest.sh"


class DatabaseContentManifestTest(unittest.TestCase):
    def mock_psql(self, root: Path) -> Path:
        executable = root / "psql"
        executable.write_text(
            textwrap.dedent(
                """\
                #!/bin/sh
                set -eu
                command_text=""
                previous=""
                for argument in "$@"; do
                  if [ "$previous" = "--command" ]; then
                    command_text=$argument
                    break
                  fi
                  previous=$argument
                done
                case "$command_text" in
                  *information_schema.tables*)
                    printf '%s\\n' auth.admin_user report.anomaly_report
                    ;;
                  *information_schema.sequences*)
                    printf '%s\\n' auth.admin_user_id_seq
                    ;;
                  *'count(*) FROM auth.admin_user'*)
                    printf '2\\n'
                    ;;
                  *'count(*) FROM report.anomaly_report'*)
                    printf '0\\n'
                    ;;
                  *'FROM auth.admin_user AS row_value'*)
                    printf '%s\\n' '{"id":1,"email":"a"}' '{"id":2,"email":"b"}'
                    ;;
                  *'FROM report.anomaly_report AS row_value'*)
                    ;;
                  *'FROM auth.admin_user_id_seq'*)
                    printf '%s\\n' '{"last_value":2,"is_called":true}'
                    ;;
                  *)
                    echo "unexpected SQL: $command_text" >&2
                    exit 2
                    ;;
                esac
                """
            ),
            encoding="utf-8",
        )
        executable.chmod(0o755)
        return executable

    def invoke(
        self,
        psql: Path,
        schemas: str = "auth,report",
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PSQL_BIN": str(psql),
                "PGHOST": "127.0.0.1",
                "PGUSER": "fixture",
                "PGDATABASE": "fixture",
                "PGPASSWORD": "not-a-real-secret",
                "DATABASE_CONTENT_SCHEMAS": schemas,
            }
        )
        return subprocess.run(
            ["sh", str(SCRIPT)],
            cwd=ROOT,
            env=environment,
            text=True,
            capture_output=True,
        )

    def test_manifest_hashes_each_table_in_stable_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            psql = self.mock_psql(Path(temporary))
            result = self.invoke(psql)
            self.assertEqual(result.returncode, 0, result.stderr)
            user_rows = '{"id":1,"email":"a"}\n{"id":2,"email":"b"}\n'
            user_hash = hashlib.sha256(user_rows.encode()).hexdigest()
            empty_hash = hashlib.sha256(b"").hexdigest()
            sequence_rows = '{"last_value":2,"is_called":true}\n'
            sequence_hash = hashlib.sha256(
                sequence_rows.encode()
            ).hexdigest()
            self.assertIn(
                f"{user_hash}  2  table:auth.admin_user",
                result.stdout,
            )
            self.assertIn(
                f"{empty_hash}  0  table:report.anomaly_report",
                result.stdout,
            )
            self.assertIn(
                f"{sequence_hash}  1  sequence:auth.admin_user_id_seq",
                result.stdout,
            )

    def test_invalid_schema_name_fails_before_query(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            psql = self.mock_psql(Path(temporary))
            result = self.invoke(psql, "auth,public);DROP TABLE x;--")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("invalid schema name", result.stderr)


if __name__ == "__main__":
    unittest.main()
