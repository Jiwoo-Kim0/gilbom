"""tbs/v1 payload 조립 — topic 문자열·envelope·enum 의 유일한 정의처.  [F-006]

⚠️ 필드명·enum·topic 은 「MQTT 통신 스웨거 명세서」(신버전) 그대로 쓸 것.
   임베디드 상세 명세서 §6 통신 파트는 구버전이므로 참조 금지.
"""

# TODO topic 상수 (robot_id 포맷팅)
#   tbs/v1/robots/{robot_id}/presence | state | telemetry
#   tbs/v1/robots/{robot_id}/events/incident | battery | safety | mission
#   tbs/v1/robots/{robot_id}/commands/request | commands/result

# TODO 공통 envelope 빌더 (events 4종 공통, 계약 §4)
#   schema_version "1.0", event_id(uuid4), event_type, robot_id,
#   boot_id(부팅 시 uuid4 1회 생성), seq(부팅 후 0부터 단조증가),
#   source_component(ai|power|safety|mission), occurred_at/sent_at(ISO8601 UTC Z),
#   mission_id(없으면 null)

# TODO 메시지 빌더 함수들
#   - presence(ONLINE/OFFLINE, reason)          — 계약 §5
#   - state(mode, reason_code, mission, battery, health)  — §6
#   - telemetry(position, motion, battery, system, environment, mission) — §7
#   - incident(location, incident{block_type, defect_type, confidence},
#              evidence{image_id, bbox_norm, ...}, model{name, version})  — §8
#   - battery_event(event_type, previous_state, battery, threshold_pct, action_taken) — §9
#   - safety_event(event_type, reason_code, measurement, transition, auto_resume_allowed) — §10
#   - mission_event(event_type, result{duration_sec, incident_count, ...}) — §11
#   - command_result(command_id, status, reason_code, state)  — §14
