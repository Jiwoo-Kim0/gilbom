#!/usr/bin/env python3
"""주행 경로 로그(var/drive_trace.jsonl) 분석 — '왜 그렇게 움직였나'를 숫자로 낸다.

대시보드가 매 0.5초(또는 상태 전이마다) 한 줄씩 남기는 계측 기록을 읽어, 현장에서
눈으로 본 것을 확인·반박할 수 있는 표로 바꾼다. 야외 테스트 한 판이 끝나면 이걸
돌리는 것이 곧 그 판의 회고다.

    scripts/analyze_trace.py                 # 마지막 세션
    scripts/analyze_trace.py --session all   # 파일 안의 모든 세션
    scripts/analyze_trace.py --episodes      # 상태 에피소드를 시간순으로 나열

세션은 monotonic 시각 t 가 되감기는 지점으로 가른다 — 프로세스를 다시 띄우면 t 가
0 근처에서 다시 시작하기 때문이다.

내는 숫자는 넷이고, 전부 '순찰이 왜 끊겼나'를 향한다:
  · 상태 점유    — 어디에 시간을 썼나. CRUISE 보다 AVOID·DEAD_END 가 길면 그 판은
                   순찰이 아니라 몸부림이었다는 뜻이다
  · 회피 결말    — 회피가 어떻게 끝났나(정상 복귀 / 상한 만료 / 재정향 승격)
  · 미션 종료    — IDLE·ESTOP 으로 떨어진 사유별 횟수. 사람이 다시 가야 했던 횟수다
  · 빈 프레임    — 세그가 아무것도 못 잡은 틱의 비율 (perception_blank).
                   이 값이 높으면 그 판의 장애물·막다른길 판정은 전부 의심 대상이다
                   (perception/segmentation.py BlankFrameGate)
"""

import argparse
import collections
import json
import os
import sys

_ROBOT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_PATH = os.path.join(_ROBOT_ROOT, "var", "drive_trace.jsonl")

#: 로그 한 줄의 기록 간격(web_test.DrivingSession.TRACE_INTERVAL_SEC). 점유 시간을
#: '줄 수 × 간격'으로 환산하는 데만 쓴다 — 전이 줄은 간격과 무관하게 끼어들므로
#: 어림값이고, 그래서 아래 표는 초와 줄 수를 같이 낸다.
SAMPLE_SEC = 0.5

#: 주행이 사람 손을 기다리며 끝난 상태들 — '미션 종료' 집계 대상.
STOPPED_STATES = ("IDLE", "ESTOP")


def load(path: str) -> list:
    """JSONL 로드. 깨진 줄은 세어서 알리고 버린다 — 마지막 줄은 대개 쓰다 만 것이다."""
    rows, broken = [], 0
    with open(path, encoding="utf-8") as stream:
        for line in stream:
            try:
                rows.append(json.loads(line))
            except ValueError:
                broken += 1
    if broken:
        print(f"※ 읽지 못한 줄 {broken}개는 건너뛴다 (기록 중 잘린 줄)", file=sys.stderr)
    return rows


#: 이보다 큰 시각 간격은 '그 사이 로거가 안 돌았다' = 다른 판이다. 기록 간격이
#: 0.5초이고 세그 추론이 늦어져도 한 틱이 몇 초를 넘지는 않는다.
SESSION_GAP_SEC = 30.0


def split_sessions(rows: list) -> list:
    """프로세스 재기동 지점에서 세션을 가른다.

    되감김만 보면 안 된다 — 이 보드의 monotonic 은 **부팅 이후 시간**이라 프로세스를
    다시 띄워도 t 가 0으로 돌아가지 않는다. 그래서 앞으로 크게 뛴 간격도 경계로 쓴다.
    이걸 빠뜨리면 고치기 전 판과 고친 뒤 판이 한 세션으로 합쳐져, 변경이 먹었는지를
    보려고 돌린 분석이 정확히 그 비교를 못 하게 된다.
    """
    if not rows:
        return []
    sessions, current = [], [rows[0]]
    for previous, row in zip(rows, rows[1:]):
        gap = row["t"] - previous["t"]
        if gap < -1.0 or gap > SESSION_GAP_SEC:
            sessions.append(current)
            current = []
        current.append(row)
    sessions.append(current)
    return sessions


def episodes(rows: list) -> list:
    """연속된 같은 상태를 하나의 에피소드로 묶는다 → [(상태, 줄들)]."""
    if not rows:
        return []
    out, current = [], [rows[0]]
    for previous, row in zip(rows, rows[1:]):
        if row["state"] != previous["state"]:
            out.append(current)
            current = []
        current.append(row)
    out.append(current)
    return out


def _duration(episode: list) -> float:
    return episode[-1]["t"] - episode[0]["t"]


def report_occupancy(rows: list) -> None:
    counts = collections.Counter(row["state"] for row in rows)
    total = sum(counts.values())
    print("■ 상태 점유")
    for state, count in counts.most_common():
        print(f"    {state:9s} {count * SAMPLE_SEC:7.1f}초  {count:5d}줄"
              f"  {100.0 * count / total:5.1f}%")


def report_avoid(eps: list) -> None:
    """회피가 어떻게 끝났는지 — 다음 에피소드가 곧 그 결말이다."""
    outcomes = collections.Counter()
    durations = []
    for index, episode in enumerate(eps):
        if episode[0]["state"] != "AVOID":
            continue
        durations.append(_duration(episode))
        following = eps[index + 1][0] if index + 1 < len(eps) else None
        if following is None:
            outcomes["(로그 끝)"] += 1
        elif following["state"] == "DEAD_END":
            outcomes["→ 재정향(후진)"] += 1
        elif "재판단" in (following.get("reason") or ""):
            outcomes["상한 만료 → 경로 재판단"] += 1
        else:
            outcomes[f"→ {following['state']}"] += 1
    if not durations:
        print("■ 회피 — 없음")
        return
    durations.sort()
    print(f"■ 회피 {len(durations)}회 — 중앙값 {durations[len(durations) // 2]:.1f}초 ·"
          f" 최장 {durations[-1]:.1f}초")
    for outcome, count in outcomes.most_common():
        print(f"    {outcome:26s} {count:3d}회")


def report_stops(eps: list) -> None:
    """사람이 다시 가야 했던 횟수 — 사유별로 센다."""
    reasons = collections.Counter()
    for episode in eps:
        if episode[0]["state"] in STOPPED_STATES:
            reasons[(episode[0]["state"], episode[0].get("reason") or "?")] += 1
    if not reasons:
        print("■ 미션 종료 — 없음")
        return
    print(f"■ 미션 종료 {sum(reasons.values())}회")
    for (state, reason), count in reasons.most_common():
        print(f"    {count:3d}회  {state:6s} {reason[:64]}")


def report_blank(rows: list) -> None:
    """빈 프레임 — 세그가 아무것도 못 잡은 틱. 없는 벽의 정체다.

    옛 로그에는 이 필드가 없다. 그 경우 실기에서 관측된 서명으로 대신 센다:
    코스트맵 전 슬롯이 같은 값이고 인도가 안 보이는 틱 (BlankFrameGate 독스트링).
    """
    scored = [row for row in rows if "sidewalk" in row]
    if not scored:
        return
    if any("blank" in row for row in scored):
        blanks = [bool(row.get("blank")) for row in scored]
        source = "perception_blank"
    else:
        def looks_blank(row):
            costs = row.get("costs")
            return (costs is not None and len(set(costs)) == 1
                    and costs[0] is not None and costs[0] > 2.0
                    and row.get("sidewalk") is False)

        blanks = [looks_blank(row) for row in scored]
        source = "서명 추정(옛 로그)"
    runs, run = collections.Counter(), 0
    for is_blank in blanks:
        if is_blank:
            run += 1
        elif run:
            runs[run] += 1
            run = 0
    if run:
        runs[run] += 1
    share = 100.0 * sum(blanks) / len(blanks)
    print(f"■ 빈 프레임 {sum(blanks)}/{len(blanks)} ({share:.1f}%) — {source}")
    if runs:
        longest = max(runs)
        print(f"    연속 길이: 1~2줄 {runs[1] + runs[2]}회 ·"
              f" 3줄 이상 {sum(c for r, c in runs.items() if r >= 3)}회 ·"
              f" 최장 {longest}줄({longest * SAMPLE_SEC:.1f}초)")


def report_episodes(eps: list) -> None:
    print("■ 에피소드")
    for episode in eps:
        head = episode[0]
        phases = []
        for row in episode:
            phase = row.get("phase")
            if not phases or phases[-1][0] != phase:
                phases.append([phase, 1])
            else:
                phases[-1][1] += 1
        trail = ("  | " + " ".join(f"{p}x{c}" for p, c in phases)
                 if head.get("phase") else "")
        print(f"    {head['t']:8.1f} {head['state']:9s} {_duration(episode):5.1f}초"
              f"  {(head.get('reason') or '')[:52]:52s}{trail}")


def report(rows: list, label: str, show_episodes: bool) -> None:
    eps = episodes(rows)
    print(f"\n===== {label} — {rows[0]['at']} ~ {rows[-1]['at']}"
          f" ({len(rows)}줄) =====")
    report_occupancy(rows)
    report_avoid(eps)
    report_stops(eps)
    report_blank(rows)
    if show_episodes:
        report_episodes(eps)


#: 조향 변화율이 관측되는 유일한 창. 기록은 0.5초 주기지만 **상태 전이 줄은 그 주기를
#: 건너뛰고** 찍히므로, 간격이 이보다 짧은 줄 쌍만이 한 틱 수준의 변화를 담고 있다.
#: 0.5초 간격 줄로는 슬루율 상한(100°/s면 그 사이 50°)에 애초에 닿을 수 없다.
STEER_FINE_DT_SEC = 0.2

#: '상한에 붙었다'로 볼 비율. 리미터가 물리면 |Δ조향|/dt 가 설정값에 수렴하는데,
#: dt 가 로그 해상도만큼 어긋나므로 정확히 같은 값이 되지는 않는다.
STEER_CAP_RATIO = 0.9


def report_steering(rows: list, cap_deg_per_s: float) -> None:
    """조향 슬루율이 **실제로 물렸는지** 센다 — 상한을 올릴 근거가 있는지의 답.

    로그에는 리미터를 통과한 **명령값**만 남는다(서보 위치 피드백이 없다). 그래서
    '서보가 따라왔는가'는 알 수 없고, '**FSM 이 상한보다 빠르게 꺾고 싶어 했는가**'만
    알 수 있다 — 상한을 올릴지 말지에는 그것으로 충분하다.
    판정: |Δ조향|/dt 가 설정 상한 근처에 몰려 있으면 리미터가 기동을 자르고 있었다.
    """
    fine = []
    for previous, current in zip(rows, rows[1:]):
        gap = current.get("t", 0.0) - previous.get("t", 0.0)
        before_deg, after_deg = previous.get("steer"), current.get("steer")
        if (0.0 < gap < STEER_FINE_DT_SEC
                and before_deg is not None and after_deg is not None):
            fine.append((abs(after_deg - before_deg) / gap,
                         abs(after_deg - before_deg), previous.get("state"),
                         current.get("state")))
    print(f"■ 조향 슬루율 (설정 상한 {cap_deg_per_s:.0f}°/s)")
    if not fine:
        print("    관측 창 없음 — 전이 줄이 없다(기동이 없었거나 기록이 짧다)")
        return
    threshold = cap_deg_per_s * STEER_CAP_RATIO
    capped = [f for f in fine if f[0] >= threshold]
    fastest = sorted(fine, reverse=True)[:5]
    print(f"    관측 창 {len(fine)}건 (전이 줄, 간격 {STEER_FINE_DT_SEC}초 미만)")
    print(f"    상한의 {STEER_CAP_RATIO:.0%} 이상: {len(capped)}건 "
          f"({100 * len(capped) / len(fine):.0f}%) ← 이게 크면 상한이 기동을 자르고 있다")
    print(f"    최댓값 {fastest[0][0]:.0f}°/s · 최대 변화량 "
          f"{max(f[1] for f in fine):.0f}°")
    for rate, delta, from_state, to_state in fastest:
        print(f"      {rate:6.0f}°/s  {delta:5.1f}°  {from_state}→{to_state}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("path", nargs="?", default=DEFAULT_PATH)
    parser.add_argument("--session", default="last",
                        help="'last'(기본) / 'all' / 0부터 세는 번호")
    parser.add_argument("--episodes", action="store_true",
                        help="상태 에피소드를 시간순으로 나열한다")
    parser.add_argument("--steering", type=float, nargs="?", const=-1.0, default=None,
                        metavar="상한",
                        help="조향 슬루율이 실제로 물렸는지 센다. 값을 주지 않으면 "
                             "config.yaml 의 driving.steer_rate_deg_per_s 를 읽는다")
    args = parser.parse_args()

    sessions = split_sessions(load(args.path))
    if not sessions:
        parser.error(f"기록이 없다: {args.path}")
    if args.session == "all":
        chosen = list(enumerate(sessions))
    elif args.session == "last":
        chosen = [(len(sessions) - 1, sessions[-1])]
    else:
        index = int(args.session)
        chosen = [(index, sessions[index])]
    cap = args.steering
    if cap is not None and cap < 0:
        import yaml   # 분석 전용 — 주행 경로에는 없는 의존이다
        with open(os.path.join(_ROBOT_ROOT, "config.yaml"), encoding="utf-8") as stream:
            cap = yaml.safe_load(stream)["driving"]["steer_rate_deg_per_s"]
    for index, rows in chosen:
        report(rows, f"세션 {index + 1}/{len(sessions)}", args.episodes)
        if cap is not None:
            report_steering(rows, float(cap))


if __name__ == "__main__":
    main()
