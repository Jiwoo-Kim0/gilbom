"""미전송 event 를 담아 두는 SQLite Outbox."""

from __future__ import annotations

import os
import sqlite3
import threading
from dataclasses import dataclass

from . import codec
from .topics import TopicKind


@dataclass(frozen=True)
class OutboxRecord:
    event_id: str
    kind: TopicKind
    payload: dict


_SCHEMA = """
CREATE TABLE IF NOT EXISTS outbox (
    event_id     TEXT PRIMARY KEY,
    kind         TEXT NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    attempted_at TEXT,
    attempts     INTEGER NOT NULL DEFAULT 0,
    sent_at      TEXT,
    receipt_at   TEXT,
    ingest_status TEXT,
    incident_id  TEXT
);
CREATE INDEX IF NOT EXISTS outbox_pending ON outbox (sent_at, created_at);
"""

_RECEIPT_STATUSES = frozenset({"STORED", "MERGED", "DUPLICATE", "REJECTED"})
_SUCCESS_RECEIPT_STATUSES = frozenset({"STORED", "MERGED", "DUPLICATE"})


class Outbox:
    """미전송 event 저장소.

    발행 전에 저장하고 PUBACK을 받으면 sent, Backend Receipt를 받으면 receipt로 표시한다.
    재전송해도 event_id 와 occurred_at 은 그대로 두고 sent_at 만 갱신한다
    (서버는 event_id 로 멱등 처리 — 계약 §3.4, §7).
    """

    def __init__(self, path, *, receipt_required: bool = False) -> None:
        self.path = str(path)
        self.receipt_required = bool(receipt_required)
        parent = os.path.dirname(os.path.abspath(self.path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(_SCHEMA)
            self._migrate_receipt_columns()
            self._conn.commit()

    def _migrate_receipt_columns(self) -> None:
        """기존 Outbox 파일을 지우지 않고 Receipt 상태 열을 보강한다."""
        columns = {
            row["name"] for row in self._conn.execute("PRAGMA table_info(outbox)")
        }
        additions = {
            "receipt_at": "TEXT",
            "ingest_status": "TEXT",
            "incident_id": "TEXT",
        }
        for name, sql_type in additions.items():
            if name not in columns:
                self._conn.execute(
                    f"ALTER TABLE outbox ADD COLUMN {name} {sql_type}"
                )
        self._conn.execute(
            "CREATE INDEX IF NOT EXISTS outbox_receipt_pending"
            " ON outbox (receipt_at, created_at)"
        )

    def enqueue(self, event_id: str, kind: TopicKind, payload: dict) -> None:
        row = (
            event_id,
            TopicKind(kind).value,
            codec.encode(payload).decode("utf-8"),
            codec.utc_now(),
        )
        with self._lock:
            self._conn.execute(
                "INSERT OR IGNORE INTO outbox (event_id, kind, payload, created_at)"
                " VALUES (?, ?, ?, ?)",
                row,
            )
            self._conn.commit()

    def pending(
        self, attempted_before: str = None, limit: int = 500,
        *, require_receipt: bool = None,
    ) -> list:
        """아직 전송 확정되지 않은 Event를 오래된 순으로 돌려준다."""
        wait_receipt = self.receipt_required if require_receipt is None else require_receipt
        terminal_column = "receipt_at" if wait_receipt else "sent_at"
        sql = (
            "SELECT event_id, kind, payload FROM outbox WHERE "
            f"{terminal_column} IS NULL"
        )
        params = []
        if attempted_before is not None:
            sql += " AND (attempted_at IS NULL OR attempted_at <= ?)"
            params.append(attempted_before)
        sql += " ORDER BY created_at LIMIT ?"
        params.append(limit)
        with self._lock:
            rows = self._conn.execute(sql, params).fetchall()
        return [
            OutboxRecord(
                event_id=row["event_id"],
                kind=TopicKind(row["kind"]),
                payload=codec.decode(row["payload"].encode("utf-8")),
            )
            for row in rows
        ]

    def mark_attempted(self, event_id: str, at: str = None) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE outbox SET attempted_at = ?, attempts = attempts + 1"
                " WHERE event_id = ?",
                (at or codec.utc_now(), event_id),
            )
            self._conn.commit()

    def mark_sent(self, event_id: str, at: str = None) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE outbox SET sent_at = ? WHERE event_id = ?",
                (at or codec.utc_now(), event_id),
            )
            self._conn.commit()

    def record_receipt(
        self, event_id: str, ingest_status: str, *, incident_id=None, at: str = None
    ) -> bool:
        """Backend DB 처리 결과를 기록한다. 알 수 없는 Event면 False다."""
        if ingest_status not in _RECEIPT_STATUSES:
            raise ValueError(f"unknown ingest_status: {ingest_status!r}")
        with self._lock:
            cursor = self._conn.execute(
                "UPDATE outbox SET receipt_at = ?, ingest_status = ?, incident_id = ?"
                " WHERE event_id = ?",
                (at or codec.utc_now(), ingest_status, incident_id, event_id),
            )
            self._conn.commit()
        return cursor.rowcount == 1

    def pending_count(self, *, require_receipt: bool = None) -> int:
        wait_receipt = self.receipt_required if require_receipt is None else require_receipt
        terminal_column = "receipt_at" if wait_receipt else "sent_at"
        with self._lock:
            row = self._conn.execute(
                f"SELECT COUNT(*) FROM outbox WHERE {terminal_column} IS NULL"
            ).fetchone()
        return row[0]

    def prune_sent(self, before: str, *, require_receipt: bool = None) -> int:
        """오래 전에 전송 완료된 행 정리. 디스크가 무한히 늘지 않게 한다."""
        wait_receipt = self.receipt_required if require_receipt is None else require_receipt
        if wait_receipt:
            placeholders = ",".join("?" for _ in _SUCCESS_RECEIPT_STATUSES)
            sql = (
                "DELETE FROM outbox WHERE receipt_at IS NOT NULL AND receipt_at < ?"
                f" AND ingest_status IN ({placeholders})"
            )
            params = (before, *sorted(_SUCCESS_RECEIPT_STATUSES))
        else:
            sql = "DELETE FROM outbox WHERE sent_at IS NOT NULL AND sent_at < ?"
            params = (before,)
        with self._lock:
            cursor = self._conn.execute(sql, params)
            self._conn.commit()
        return cursor.rowcount

    def close(self) -> None:
        with self._lock:
            self._conn.close()
