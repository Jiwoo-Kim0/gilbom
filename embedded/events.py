"""events 4종 판정·발행 — incident · safety · battery · mission.  [F-003, F-004, F-007]

네 사건은 트리거가 서로 달라서 한 모듈에 모으되 경로를 나눠 둔다.
  safety   메인 루프 tick 마다 LiDAR 를 보고 2단계로 확정한다 (§10.2)
  battery  RobotState 가 판정한 등급이 바뀌는 순간 (§9)
  incident 탐지를 확정한 쪽이 report_incident() 로 알린다 (§8)
  mission  미션이 전이할 때 R-5 가 mission_*() 을 부른다 (§11)
payload 조립은 하지 않는다. 값만 모아 factory 의 event 빌더에 넘긴다.
저장·재전송·멱등 처리도 여기서 하지 않는다 — client.publish() 가 event 4종을
Outbox 로 태우고, 재전송 때 event_id·occurred_at 을 그대로 지킨다 (§7).
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §7~§11
"""

from __future__ import annotations

import logging
import time

from comms import TopicKind
from comms.codec import utc_now
from comms.uploader import upload as upload_image

log = logging.getLogger(__name__)

#: 배터리 등급의 높낮이. 올라가는 전이의 이름이 BATTERY_RECOVERED 다 (§9.3).
_BATTERY_RANK = {"CRITICAL": 0, "LOW": 1, "NORMAL": 2}

#: health 멤버 → events/safety 의 reason_code (§10.5).
SENSOR_REASON_CODES = {"lidar": "LIDAR_FAULT", "motor": "MOTOR_FAULT"}

#: SAFE_STOP 으로 넘어갈 수 있는 mode (robot_state._TRANSITIONS 와 같은 집합).
_SAFE_STOPPABLE_MODES = frozenset({"READY", "PATROLLING", "PAUSED"})

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
        lidar=None,
        gps=None,
        model_name: str = None,
        model_version: str = None,
        obstacle_confirm_sec: float = OBSTACLE_CONFIRM_SEC,
        stop_motor=None,
        sensor_fault_stops_driving: bool = True,
        upload=upload_image,
        upload_robot_uuid: str = None,
        monotonic=time.monotonic,
        now=utc_now,
    ) -> None:
        self._client = client
        self._state = state
        self._lidar = lidar
        self._gps = gps
        self._model_name = model_name
        self._model_version = model_version
        self._obstacle_confirm_sec = obstacle_confirm_sec
        #: 장애물 최초 감지 때 부른다. MQTT 보다 먼저 물리 정지를 시키는 자리다 (§10.1).
        self._stop = stop_motor
        #: LiDAR·모터가 FAULT 인 동안 주행을 막을지. 눈이 먼 채로 굴러가지 않게 하는
        #: 안전 정책이라 기본은 켬이다. 장치 없는 개발 PC 에서 주행 흐름을 흉내 낼 때만 끈다.
        self._sensor_fault_stops_driving = sensor_fault_stops_driving
        self._upload = upload
        #: 이미지 업로드 form 의 robot_id 에 실리는 DB 내부 UUID (robot.robot_id).
        #: mqtt_id 가 아니다 — 서버가 UUID 로 파싱하므로 mqtt_id 를 보내면 400 이다.
        self._upload_robot_uuid = upload_robot_uuid
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
        obstacle, distance_m = self._read_lidar()
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

    def _read_lidar(self) -> tuple:
        """(obstacle, distance_m). 읽을 수 없으면 장애물 없음으로 본다."""
        reading = self._lidar() if self._lidar is not None else None
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
        if grade == "CRITICAL" and mode in _SAFE_STOPPABLE_MODES:
            self._state.safe_stop("CRITICAL_BATTERY")
        elif grade == "LOW" and mode == "PATROLLING":
            self._state.pause("LOW_BATTERY")

    def set_upload_robot_uuid(self, robot_uuid) -> None:
        """DB 내부 robot.robot_id UUID 를 런타임에 채운다.

        웹이 로봇 등록 뒤에 내려주는 값이라 기동 시점에는 없을 수 있다. 받기 전까지
        이미지 업로드는 건너뛰고(uploader 가 error 로그), 결함 event 는 image_id: null
        로 그대로 나간다 — 사진이 없다고 결함을 삼키지 않는다.
        """
        self._upload_robot_uuid = robot_uuid

    # -- events/incident (계약 §8) ----------------------------------------

    def report_incident(
        self,
        detection: dict,
        *,
        image_width: int,
        image_height: int,
        image_path=None,
        captured_at: str = None,
        occurred_at: str = None,
    ) -> dict:
        """확정된 결함 관측 1건을 발행한다. 발행한 payload 를 돌려준다.

        tracking·cooldown 중복 제거는 detect 몫이다 — 여기 오는 것은 이미 확정된 것이다.
        """
        occurred_at = occurred_at or self._now()
        snapshot = self._state.snapshot()
        valid, lat, lon = self._location()
        payload = self._client.messages.incident_event(
            occurred_at=occurred_at,
            location_valid=valid,
            lat=lat,
            lon=lon,
            # START_MISSION 으로 받은 값을 그대로 echo 한다. 좌표로 계산하지 않는다.
            zone_id=snapshot.zone_id,
            block_type=detection["block_type"],
            defect_type=detection.get("defect_type", "DAMAGED"),
            confidence=detection["confidence"],
            damage_ratio=detection.get("damage_ratio"),
            image_id=None,
            captured_at=captured_at or occurred_at,
            image_width=image_width,
            image_height=image_height,
            bbox_norm=detection["bbox_norm"],
            model_name=self._model_name,
            model_version=self._model_version,
            mission_id=snapshot.mission_id,
        )
        if image_path is not None:
            # 서버는 (event_id, robot_id) 로 이미지를 event 에 묶는다 (계약 §7.2).
            # event_id 는 factory 가 만들므로 payload 를 만든 뒤에야 업로드할 수 있다.
            # ⚠️ 여기 넘기는 것은 mqtt_id 가 아니라 DB 내부 robot.robot_id UUID 다.
            #    서버 ImageUploadController 가 UUID.fromString() 으로 파싱한다.
            payload["evidence"]["image_id"] = self._upload(
                image_path,
                payload["event_id"],
                self._upload_robot_uuid,
                captured_at=payload["evidence"]["captured_at"],
            )
        # 업로드가 실패해 image_id 가 null 이어도 event 발행은 계속한다 (§8.3).
        self._client.publish(TopicKind.EVENT_INCIDENT, payload)
        if snapshot.mission_id is not None:
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
