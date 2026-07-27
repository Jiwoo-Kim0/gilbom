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
    sent_at      TEXT
);
CREATE INDEX IF NOT EXISTS outbox_pending ON outbox (sent_at, created_at);
"""


class Outbox:
    """미전송 event 저장소.

    발행 전에 저장하고 PUBACK 을 받았을 때만 sent 로 표시한다.
    재전송해도 event_id 와 occurred_at 은 그대로 두고 sent_at 만 갱신한다
    (서버는 event_id 로 멱등 처리 — 계약 §3.4, §7).
    """

    def __init__(self, path) -> None:
        self.path = str(path)
        parent = os.path.dirname(os.path.abspath(self.path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(_SCHEMA)
            self._conn.commit()

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

    def pending(self, attempted_before: str = None, limit: int = 500) -> list:
        """아직 PUBACK 을 못 받은 event 를 오래된 순으로."""
        sql = "SELECT event_id, kind, payload FROM outbox WHERE sent_at IS NULL"
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

    def pending_count(self) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL"
            ).fetchone()
        return row[0]

    def prune_sent(self, before: str) -> int:
        """오래 전에 전송 완료된 행 정리. 디스크가 무한히 늘지 않게 한다."""
        with self._lock:
            cursor = self._conn.execute(
                "DELETE FROM outbox WHERE sent_at IS NOT NULL AND sent_at < ?",
                (before,),
            )
            self._conn.commit()
        return cursor.rowcount

    def close(self) -> None:
        with self._lock:
            self._conn.close()
