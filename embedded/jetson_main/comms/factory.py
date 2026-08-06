"""boot_id·seq 를 소유하고 발행 payload 8종을 조립한다."""

from __future__ import annotations

import threading

from .blocks import _battery, _bbox, _position, health
from .codec import _enum, _optional_enum, _percent, _ratio, new_uuid, utc_now
from .contract import (
    ASSET_TYPE_BRAILLE_BLOCK,
    BATTERY_EVENT_TYPES,
    BATTERY_STATES,
    BLOCK_TYPES,
    COMMAND_STATUSES,
    DEFECT_TYPES,
    EVENT_TTL_DEFAULT_SEC,
    EVENT_TTL_MAX_SEC,
    EVENT_TTL_MIN_SEC,
    INCIDENT_EVENT_TYPE,
    MISSION_EVENT_TYPES,
    MISSION_STATES,
    MISSION_TERMINAL_EVENT_TYPES,
    MODES,
    OFFLINE_REASONS,
    REASON_CODES,
    SAFETY_EVENT_TYPES,
    SAFETY_REASON_CODES,
    SCHEMA_VERSION,
    SOURCE_COMPONENTS,
    UNLOCALIZED_DEFECT_TYPES,
)
from .topics import Topics


class MessageFactory:
    """boot_id·seq 를 소유하고 v2 payload 를 조립한다.

    boot_id : 프로세스(통신 프로그램) 시작 시 uuid4 1회.
    seq     : 한 boot_id 안에서 state·telemetry·event 가 공유하는 단조증가 순번.
              첫 메시지가 1이고, 새 인스턴스(=새 boot_id)면 다시 1부터 시작한다.
              presence 와 commands/result 는 seq 를 쓰지 않는다.
    """

    def __init__(
        self,
        robot_id: str,
        boot_id: str = None,
        event_ttl_sec: int = EVENT_TTL_DEFAULT_SEC,
    ) -> None:
        if not robot_id:
            raise ValueError("robot_id is required")
        if (
            isinstance(event_ttl_sec, bool)
            or not isinstance(event_ttl_sec, int)
            or not EVENT_TTL_MIN_SEC <= event_ttl_sec <= EVENT_TTL_MAX_SEC
        ):
            raise ValueError(
                "event_ttl_sec must be an integer between "
                f"{EVENT_TTL_MIN_SEC} and {EVENT_TTL_MAX_SEC}"
            )
        self.robot_id = robot_id
        self.boot_id = boot_id or new_uuid()
        self.event_ttl_sec = event_ttl_sec
        self.topics = Topics(robot_id)
        self._seq = 0
        self._seq_lock = threading.Lock()

    # -- 식별자 ----------------------------------------------------------

    def next_seq(self) -> int:
        with self._seq_lock:
            self._seq += 1
            return self._seq

    @property
    def seq(self) -> int:
        return self._seq

    def _head(self) -> dict:
        return {
            "schema_version": SCHEMA_VERSION,
            "mqtt_id": self.robot_id,
            "boot_id": self.boot_id,
        }

    # -- presence (§4) ---------------------------------------------------

    def presence_online(self, connected_at: str = None) -> dict:
        payload = self._head()
        payload["status"] = "ONLINE"
        payload["connected_at"] = connected_at or utc_now()
        return payload

    def presence_offline(self, reason: str) -> dict:
        reason = _enum(reason, OFFLINE_REASONS, "presence.reason")
        payload = self._head()
        payload["status"] = "OFFLINE"
        payload["reason"] = reason
        return payload

    # -- state (§5) ------------------------------------------------------

    def state(
        self,
        *,
        mode: str,
        reason_code: str,
        health_block: dict,
        battery_valid: bool,
        battery_percent,
        battery_state: str,
        mission_id: str = None,
        mission_state: str = None,
        updated_at: str = None,
    ) -> dict:
        mode = _enum(mode, MODES, "state.mode")
        reason_code = _enum(reason_code, REASON_CODES, "state.reason_code")
        mission = self._mission_block(mission_id, mission_state)
        battery = _battery(battery_valid, battery_percent, battery_state)
        if not isinstance(health_block, dict):
            raise ValueError("health: object required")
        health_members = health(**health_block)
        return {
            "schema_version": SCHEMA_VERSION,
            "mqtt_id": self.robot_id,
            "boot_id": self.boot_id,
            "seq": self.next_seq(),
            "updated_at": updated_at or utc_now(),
            "mode": mode,
            "reason_code": reason_code,
            "mission": mission,
            "battery": battery,
            "health": health_members,
        }

    @staticmethod
    def _mission_block(mission_id, mission_state):
        """활성 미션이 없으면 null. 있으면 mission_id 와 state 를 함께 싣는다."""
        if mission_id is None:
            if mission_state is not None:
                raise ValueError("mission_state given without mission_id")
            return None
        return {
            "mission_id": mission_id,
            "state": _enum(mission_state, MISSION_STATES, "mission.state"),
        }

    # -- telemetry (§6) --------------------------------------------------

    def telemetry(
        self,
        *,
        position_valid: bool,
        lat=None,
        lon=None,
        heading_deg=None,
        moving: bool,
        battery_valid: bool,
        battery_percent,
        battery_state: str,
        battery_source: str = None,
        battery_extra: dict = None,
        jetson_temp_c=None,
        mission_id: str = None,
        mission_state: str = None,
        mission_elapsed_sec: int = None,
        mission_incident_count: int = None,
        sampled_at: str = None,
    ) -> dict:
        position = _position(position_valid, lat, lon, heading_deg)
        battery = _battery(
            battery_valid,
            battery_percent,
            battery_state,
            with_source=True,
            source=battery_source,
            extra=battery_extra,
        )
        mission = self._telemetry_mission_block(
            mission_id, mission_state, mission_elapsed_sec, mission_incident_count
        )
        return {
            "schema_version": SCHEMA_VERSION,
            "message_id": new_uuid(),
            "mqtt_id": self.robot_id,
            "boot_id": self.boot_id,
            "seq": self.next_seq(),
            "sampled_at": sampled_at or utc_now(),
            "mission_id": mission_id,
            "position": position,
            "motion": {
                "moving": bool(moving),
                # 엔코더·오도메트리가 없으므로 MVP 는 항상 null.
                "speed_mps": None,
            },
            "battery": battery,
            "system": {"jetson_temp_c": jetson_temp_c},
            "mission": mission,
        }

    @staticmethod
    def _telemetry_mission_block(mission_id, state, elapsed_sec, incident_count):
        if mission_id is None:
            if state is not None:
                raise ValueError("mission_state given without mission_id")
            return None
        if elapsed_sec is None or incident_count is None:
            raise ValueError(
                "active mission requires mission_elapsed_sec and mission_incident_count"
            )
        return {
            "state": _enum(state, MISSION_STATES, "mission.state"),
            "elapsed_sec": int(elapsed_sec),
            "incident_count": int(incident_count),
        }

    # -- event 공통 envelope (§7) ----------------------------------------

    def event_envelope(
        self,
        *,
        event_type: str,
        source_component: str,
        occurred_at: str,
        mission_id: str = None,
        sent_at: str = None,
        event_id: str = None,
    ) -> dict:
        """4종 event 가 공유하는 머리 부분.

        재전송 시 event_id 와 occurred_at 은 유지하고 sent_at 만 갱신한다
        (갱신은 Outbox 가 담당 — client._send_stored).

        `event_id` 를 주면 그 값을 쓴다. 증거 이미지 업로드가 event 발행보다 먼저
        일어나는 경로 때문이다 — 서버는 (event_id, robot_id) 로 사진을 event 에
        묶으므로(계약 §7.2) 업로드에 쓴 값과 payload 의 값이 같아야 한다.
        seq 는 여기서 매긴다: 발행 순서와 어긋나면 서버가 재생 공격으로 보고 버린다.
        """
        return {
            "schema_version": SCHEMA_VERSION,
            "event_id": event_id or new_uuid(),
            "event_type": event_type,
            "mqtt_id": self.robot_id,
            "boot_id": self.boot_id,
            "seq": self.next_seq(),
            "source_component": _enum(
                source_component, SOURCE_COMPONENTS, "source_component"
            ),
            "occurred_at": occurred_at,
            "sent_at": sent_at or utc_now(),
            "ttl_sec": self.event_ttl_sec,
            "mission_id": mission_id,
        }

    # -- events/incident (§8) --------------------------------------------

    def incident_event(
        self,
        *,
        occurred_at: str,
        location_valid: bool,
        lat,
        lon,
        zone_id: str,
        block_type: str,
        image_id: str,
        captured_at: str,
        image_width: int,
        image_height: int,
        model_name: str,
        model_version: str,
        # MISSING 은 둘 다 없다. 그래서 기본값이 있고, 필수 여부는 아래에서
        # defect_type 별로 본다 — 시그니처로는 표현할 수 없는 조건이다.
        confidence=None,
        bbox_norm=None,
        defect_type: str = "DAMAGED",
        damage_ratio=None,
        mission_id: str = None,
        event_id: str = None,
    ) -> dict:
        if location_valid and (lat is None or lon is None):
            raise ValueError("location: valid=True requires both lat and lon")
        defect_type = _enum(defect_type, DEFECT_TYPES, "incident.defect_type")
        # MISSING 은 상자도 점수도 없다. 나머지는 둘 다 필수다 — 빠뜨리고 보내면 서버가
        # MQTT_PAYLOAD_VALIDATION_FAILED 로 조용히 버리고 로봇은 PUBACK 을 받아
        # Outbox 에서 지운다. 유실을 알아챌 수 있는 마지막 자리가 여기다.
        if defect_type not in UNLOCALIZED_DEFECT_TYPES:
            missing = [name for name, value in
                       (("bbox_norm", bbox_norm), ("confidence", confidence))
                       if value is None]
            if missing:
                raise ValueError(
                    f"incident: defect_type {defect_type} requires {', '.join(missing)}")
        location = {
            "valid": bool(location_valid),
            "lat": float(lat) if location_valid else None,
            "lon": float(lon) if location_valid else None,
            # START_MISSION 으로 받은 값을 그대로 echo 한다. 좌표로 계산하지 않는다.
            "zone_id": zone_id,
        }
        incident = {
            "asset_type": ASSET_TYPE_BRAILLE_BLOCK,
            "block_type": _enum(block_type, BLOCK_TYPES, "incident.block_type"),
            "defect_type": defect_type,
            "confidence": _ratio(confidence, "incident.confidence"),
        }
        if damage_ratio is not None:
            incident["attributes"] = {
                "damage_ratio": _ratio(damage_ratio, "incident.attributes.damage_ratio")
            }
        evidence = {
            # 업로드 실패 시 image_id 만 null 로 두고 event 는 그대로 발행한다.
            "image_id": image_id,
            "captured_at": captured_at,
            "image_width": int(image_width),
            "image_height": int(image_height),
            "bbox_norm": None if bbox_norm is None else _bbox(bbox_norm),
        }
        payload = self.event_envelope(
            event_type=INCIDENT_EVENT_TYPE,
            source_component="ai",
            occurred_at=occurred_at,
            mission_id=mission_id,
            event_id=event_id,
        )
        payload["location"] = location
        payload["incident"] = incident
        payload["evidence"] = evidence
        payload["model"] = {"name": model_name, "version": model_version}
        return payload

    # -- events/battery (§9) ---------------------------------------------

    def battery_event(
        self,
        *,
        event_type: str,
        occurred_at: str,
        previous_state: str,
        battery_valid: bool,
        battery_percent,
        battery_state: str,
        threshold_pct,
        action_robot_mode: str,
        action_mission_state: str = None,
        mission_id: str = None,
    ) -> dict:
        event_type = _enum(event_type, BATTERY_EVENT_TYPES, "event_type")
        previous_state = _enum(previous_state, BATTERY_STATES, "previous_state")
        battery = _battery(battery_valid, battery_percent, battery_state)
        threshold_pct = (
            None if threshold_pct is None else _percent(threshold_pct, "threshold_pct")
        )
        action_taken = {
            "mission_state": _optional_enum(
                action_mission_state, MISSION_STATES, "action_taken.mission_state"
            ),
            "robot_mode": _enum(action_robot_mode, MODES, "action_taken.robot_mode"),
        }
        payload = self.event_envelope(
            event_type=event_type,
            source_component="power",
            occurred_at=occurred_at,
            mission_id=mission_id,
        )
        payload["previous_state"] = previous_state
        payload["battery"] = battery
        payload["threshold_pct"] = threshold_pct
        payload["action_taken"] = action_taken
        return payload

    # -- events/safety (§10) ---------------------------------------------

    def safety_event(
        self,
        *,
        event_type: str,
        occurred_at: str,
        reason_code: str,
        mode_before: str,
        mode_after: str,
        auto_resume_allowed: bool,
        distance_m=None,
        mission_id: str = None,
    ) -> dict:
        event_type = _enum(event_type, SAFETY_EVENT_TYPES, "event_type")
        reason_code = _enum(reason_code, SAFETY_REASON_CODES, "reason_code")
        # LiDAR 거리가 해당 없는 사건이면 null.
        measurement = {"distance_m": None if distance_m is None else float(distance_m)}
        transition = {
            "mode_before": _enum(mode_before, MODES, "transition.mode_before"),
            "mode_after": _enum(mode_after, MODES, "transition.mode_after"),
        }
        payload = self.event_envelope(
            event_type=event_type,
            source_component="safety",
            occurred_at=occurred_at,
            mission_id=mission_id,
        )
        payload["reason_code"] = reason_code
        payload["measurement"] = measurement
        payload["transition"] = transition
        payload["auto_resume_allowed"] = bool(auto_resume_allowed)
        return payload

    # -- events/mission (§11) --------------------------------------------

    def mission_event(
        self,
        *,
        event_type: str,
        occurred_at: str,
        mission_id: str,
        duration_sec: int = None,
        incident_count: int = None,
        normal_detection_count: int = None,
        completion_reason: str = None,
        causation_command_id: str = None,
    ) -> dict:
        """기기는 총거리·진행률·남은 거리·ETA 를 보내지 않는다 (v2 결정)."""
        event_type = _enum(event_type, MISSION_EVENT_TYPES, "event_type")
        result_args = (
            duration_sec,
            incident_count,
            normal_detection_count,
            completion_reason,
        )
        result = None
        if event_type in MISSION_TERMINAL_EVENT_TYPES:
            if any(arg is None for arg in result_args):
                raise ValueError(f"{event_type} requires the full result block")
            result = {
                "duration_sec": int(duration_sec),
                "incident_count": int(incident_count),
                "normal_detection_count": int(normal_detection_count),
                "completion_reason": completion_reason,
            }
        elif any(arg is not None for arg in result_args):
            # 시작·일시정지·재개 event 에서는 result 를 생략한다.
            raise ValueError(f"{event_type} must not carry a result block")
        payload = self.event_envelope(
            event_type=event_type,
            source_component="mission",
            occurred_at=occurred_at,
            mission_id=mission_id,
        )
        if causation_command_id is not None:
            payload["causation_command_id"] = causation_command_id
        if result is not None:
            payload["result"] = result
        return payload

    # -- commands/result (§13) -------------------------------------------

    def command_result(
        self,
        *,
        command_id: str,
        status: str,
        received_at: str,
        reason_code: str,
        mode: str,
        state_reason_code: str,
        completed_at: str = None,
    ) -> dict:
        status = _enum(status, COMMAND_STATUSES, "status")
        if status == "ACCEPTED" and completed_at is not None:
            raise ValueError("ACCEPTED result must leave completed_at null")
        if status != "ACCEPTED" and completed_at is None:
            raise ValueError(f"{status} result requires completed_at")
        if completed_at is not None and completed_at < received_at:
            # 서버 DTO 는 completed_at >= received_at 도 함께 요구한다. 시계가 뒤로
            # 튀면(NTP 스텝) 여기서 안 막힌 result 가 MQTT_PAYLOAD_VALIDATION_FAILED
            # 로 조용히 버려지고, 관제 화면의 명령은 영원히 ACCEPTED 에 멈춘다.
            # ISO-8601 UTC 고정 폭 문자열이라 사전순 비교가 시각순 비교와 같다.
            raise ValueError(
                f"completed_at({completed_at}) must not precede received_at({received_at})"
            )
        return {
            "schema_version": SCHEMA_VERSION,
            "result_id": new_uuid(),
            "command_id": command_id,
            "mqtt_id": self.robot_id,
            "status": status,
            "received_at": received_at,
            "completed_at": completed_at,
            "reason_code": reason_code,
            "state": {
                "mode": _enum(mode, MODES, "state.mode"),
                "reason_code": _enum(
                    state_reason_code, REASON_CODES, "state.reason_code"
                ),
            },
        }
