#!/usr/bin/env python3
"""저장된 주행 하나를 되짚는다 — 상태 체류·전이·정지 원인.

주행 중에는 scripts/web_test.py 가 정지마다 stops.jsonl 을 쌓는다. 이 스크립트는
같은 분석(driving/stop_analysis.py)을 **끝난 주행에** 다시 돌린다. 쓰임이 둘이다:
  · 그날 주행 전체를 한 장으로 본다 (어디서 시간을 썼나, 무엇이 반복됐나)
  · 분석 규칙을 고친 뒤 예전 주행에 다시 물어본다 (기록은 그대로 남아 있으므로)

    scripts/analyze_run.py                       # var/runs/latest
    scripts/analyze_run.py var/runs/20260805_224318
    scripts/analyze_run.py var/drive_trace.jsonl # 실행 구분 전의 옛 기록도 읽는다
    scripts/analyze_run.py --since 2026-08-05T13:43   # 그중 한 구간만

옛 drive_trace.jsonl 은 여러 실행이 한 파일에 이어 붙어 있다 — --since 로 자르거나,
t(monotonic)가 뒤로 뛰는 지점을 실행 경계로 본다(아래 split_sessions).
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import sys

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_ROBOT_ROOT = os.path.dirname(_SCRIPT_DIR)
sys.path.insert(0, _ROBOT_ROOT)

from driving import stop_analysis


def read_records(path: str, since: str = "") -> list:
    """트레이스 JSONL 을 읽는다. 깨진 줄은 세고 넘어간다.

    마지막 줄은 프로세스가 죽는 순간 반쪽만 쓰여 있을 수 있다 — 실제로 0805
    기록에 한 줄 있었다. 그 한 줄 때문에 분석 전체가 죽으면 안 된다.
    """
    records, broken = [], 0
    with open(path, "r", encoding="utf-8", errors="replace") as stream:
        for line in stream:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except ValueError:
                broken += 1
                continue
            if since and record.get("at", "") < since:
                continue
            records.append(record)
    if broken:
        print(f"※ 깨진 줄 {broken}개는 건너뜀")
    return records


def split_sessions(records: list) -> list:
    """t(monotonic)가 뒤로 뛰면 다른 실행이다 — 그 지점에서 자른다."""
    sessions, current, previous_t = [], [], None
    for record in records:
        t = record.get("t")
        if previous_t is not None and t is not None and t < previous_t:
            sessions.append(current)
            current = []
        current.append(record)
        previous_t = t
    if current:
        sessions.append(current)
    return sessions


#: 트레이스 한 줄 사이의 간격 — web_test.DrivingSession.TRACE_INTERVAL_SEC 와 같다.
#: 체류 시간을 프레임 수로 재기 위한 값이라 여기 상수로 둔다 (web_test 를 import 하면
#: cv2·fastapi 가 딸려 와서 개발 PC 에서 이 스크립트가 안 돈다).
TRACE_INTERVAL_SEC = 0.5


def summarize(records: list) -> None:
    interval = TRACE_INTERVAL_SEC
    print(f"프레임 {len(records)}개 · {records[0].get('at')} → {records[-1].get('at')}")
    print("\n=== 상태별 체류 ===")
    counts = collections.Counter(r.get("state") for r in records)
    for state, count in counts.most_common():
        print(f"  {state:10} {count * interval:7.1f}초  ({count / len(records):5.1%})")

    print("\n=== 상태 전이 ===")
    transitions = collections.Counter()
    previous = None
    for record in records:
        if previous and record.get("state") != previous.get("state"):
            transitions[(previous["state"], record["state"],
                         (record.get("reason") or "")[:50])] += 1
        previous = record
    for (before, after, why), count in transitions.most_common(15):
        print(f"  {count:3}회  {before:9}→{after:9}  {why}")


def analyze_stops(records: list) -> None:
    """정지 전이를 찾아 주행 중과 **같은 분석**을 다시 돌린다."""
    print("\n=== 정지 분석 ===")
    found = 0
    previous = None
    for index, record in enumerate(records):
        state = record.get("state")
        if (previous and state != previous.get("state")
                and state in stop_analysis.STOP_STATES
                and previous.get("state") not in stop_analysis.STOP_STATES):
            found += 1
            # 정지 프레임 **포함**해서 자른다 — 정지를 만든 장면(차도가 코리도를
            # 덮은 그 프레임)이 근거의 핵심이라 빼면 '원인 불명'이 된다.
            window = stop_analysis.window_before(
                records[:index + 1], record.get("t") or 0.0)
            analysis = stop_analysis.analyze_stop(
                window, state_from=previous["state"], state_to=state,
                reason=record.get("reason") or "", at=record.get("at", ""))
            print(f"\n[{found}] {analysis.at}  {analysis.state_from} → "
                  f"{analysis.state_to}")
            print(f"     사유: {analysis.reason}")
            print(f"     판정: {analysis.cause}  (근거 {analysis.frames}프레임)")
            for finding in analysis.findings:
                knob = f"  → {finding.knob}" if finding.knob else ""
                print(f"       · {finding.headline}: {finding.evidence}{knob}")
        previous = record
    if not found:
        print("  (정지 없음 — 이 구간에서 주행이 끝나지 않았다)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", nargs="?",
                        default=os.path.join(_ROBOT_ROOT, "var", "runs", "latest"),
                        help="주행 디렉터리(var/runs/<id>) 또는 trace.jsonl 경로")
    parser.add_argument("--since", default="",
                        help="이 UTC 시각 이후만 (예: 2026-08-05T13:43)")
    parser.add_argument("--all-sessions", action="store_true",
                        help="한 파일에 여러 실행이 섞여 있을 때 전부 분석 "
                             "(기본은 마지막 실행만)")
    args = parser.parse_args()

    path = args.path
    if os.path.isdir(path):
        path = os.path.join(path, "trace.jsonl")
    if not os.path.exists(path):
        parser.error(f"기록이 없다: {path}")

    records = read_records(path, args.since)
    if not records:
        parser.error("읽을 기록이 없다 (--since 가 너무 늦지 않은지 확인)")

    sessions = split_sessions(records)
    if len(sessions) > 1 and not args.all_sessions:
        print(f"※ 이 파일에 실행 {len(sessions)}개가 섞여 있다 — 마지막 것만 본다 "
              f"(전부 보려면 --all-sessions)")
        sessions = sessions[-1:]
    for number, session in enumerate(sessions, 1):
        if len(sessions) > 1:
            print(f"\n{'=' * 60}\n실행 {number}/{len(sessions)}\n{'=' * 60}")
        summarize(session)
        analyze_stops(session)


if __name__ == "__main__":
    main()
