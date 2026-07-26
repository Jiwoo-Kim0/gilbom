"""OrinCar 진입점 — 메인 루프 + 상태머신. (구현 전, 안내 주석만 있음)

역할: 세 축(drive / detect / comms)과 sensors를 '조립'만 한다.
여기에 하드웨어 접근 코드나 판정 로직을 직접 쓰지 말 것.
요구 추적: F-001(초기화) F-007(시스템 관리) + MQTT 계약(신버전 tbs/v1)
"""

# TODO 1) 설정 로드
#   - config.yaml 을 yaml.safe_load 로 읽어 각 모듈에 주입

# TODO 2) 모듈 초기화  [F-001]
#   - drive:   Steering(I2C 0x60, center 정렬 필수) + Motor(0x40) + Safety(LiDAR)
#   - detect:  Camera + Detector(모델 로드)
#   - comms:   MqttClient(접속, presence ONLINE 발행, commands/request 구독)
#   - sensors: Gps + Battery
#   - 초기화 실패 모듈은 health FAULT 로 기록하고 가능하면 계속 기동

# TODO 3) 상태머신 (신버전 계약 state.mode 그대로)
#   - BOOTING → READY → PATROLLING ↔ PAUSED / SAFE_STOP → SHUTTING_DOWN
#   - reason_code: NONE / REMOTE_ESTOP / OBSTACLE / LOW_BATTERY / ... (계약 §6)
#   - health 취합: camera/lidar/gps/battery/motor/storage 를 OK/DEGRADED/FAULT/UNKNOWN
#     으로 모아 state.health 에 포함  [F-007 상태 모니터링]
#   - mode·reason_code·health 가 바뀔 때마다 comms 로 state 발행

# TODO 4) 메인 루프 (PATROLLING 기준)
#   - drive 스텝 + safety 체크: 장애물 감지 → 즉시 정지 + SAFE_STOP 전이
#     (정지 후 0.5초 내 events/safety 발행 — 계약 §10)  [F-003]
#   - detect 스텝: 프레임 추론 → 이상 판정 시
#     ① uploader 로 이미지 업로드(image_id 획득) ② events/incident 발행
#     incident 의 location.zone_id 는 GPS 좌표→구역 매핑으로 채움 (필수 필드,
#     정규식 ^[A-Z]-[0-9]{2}$ — 구획 기준은 팀 확정 필요)  [계약 §8]
#   - 주기 발행: telemetry 5초 / state 는 변경 시 + 30초마다
#     telemetry.system.jetson_temp_c 는 tj-thermal(/sys thermal zone) 읽기  [F-007]
#   - 명령 처리: comms 명령 큐 pop → EMERGENCY_STOP / START_MISSION 등 실행
#     → commands/result 응답 (ALREADY_IN_STATE 등 reason_code 포함)
#   - 미션 전이(시작/완료/취소/실패) 시 events/mission 발행 (계약 §11)
#   - battery 상태 전이(LOW/CRITICAL/RECOVERED) 감지 → events/battery 발행 [F-007]

# TODO 5) 종료 처리 (KeyboardInterrupt / systemd stop)
#   - drive 정지 → presence OFFLINE(GRACEFUL_SHUTDOWN) 발행 → MQTT disconnect

# TODO 6) 배포  [F-007]
#   - systemd 서비스 등록: 부팅 자동 시작 + Restart=always(비정상 종료 자동 복구)
#   - unit 파일·설치 안내는 scripts/ 에 작성
#   - 시계: NTP 동기(timedatectl) 전제 — 명령 expires_at 검증이 시계에 의존 (계약 §17)
