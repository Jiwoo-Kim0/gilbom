"""events 4종 판정·발행 — incident · safety · battery · mission.  [F-003, F-004, F-007]

네 사건은 트리거가 서로 달라서 한 모듈에 모으되 경로를 나눠 둔다.
  safety   메인 루프 tick 마다 장애물 정지 여부를 보고 2단계로 확정한다 (§10.2).
           주행 계층이 스스로 세운 정지(차도 침범·경계 소실)는 bridge 가
           report_safety() 로 직접 알린다 — 2단계 확정을 거칠 사건이 아니다
  battery  RobotState 가 판정한 등급이 바뀌는 순간 (§9)
  incident 탐지를 확정한 쪽이 observe_incident() 로 값을 얼리고, 증거 사진 업로드가
           끝난 뒤 report_incident() 로 발행한다 (§8). 업로드 자체는 evidence.py 몫이다 —
           사진을 기다리며 로봇이 서 있으면 안 된다
  mission  미션이 전이할 때 R-5 가 mission_*() 을 부른다 (§11)
payload 조립은 하지 않는다. 값만 모아 factory 의 event 빌더에 넘긴다.
저장·재전송·멱등 처리도 여기서 하지 않는다 — client.publish() 가 event 4종을
Outbox 로 태우고, 재전송 때 event_id·occurred_at 을 그대로 지킨다 (§7).
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §7~§11
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from comms import TopicKind
from comms.codec import new_uuid, utc_now
from communication.robot_state import SAFE_STOPPABLE_MODES

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class IncidentObservation:
    """결함 관측 1건을 **관측 시점의 값으로** 얼린 것 (계약 §8).

    증거 사진 업로드가 끝난 뒤에 발행되므로, 발행 시점의 로봇 상태를 읽으면 안 된다:
    그때 로봇은 이미 다른 곳에 있고 미션이 끝났을 수도 있다. seq 만은 여기 없다 —
    발행 순간에 매겨야 발행 순서와 어긋나지 않는다 (§3.5).
    """

    event_id: str
    detection: dict
    occurred_at: str
    captured_at: str
    image_width: int
    image_height: int
    location_valid: bool
    lat: float
    lon: float
    zone_id: str
    mission_id: str

#: 배터리 등급의 높낮이. 올라가는 전이의 이름이 BATTERY_RECOVERED 다 (§9.3).
_BATTERY_RANK = {"CRITICAL": 0, "LOW": 1, "NORMAL": 2}

#: health 멤버 → events/safety 의 reason_code (§10.5).
SENSOR_REASON_CODES = {"lidar": "LIDAR_FAULT", "motor": "MOTOR_FAULT"}

#: 최초 감지 후 재확인까지 (계약 §10.2). config.yaml 의 safety 절이 덮어쓴다.
OBSTACLE_CONFIRM_SEC = 3.0


class EventPublisher:
    """event 4종을 언제 어떤 값으로 보낼지 정한다.

    메인 루프가 매 tick poll() 을 부르고, 탐지·미션 쪽은 직접 메서드를 부른다.
    """

    def __init__(
        self,
        client,
        state,
        *,
        obstacle_source=None,
        gps=None,
        model_name: str = None,
        model_version: str = None,
        obstacle_confirm_sec: float = OBSTACLE_CONFIRM_SEC,
        stop_motor=None,
        sensor_fault_stops_driving: bool = True,
        monotonic=time.monotonic,
        now=utc_now,
    ) -> None:
        self._client = client
        self._state = state
        #: () -> (장애물 있음?, 거리 m 또는 None). LiDAR 시절의 이름을 버린 이유는
        #: 이 로봇에 거리 센서가 없기 때문이다 — 지금은 세그멘테이션이 본 장애물 앞에
        #: 주행 FSM 이 서 있는지(AVOID 정지)를 그대로 넘겨준다 (bridge.py). 판정 시각과
        #: 2단계 확정(§10.2)은 그대로다: 거리만 null 로 나간다.
        self._obstacle_source = obstacle_source
        self._gps = gps
        self._model_name = model_name
        self._model_version = model_version
        self._obstacle_confirm_sec = obstacle_confirm_sec
        #: 장애물 최초 감지 때 부른다. MQTT 보다 먼저 물리 정지를 시키는 자리다 (§10.1).
        self._stop = stop_motor
        #: LiDAR·모터가 FAULT 인 동안 주행을 막을지. 눈이 먼 채로 굴러가지 않게 하는
        #: 안전 정책이라 기본은 켬이다. 장치 없는 개발 PC 에서 주행 흐름을 흉내 낼 때만 끈다.
        self._sensor_fault_stops_driving = sensor_fault_stops_driving
        self._monotonic = monotonic
        self._now = now
        # 장애물 발행 기록. 확정 여부는 RobotState 가 들고 있다 (§10.2).
        self._obstacle_since = None
        self._obstacle_occurred_at = None
        self._obstacle_mode_before = None
        #: 현재 FAULT 로 알린 health 멤버. 반복 발행을 막는다.
        self._sensor_faults = set()
        #: 마지막으로 본 배터리 등급. RobotState 도 UNKNOWN 으로 시작한다.
        self._battery_grade = "UNKNOWN"
        self._battery_sensor_fault = False

    # -- 주기 판정 --------------------------------------------------------

    def poll(self) -> None:
        """메인 루프가 매 tick 부른다. 판정은 전부 여기 안에서 한다.

        세 갈래가 스냅샷 하나를 나눠 쓴다 — 한 tick 안에서 서로 다른 시점의 값을
        섞어 보지 않게 하고, health 프로브도 tick 당 한 번으로 묶는다.
        """
        snapshot = self._state.snapshot()
        for check in (self._poll_obstacle, self._poll_sensor_health, self._poll_battery):
            try:
                check(snapshot)
            except Exception:
                # 한 갈래가 넘어져도 나머지 사건과 메인 루프는 계속 간다.
                log.exception("%s failed", check.__name__)

    # -- events/safety — 장애물 2단계 (계약 §10.2) --------------------------

    def _poll_obstacle(self, snapshot) -> None:
        obstacle, distance_m = self._read_obstacle()
        if self._obstacle_since is None:
            if obstacle:
                self._begin_obstacle(snapshot)
            return
        if not obstacle:
            self._release_obstacle(snapshot, distance_m)
            return
        if not snapshot.obstacle_confirmed and (snapshot.mode, snapshot.reason_code) != (
            "SAFE_STOP",
            "OBSTACLE",
        ):
            # 확정 전에 다른 사유가 우리를 밀어냈다(예: 관리자 e-stop). 이 감지는 없던 것으로
            # 하고 처음부터 다시 본다 — 안 그러면 그 사유가 풀린 뒤 장애물이 그대로인데도
            # 다시 서지 못한다.
            self._forget_obstacle()
            return
        if self._monotonic() - self._obstacle_since < self._obstacle_confirm_sec:
            return  # 아직 재확인 시점 전이다.
        # 대기시간이 지난 뒤 '다시 봤더니' 여전히 있다 — 이 tick 의 읽기가 재확인이다.
        # 시간만 재고 발행하면 이미 치워진 장애물로 사건을 만든다.
        if not self._state.confirm_obstacle():
            return  # 이미 확정한 사건이다. 프레임마다 반복 발행하지 않는다.
        self._publish_safety(
            "SAFETY_STOPPED",
            # 로봇이 실제로 선 시각이다. 확정 시각이 아니다 (§10.3).
            occurred_at=self._obstacle_occurred_at,
            distance_m=distance_m,
            mode_before=self._obstacle_mode_before,
            # 방금 전이한 결과라 스냅샷보다 새 값이다.
            mode_after=self._state.mode,
        )

    def _begin_obstacle(self, snapshot) -> None:
        """최초 감지 — 모터를 세우고 State 만 알린다. Event 는 재확인 뒤다 (§10.2).

        정지가 발행보다 먼저다 (§10.1·§16-10).
        """
        self._stop_motor()
        if not self._state.obstacle_detected():
            # 다른 사유로 이미 서 있다. 그 사유가 풀린 뒤 다시 감지되는 때가 최초 감지다 —
            # 여기서 시각을 붙잡아 두면 그 사이 사건이 통째로 죽는다.
            return
        self._obstacle_mode_before = snapshot.mode
        self._obstacle_occurred_at = self._now()
        self._obstacle_since = self._monotonic()

    def _forget_obstacle(self) -> None:
        self._obstacle_since = None
        self._obstacle_occurred_at = None
        self._obstacle_mode_before = None

    def _stop_motor(self) -> None:
        """정지 호출이 실패해도 메인 루프를 죽이지 않는다."""
        if self._stop is None:
            return
        try:
            self._stop()
        except Exception:
            log.exception("failed to stop the motor")

    def _release_obstacle(self, snapshot, distance_m) -> None:
        # 확정 여부와 직전 mode 는 obstacle_cleared() 가 바꾸기 전 값이어야 한다.
        # STOPPED 를 내지 않은 짧은 정지에는 CLEARED 도 내지 않는다 (§10.2).
        confirmed = snapshot.obstacle_confirmed
        mode_before = snapshot.mode
        released = self._state.obstacle_cleared()
        # 장애물은 실제로 사라졌으니 다음 감지는 새 사건이다.
        self._forget_obstacle()
        # 다른 사유로 여전히 서 있으면 해제가 아니다 — 알리면 서버가 안전 상황이
        # 끝난 것으로 읽는데 로봇은 그대로 멈춰 있다.
        if confirmed and released:
            self._publish_safety(
                "SAFETY_CLEARED",
                occurred_at=self._now(),
                distance_m=distance_m,
                mode_before=mode_before,
                mode_after=self._state.mode,
            )

    def _read_obstacle(self) -> tuple:
        """(obstacle, distance_m). 읽을 수 없으면 장애물 없음으로 본다."""
        reading = self._obstacle_source() if self._obstacle_source is not None else None
        if not reading:
            return False, None
        obstacle, distance_m = reading
        return bool(obstacle), distance_m

    # -- events/safety — 센서 고장 (계약 §10.5) ----------------------------

    def _poll_sensor_health(self, snapshot) -> None:
        health = snapshot.health_block
        for member, reason_code in SENSOR_REASON_CODES.items():
            faulty = health.get(member) == "FAULT"
            if faulty == (member in self._sensor_faults):
                continue  # 상태가 그대로다. 반복 발행하지 않는다.
            mode_before = snapshot.mode
            if faulty:
                self._sensor_faults.add(member)
                # 정지가 발행보다 먼저다 (§16-10). 서 있는 로봇은 밀지 않는다 —
                # 판정은 _halt_on_sensor_fault 안에 있다.
                self._halt_on_sensor_fault()
            else:
                self._sensor_faults.discard(member)
                self._release_after_sensor_fault(snapshot, reason_code)
            self._publish_safety(
                "SENSOR_FAULT" if faulty else "SENSOR_RECOVERED",
                occurred_at=self._now(),
                distance_m=None,
                mode_before=mode_before,
                # 방금 전이한 결과라 스냅샷보다 새 값이다.
                mode_after=self._state.mode,
                reason_code=reason_code,
                auto_resume_allowed=not faulty,
            )
        # 기동 때부터 고장이던 장치는 위 전이 판정을 이미 지나갔다. 그 뒤에 주행이
        # 시작되는 경로(START_MISSION)가 있으므로 매 tick 다시 본다.
        self._halt_on_sensor_fault()

    def _halt_on_sensor_fault(self) -> None:
        """LiDAR·모터가 고장인 채로 굴러가지 않게 한다.

        주행 중일 때만 세운다. 서 있는 로봇까지 SAFE_STOP 으로 밀면 장치가 하나도
        없는 개발 PC 가 부팅하자마자 안전 정지에 갇히고, START_MISSION 이 통째로 막힌다.
        """
        if not (self._sensor_fault_stops_driving and self._sensor_faults):
            return
        if self._state.mode != "PATROLLING":
            return
        for member, reason_code in SENSOR_REASON_CODES.items():
            if member not in self._sensor_faults:
                continue
            self._stop_motor()
            self._state.safe_stop(reason_code)
            return

    def _release_after_sensor_fault(self, snapshot, reason_code: str) -> None:
        """그 고장 때문에 서 있었고 다른 고장도 남지 않았으면 정지를 푼다."""
        if self._sensor_faults:
            return  # 다른 장치가 아직 고장이다. 지금 풀면 눈이 반쯤 먼 채로 나간다.
        if (snapshot.mode, snapshot.reason_code) != ("SAFE_STOP", reason_code):
            return  # 다른 사유로 서 있다 — 이 복구가 풀 정지가 아니다.
        self._state.release_safe_stop()

    def _publish_safety(
        self,
        event_type: str,
        *,
        occurred_at: str,
        distance_m,
        mode_before: str,
        mode_after: str,
        reason_code: str = "OBSTACLE",
        auto_resume_allowed: bool = True,
    ) -> dict:
        payload = self._client.messages.safety_event(
            event_type=event_type,
            occurred_at=occurred_at,
            reason_code=reason_code,
            mode_before=mode_before,
            mode_after=mode_after,
            auto_resume_allowed=auto_resume_allowed,
            distance_m=distance_m,
            mission_id=self._state.mission_id,
        )
        self._client.publish(TopicKind.EVENT_SAFETY, payload)
        return payload

    def report_safety(
        self,
        event_type: str,
        reason_code: str,
        *,
        mode_before: str,
        auto_resume_allowed: bool = False,
    ) -> dict:
        """주행 계층이 확정한 안전 사건 1건을 그대로 알린다 (bridge 가 부른다).

        장애물(§10.2)은 여기 오지 않는다 — 그건 _poll_obstacle 이 2단계로 확정한다.
        여기 오는 것은 인지가 이미 판정을 끝낸 사건(차도 침범 위험·인도 경계 소실)이라
        재확인 대기가 없다. 전이는 부르기 전에 끝나 있어야 한다 — mode_after 로
        지금 상태를 그대로 싣기 때문이다 (§10.3).
        """
        return self._publish_safety(
            event_type,
            occurred_at=self._now(),
            distance_m=None,          # 거리 센서가 없다 (§10.4 — 해당 없으면 null)
            mode_before=mode_before,
            mode_after=self._state.mode,
            reason_code=reason_code,
            auto_resume_allowed=auto_resume_allowed,
        )

    # -- events/battery (계약 §9) -----------------------------------------

    def _poll_battery(self, snapshot) -> None:
        """등급이 바뀌는 순간 1회. 판정·히스테리시스는 RobotState 안에 있다."""
        previous, current = self._battery_grade, snapshot.battery_state
        if current == previous:
            return
        self._battery_grade = current
        event_type = self._battery_event_type(previous, current)
        if event_type is None:
            return
        threshold_pct = self._battery_threshold(event_type, current)
        # 조치를 먼저 하고 그 결과를 action_taken 에 담는다 (§9.3).
        self._apply_battery_action(current)
        after = self._state.snapshot()  # 조치 후 값이라 앞의 스냅샷을 재사용할 수 없다
        payload = self._client.messages.battery_event(
            event_type=event_type,
            occurred_at=self._now(),
            previous_state=previous,
            # 등급을 판정한 그 표본을 그대로 싣는다 — state·telemetry 와 같은 값이다.
            battery_valid=snapshot.battery_valid,
            battery_percent=snapshot.battery_percent,
            battery_state=current,
            threshold_pct=threshold_pct,
            action_robot_mode=after.mode,
            action_mission_state=after.mission_state,
            mission_id=after.mission_id,
        )
        self._client.publish(TopicKind.EVENT_BATTERY, payload)
        if event_type == "BATTERY_SENSOR_FAULT":
            self._battery_sensor_fault = True
        elif event_type == "BATTERY_SENSOR_RECOVERED":
            self._battery_sensor_fault = False

    def _battery_event_type(self, previous: str, current: str):
        """전이 이름 (§9.3). 알릴 사건이 아니면 None."""
        if current == "UNKNOWN":
            return "BATTERY_SENSOR_FAULT"
        if previous == "UNKNOWN":
            if self._battery_sensor_fault:
                return "BATTERY_SENSOR_RECOVERED"
            # 기동 후 첫 판정이다. 낮으면 알리고 정상이면 알릴 사건이 아니다.
            return {"LOW": "BATTERY_LOW", "CRITICAL": "BATTERY_CRITICAL"}.get(current)
        if _BATTERY_RANK[current] > _BATTERY_RANK[previous]:
            # 등급이 올라갔다. LOW→NORMAL 의 이름이 BATTERY_RECOVERED 다.
            return "BATTERY_RECOVERED"
        return "BATTERY_LOW" if current == "LOW" else "BATTERY_CRITICAL"

    def _battery_threshold(self, event_type: str, current: str):
        """그 전이를 일으킨 임계값. 해당 없으면 None."""
        limits = self._state.battery_thresholds
        if event_type == "BATTERY_LOW":
            return limits.low_pct
        if event_type == "BATTERY_CRITICAL":
            return limits.critical_pct
        if event_type == "BATTERY_RECOVERED":
            if current == "NORMAL":
                return limits.low_recover_pct
            return limits.critical_recover_pct
        return None  # 센서 고장·복구는 임계값과 무관하다.

    def _apply_battery_action(self, grade: str) -> None:
        """등급이 떨어졌을 때의 조치.

        미션 상태는 건드리지 않는다 — 미션 전이 정책은 R-5 다.
        action_taken.mission_state 에는 조치 후 현재 값이 그대로 실린다.
        """
        mode = self._state.mode
        if grade == "CRITICAL" and mode in SAFE_STOPPABLE_MODES:
            self._state.safe_stop("CRITICAL_BATTERY")
        elif grade == "LOW" and mode == "PATROLLING":
            self._state.pause("LOW_BATTERY")

    # -- events/incident (계약 §8) ----------------------------------------

    def observe_incident(
        self,
        detection: dict,
        *,
        image_width: int,
        image_height: int,
        captured_at: str = None,
        occurred_at: str = None,
    ) -> "IncidentObservation":
        """확정된 결함 관측 1건을 **그 시점의 값으로 얼린다.** 발행하지는 않는다.

        관측과 발행을 나누는 이유는 증거 사진 업로드다: 업로드는 최악의 경우 수십 초가
        걸리는데(재시도), 그동안 로봇은 계속 달리고 미션도 끝날 수 있다. 발행 시점에
        상태를 읽으면 다른 곳에서 찍힌 좌표와 이미 끝난 미션이 실린다.
        event_id 를 여기서 만드는 것도 같은 이유다 — 서버는 (event_id, robot_id) 로
        사진을 event 에 묶으므로(§7.2) 업로드가 그 값을 먼저 알아야 한다.
        seq 만은 발행 순간에 매긴다(report_incident) — 발행 순서와 어긋나면 서버가
        재생 공격으로 보고 조용히 버린다 (§3.5).

        tracking·cooldown 중복 제거는 detect 몫이다 — 여기 오는 것은 이미 확정된 것이다.
        """
        occurred_at = occurred_at or self._now()
        snapshot = self._state.snapshot()
        valid, lat, lon = self._location()
        return IncidentObservation(
            event_id=new_uuid(),
            detection=dict(detection),
            occurred_at=occurred_at,
            captured_at=captured_at or occurred_at,
            image_width=int(image_width),
            image_height=int(image_height),
            location_valid=valid,
            lat=lat,
            lon=lon,
            # START_MISSION 으로 받은 값을 그대로 echo 한다. 좌표로 계산하지 않는다.
            zone_id=snapshot.zone_id,
            mission_id=snapshot.mission_id,
        )

    def report_incident(self, observation: "IncidentObservation", *, image_id=None) -> dict:
        """얼려 둔 관측 1건을 발행한다. 발행한 payload 를 돌려준다.

        `image_id` 는 증거 사진 업로드 결과다. 업로드가 실패해 null 이어도 event 는
        그대로 나간다 (§8.3) — 사진이 없다고 결함 사건을 삼키지 않는다.
        """
        detection = observation.detection
        payload = self._client.messages.incident_event(
            event_id=observation.event_id,
            occurred_at=observation.occurred_at,
            location_valid=observation.location_valid,
            lat=observation.lat,
            lon=observation.lon,
            zone_id=observation.zone_id,
            block_type=detection["block_type"],
            defect_type=detection.get("defect_type", "DAMAGED"),
            # 미설치(MISSING)는 상자도 점수도 없다 — factory 가 defect_type 별로
            # 필수 여부를 판단하므로 여기서는 없는 값을 그대로 넘긴다 (§8.3).
            confidence=detection.get("confidence"),
            damage_ratio=detection.get("damage_ratio"),
            image_id=image_id,
            captured_at=observation.captured_at,
            image_width=observation.image_width,
            image_height=observation.image_height,
            bbox_norm=detection.get("bbox_norm"),
            model_name=self._model_name,
            model_version=self._model_version,
            mission_id=observation.mission_id,
        )
        self._client.publish(TopicKind.EVENT_INCIDENT, payload)
        # 발행한 event 만 센다 (§11.3). 업로드를 기다리는 사이 미션이 끝났거나 다른
        # 미션이 시작됐으면 셀 보관함이 없다 — 그 event 는 미션 집계 밖이다.
        if (observation.mission_id is not None
                and observation.mission_id == self._state.mission_id):
            self._state.record_incident()
        return payload

    def _location(self) -> tuple:
        """(valid, lat, lon). Fix 가 없으면 좌표는 null 이고 event 는 그대로 나간다 (§8.3)."""
        reading = (self._gps() if self._gps is not None else None) or {}
        lat, lon = reading.get("lat"), reading.get("lon")
        if not reading.get("valid") or lat is None or lon is None:
            return False, None, None
        return True, lat, lon

    # -- events/mission (계약 §11) ----------------------------------------

    def mission_started(self) -> dict:
        return self._publish_mission("MISSION_STARTED")

    def mission_paused(self) -> dict:
        self._state.set_mission_state("PAUSED")
        return self._publish_mission("MISSION_PAUSED")

    def mission_resumed(self) -> dict:
        self._state.set_mission_state("RUNNING")
        return self._publish_mission("MISSION_RESUMED")

    def mission_completed(self, completion_reason: str = "ROUTE_COMPLETED") -> dict:
        return self._finish_mission("MISSION_COMPLETED", "COMPLETED", completion_reason)

    def mission_cancelled(self, completion_reason: str = "OPERATOR_CANCELLED") -> dict:
        return self._finish_mission("MISSION_CANCELLED", "CANCELLED", completion_reason)

    def mission_cancelled_for(self, mission_id: str, completion_reason: str) -> dict:
        """보관함에 없는 미션의 취소를 발행한다 — 서버 미션을 닫기 위한 경로다.

        보관함을 거치지 않는 유일한 mission event 다. 서버가 RUNNING 으로 알고 있는
        미션을 로봇이 모를 수 있고(재기동으로 보관함이 비었거나 START 를 못 받았다),
        그때도 관제의 취소 버튼은 미션을 닫아야 한다 (commands._cancel_mission).

        집계는 0 이다. 계약이 종료 event 에 result 전체를 요구하는데(§11.2) 세어 둔
        값이 없다 — 서버는 incident 를 event 로 따로 받아 세므로 이 0 이 실제 발행
        건수를 지우지는 않는다. 보관함도 건드리지 않는다: 살아 있는 **다른** 미션의
        집계를 이 발행이 망가뜨리면 안 된다.
        """
        if not mission_id:
            raise ValueError("mission_id is required")
        payload = self._client.messages.mission_event(
            event_type="MISSION_CANCELLED",
            occurred_at=self._now(),
            mission_id=mission_id,
            duration_sec=0,
            incident_count=0,
            normal_detection_count=0,
            completion_reason=completion_reason,
        )
        self._client.publish(TopicKind.EVENT_MISSION, payload)
        return payload

    def mission_failed(self, completion_reason: str = "INTERNAL_ERROR") -> dict:
        return self._finish_mission("MISSION_FAILED", "FAILED", completion_reason)

    def _finish_mission(self, event_type, mission_state, completion_reason) -> dict:
        """종료 3종. 집계를 실어 보내고 보관함을 비운다.

        result 는 payload 로 이미 복사된 뒤라 여기서 비워도 안전하다. 비우는 것을
        호출자에게 맡기면 종료 상태로 elapsed_sec 이 계속 자라는 창이 남는다.
        mode 전이(→ READY)는 완료·취소·실패에 따라 갈리므로 명령 쪽에서 한다.
        """
        self._state.set_mission_state(mission_state)
        payload = self._publish_mission(event_type, completion_reason=completion_reason)
        self._state.end_mission()
        return payload

    def _publish_mission(self, event_type: str, *, completion_reason: str = None) -> dict:
        # 미션 집계는 한 시점의 값이어야 한다 — 스냅샷 하나에서 전부 읽는다.
        snapshot = self._state.snapshot()
        if snapshot.mission_id is None:
            raise ValueError("no active mission; start_mission() must come first")
        result = {}
        if completion_reason is not None:
            # 시작·일시정지·재개에는 result 를 싣지 않는다 (§11.2).
            result = {
                "duration_sec": snapshot.mission_elapsed_sec,
                "incident_count": snapshot.mission_incident_count,
                "normal_detection_count": snapshot.mission_normal_detection_count,
                "completion_reason": completion_reason,
            }
        payload = self._client.messages.mission_event(
            event_type=event_type,
            occurred_at=self._now(),
            mission_id=snapshot.mission_id,
            **result,
        )
        self._client.publish(TopicKind.EVENT_MISSION, payload)
        return payload
