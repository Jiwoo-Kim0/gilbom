"""MQTT 접속·발행·구독 — paho-mqtt.  [F-006]

⚠️ 구현 기준은 「MQTT 통신 스웨거 명세서」(신버전, tbs/v1) 하나만.
   임베디드 상세 명세서 §6 통신 파트는 구버전 — 참조 금지.
topic 문자열·payload 조립은 여기서 하지 말고 전부 messages.py 에서 가져올 것.
"""

# TODO MqttClient 클래스
#   - 접속: host:8883 TLS + 계정 ID/PW 인증 (계약 §17 — TLS·계정 모두 MVP),
#     client_id=RBT-001, MQTT5, clean_start=False,
#     keepalive 30초(LTE NAT), 재접속 백오프 1→2→4→…→최대 30초
#   - LWT 등록: presence OFFLINE(UNGRACEFUL_DISCONNECT), retain
#   - 접속 성공(birth): presence ONLINE 발행, retain
#   - publish 계열:
#     state           QoS1 retain   (변경 시 + 30초 주기)
#     telemetry       QoS0          (5초 주기, 유실 허용)
#     events/*        QoS1          (incident / battery / safety / mission)
#     commands/result QoS1
#   - subscribe: commands/request
#     수신 시 검증 → 명령 큐에 push (main 이 pop 해서 실행)
#     ① now > expires_at → 즉시 result EXPIRED (NTP 동기 전제)
#     ② command_id 최근 목록(50개 set)에 있음(중복) → 무시
#   - shutdown(): presence OFFLINE(GRACEFUL_SHUTDOWN) 발행 후 disconnect
#   - Outbox (MVP — 계약 §16.1): events 4종은 로컬 저장(SQLite 등) 후 발행,
#     PUBACK(on_publish) 확인되면 sent 처리. LTE 단절 중 이벤트 유실 방지.
#     재접속 시 미전송분 재발행 (event_id 동일 → 서버가 UNIQUE 로 멱등 처리)
