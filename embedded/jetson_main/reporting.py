"""이상 보고 — 사진 저장 + GPS 좌표 태깅 (명세 4·5단계).

이 저장소의 책임은 **로컬에 남기는 것까지**다. 서버 전송과 웹 지도 핀 표시(명세 6단계)는
젯슨에 있는 통신 코드가 담당하고 보드에서 합친다 — 그 코드가 그대로 집어갈 수 있는
형식으로 남기는 것이 여기 목적이고, 이음새는 write_report() 한 함수뿐이다.

출력 (config reporting.output_dir 아래):
  events.jsonl              한 줄 = 한 건. 좌표·판정·사진 파일명이 들어 있다
  <kind>_<타임스탬프>.jpg   증거 사진 (save_images: false면 생략)

계층 분리는 다른 모듈과 같은 원칙이다:
  · ReportGate — 순수. '지금 보고할 것인가'(연속 프레임 + 쿨다운)만 판단한다.
    개발 PC의 pytest가 이걸 검증한다 (tests/test_reporting.py).
  · write_report — I/O. 파일 쓰기와 cv2 인코딩. 실패는 절대 예외로 올리지 않는다 —
    보고가 실패해서 순찰이 죽는 것이 가장 나쁜 결과다.
"""

import json
import logging
import os
import time
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger("robot.report")

EVENTS_FILE = "events.jsonl"        # 통신 코드가 tail 하는 파일 (프로토콜 상수)
KIND_DEFECT = "defect"              # 점자블록 파손 (명세 4단계)
KIND_MISSING = "missing_braille"    # 횡단보도 점자블록 미설치 (명세 5단계)
KIND_TRACE = "trace_session"        # 추종 세션 계측 — 이상 보고가 아니라 커버리지 기록


@dataclass
class TraceSession:
    """TRACE 한 세션의 계측 — '빠짐없이 탐지했는가'를 사후에 셀 수 있게 한다 (순수).

    왜 필요한가: 파손·미설치 **보고**만 남으면 아무것도 안 나온 주행이 '깨끗한 구역'인지
    '탐지가 안 돌았는지' 구별되지 않는다. 0804 실외에서 실제로 그 구별이 안 됐다.
    이 기록은 이상 보고가 아니라 커버리지 근거다 — kind가 따로인 이유다.

    세션은 INSPECT 왕복을 **포함한다**: FSM의 trace_started_at이 정차를 다녀와도
    리셋되지 않는 것과 같은 경계다 (정차 판정은 추종의 일부다).

    한계를 명시해 둔다 — 이 숫자는 '탐지를 몇 번 돌렸나'이지 '블록을 몇 개 봤나'가
    아니다. 후자는 '이 블록에 도착했다'가 관측 가능해야 하고, 그건 오도메트리가
    필요하다 (state_machine._inspect_cooldown_over 주석 / docs/ALGORITHMS.md N1).
    """

    entered_at: Optional[float] = None
    detections: int = 0            # 탐지 판정을 소비한 횟수
    defect_candidates: int = 0     # 그 중 파손 후보였던 횟수

    def count_detection(self, is_defect: Optional[bool]) -> None:
        """탐지 1회 결과를 먹인다. None(추론 못 함·검사기 없음)은 세지 않는다."""
        if is_defect is None:
            return
        self.detections += 1
        if is_defect:
            self.defect_candidates += 1

    def on_transition(self, prev, new, reason: str, now: float) -> Optional[dict]:
        """FSM 전이 훅에서 부른다 → 세션이 끝났으면 요약 dict, 아니면 None.

        문자열이 아니라 DriveState로 비교한다 — 상태 이름이 바뀌면 조용히 어긋나는
        대신 import가 깨지는 편이 낫다.
        """
        from driving.states import DriveState

        if new is DriveState.TRACE and self.entered_at is None:
            self.entered_at = now
            self.detections = self.defect_candidates = 0
            return None
        if (self.entered_at is not None
                and new not in (DriveState.TRACE, DriveState.INSPECT)):
            summary = {
                "duration_sec": round(now - self.entered_at, 2),
                "detections": self.detections,
                "defect_candidates": self.defect_candidates,
                "exit_state": new.value,
                "exit_reason": reason,
            }
            self.entered_at = None
            return summary
        return None


@dataclass
class ReportGate:
    """'지금 보고할 것인가' 판정 — 연속 프레임 확정 + 쿨다운 (순수, 시계 미접근).

    보고는 **되돌릴 수 없는 외부 출력**이라 주행 판단보다 엄격한 디바운스를 든다:
    한 프레임 오탐이 '이 횡단보도에 점자블록이 없다'는 기록을 남기면 지울 방법이 없다.
    FSM의 디바운스(시간 기준)와 달리 프레임 수 기준인 이유는 인지 주기가 보드에서
    추론 시간만큼 흔들리기 때문이다 — '연속 3프레임'은 그 흔들림과 무관하게 '세 번
    연속 같은 것을 봤다'를 뜻한다.

    쿨다운은 같은 대상의 중복 보고를 막는다. 하나의 횡단보도는 접근하는 동안 수십
    프레임에 걸쳐 계속 보이므로, 없으면 같은 지점을 수백 번 보고한다.
    """

    confirm_frames: int
    cooldown_sec: float
    _streak: int = 0
    _last_at: Optional[float] = None

    def should_report(self, flag: bool, now: float) -> bool:
        """이번 프레임의 판정(flag)을 먹이고 보고할지 답한다. 상태를 갱신한다."""
        if not flag:
            self._streak = 0
            return False
        self._streak += 1
        if self._streak < max(1, self.confirm_frames):
            return False
        if (self._last_at is not None
                and now - self._last_at < self.cooldown_sec):
            return False
        self._last_at = now
        self._streak = 0     # 보고했으면 다시 처음부터 — 쿨다운 직후 즉시 재보고 방지
        return True


def _timestamp_name(clock=time.time) -> str:
    """파일명용 타임스탬프 (로컬 시각). 보고 식별자로도 쓰인다."""
    return time.strftime("%Y%m%d_%H%M%S", time.localtime(clock()))


def _save_image(image, path: str) -> bool:
    """cv2로 JPEG 저장. cv2가 없거나(개발 PC) 실패하면 False — 예외를 올리지 않는다."""
    try:
        import cv2
        return bool(cv2.imwrite(path, image))
    except Exception:
        logger.warning("[보고] 사진 저장 실패: %s", path, exc_info=True)
        return False


def write_report(output_dir: str, kind: str, gps: Optional[dict],
                 image=None, save_images: bool = True, extra: Optional[dict] = None,
                 clock=time.time) -> Optional[str]:
    """보고 1건을 남긴다 → events.jsonl에 쓴 레코드(dict의 사진 경로) 또는 None.

    **실패해도 예외를 올리지 않는다.** 순찰 중이고, 보고 실패로 미션이 죽는 것보다
    한 건을 놓치는 편이 낫다. 실패는 경고 로그로만 남긴다.

    gps는 scripts/gps_reader.GpsReader.status()가 돌려주는 dict를 그대로 받는다.
    fix가 없으면 좌표 없이 기록한다 — 좌표가 없다고 파손 사실을 버릴 이유는 없고,
    통신 코드가 fix 필드를 보고 지도에 올릴지 판단할 수 있다.
    """
    stamp = _timestamp_name(clock)
    record = {
        "kind": kind,
        "at": stamp,
        "monotonic": round(time.monotonic(), 3),
        "fix": bool(gps.get("fix")) if gps else False,
        "lat": (gps or {}).get("lat"),
        "lon": (gps or {}).get("lon"),
        "sats": (gps or {}).get("sats"),
        "hdop": (gps or {}).get("hdop"),
        "image": None,
    }
    if extra:
        record.update(extra)

    try:
        os.makedirs(output_dir, exist_ok=True)
        if save_images and image is not None:
            name = f"{kind}_{stamp}.jpg"
            if _save_image(image, os.path.join(output_dir, name)):
                record["image"] = name
        with open(os.path.join(output_dir, EVENTS_FILE), "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError:
        logger.warning("[보고] 기록 실패 (%s) — 이번 건은 버린다", output_dir, exc_info=True)
        return None

    logger.info("[보고] %s — 좌표 %s, 사진 %s", kind,
                f"{record['lat']:.6f},{record['lon']:.6f}"
                if record["fix"] and record["lat"] is not None else "없음(fix 대기)",
                record["image"] or "없음")
    return record["image"]
