# DrivingModule — Jetson Orin Nano 자율주행 모듈 레퍼런스

`segTest` 프로젝트(YOLO 세그멘테이션 기반 인도 주행 봇)에서 사용한 주행 모듈 모음.
다른 프로젝트에서 재사용할 수 있도록 원본 그대로 보존 + 문서화한 것. (정리일: 2026-07-28)

## 구성 파일

| 파일 | 역할 |
|------|------|
| `motor_driver.py` | 하드웨어 모터/조향 제어 계층 (PCA9685 / GPIO / MOCK) |
| `auto_navigator.py` | 주행 행동 상태머신 + 페일세이프(워치독, 증거 게이트) |
| `config.py` | 하드웨어 핀맵, 조향/속도 캘리브레이션, 상태머신 튜닝 파라미터 |
| `integration_example.py` | 세 모듈을 카메라 루프에 연결하는 최소 예제 |

의존성: `numpy`, `opencv-python`, `ultralytics`(YOLO), 하드웨어 모드에 따라
`adafruit-pca9685` + `adafruit-servokit` + `busio/board`(Blinka) 또는 `Jetson.GPIO`.

---

## 1. motor_driver.py — 하드웨어 제어 계층

`get_motor_driver(mode)` 팩토리로 드라이버를 얻는다. 초기화 실패 시 자동으로 MOCK 폴백되므로
하드웨어 없는 개발 PC에서도 그대로 임포트/실행 가능.

```python
from motor_driver import get_motor_driver
driver = get_motor_driver()          # config.HARDWARE_MODE 사용 ('PCA9685'|'GPIO'|'MOCK')
driver.forward(50)                   # 전진 50% 스로틀
driver.set_angle(120)                # 조향 서보 각도 (STEERING_MAX_LEFT~RIGHT로 클램프)
driver.stop()
driver.cleanup()
```

공통 인터페이스 (`BaseMotorDriver`):
- `forward(speed)`, `backward(speed)`, `stop()`
- `set_angle(angle)`, `steer_left(step)`, `steer_right(step)`, `center()`
- `set_speed(speed)` — MIN/MAX_SPEED로 클램프
- 상태 프로퍼티: `current_direction`("FORWARD"/"BACKWARD"/"STOP"/...), `current_speed`, `current_angle`

구현체:
- **PCA9685MotorDriver** — 듀얼 I2C 보드. 조향 서보 보드 `0x60`(ServoKit ch0),
  DC 모터 HAT `0x40`(PWM ch5, IN1 ch4, IN2 ch3). 주소는 후보 리스트를 순회하며 연결 시도.
- **GPIOMotorDriver** — L298N류 직결 (BOARD 핀맵: ENA=33, IN1=29, IN2=31, ENB=32, IN3=35, IN4=37).
  `ensure_gpio_mode()`가 Blinka가 SYSFS 모드로 선점한 경우에도 안전하게 핀모드를 재설정한다.
- **MockMotorDriver** — 로그만 남기는 시뮬레이터. 소프트웨어 테스트용.

⚠️ **핵심 특성: 모터는 마지막 PWM을 래치한다.** 명령이 끊겨도 멈추지 않는다.
"명령이 안 온다 = 정지"가 아니므로, 상위 계층에 반드시 워치독이 필요하다 (아래 3-2 참고).

---

## 2. auto_navigator.py — 주행 행동 상태머신

### 전체 파이프라인
```
카메라 프레임 + YOLO 결과
   → _perceive()   장애물/도로영역/거리 추출
   → _decide()     상태머신이 (motion, speed, steer) 결정
   → _apply()      모터 구동 (auto 모드 + 워치독 정상일 때만)
   → 텔레메트리 + HUD 프레임 반환
```
진입점은 `process_vision_navigation(img_bgr, yolo_results, drive=True)` 하나.
`drive=False`면 HUD만 그리고 상태머신/모터는 건드리지 않는다 (정지 이미지 미리보기용).

### 상태머신 (장애물 행동)
```
CRUISE ──장애물 확인──▶ STOP_HOLD ──3초 정지 관찰──┬─ 장애물 떠남 → CRUISE (직진 재개)
                                                └─ 그대로 있음 → AVOID
AVOID (회피 조향 턴) → PASS_BY (장애물 옆 직진 통과) → RECOVER (반대 조향으로 헤딩 복원) → CRUISE
AVOID/PASS_BY 중 막히면 → REVERSE (후진 이탈) → 반대편으로 AVOID 재시도
3회 모두 실패 → BLOCKED (안전 정차, 경로 열리면 자동 재개)
```

설계 포인트 (재사용 시 그대로 가져갈 가치가 있는 부분):
- **정지 후 관찰(STOP_HOLD)**: 장애물을 보면 일단 브레이크, 앵커(위치 스냅샷)를 찍고
  같은 자리에 `OBSTACLE_STOP_HOLD_SEC`(3s) 머무는지 확인. 움직이는 보행자는 회피 기동 없이 지나보낸다.
- **거리 기반 타이밍**: 회피 기동의 각 구간은 거리(m)로 정의하고 `BOT_SPEED_MPS_PER_PCT`
  (실측 속도 계수)로 시간으로 환산. 엔코더 없는 봇에서 개루프 주행을 재현 가능하게 만드는 방식.
- **대칭 복원(RECOVER)**: 회피 턴과 같은 조향량·속도·시간으로 반대 방향 턴 → 요(yaw)가 상쇄되어
  원래 헤딩으로 복귀 (횡방향 오프셋만 남음). 복귀 중 장애물이 다시 보이면 PASS_BY로 되돌아가 직진 연장.
- **블라인드스팟 메모리**: 근거리 장애물이 화면에서 사라져도 `AVOID_BLIND_CLEAR_SEC` 동안은
  "안 보임 ≠ 통과함"으로 취급. 카메라 근거리 사각지대 대응.
- **디바운스/그레이스**: 감지 `OBSTACLE_CONFIRM_FRAMES`(2프레임) 연속이어야 브레이크,
  소실은 `OBSTACLE_CLEAR_GRACE_SEC`(0.6s) 지나야 "사라짐" 판정. 단일 프레임 오검출 면역.
- **긴급 브레이크(_is_critical)**: 거리 조건 + 범퍼 밴드(`CRITICAL_BUMPER_HALF_W`) 겹침을 동시에
  요구. 근접만으로 발동하면 회피 중인 장애물이 기동을 계속 중단시키기 때문.
- **후진-후-반대편 재시도**: 회피가 막히면 즉시 반대 조향이 아니라 먼저 후진(`REVERSE_DIST_M`).
  코앞에서 반대로 꺾으면 장애물을 긁는다.

### 인지(_perceive)의 거리 추정
`estimate_ground_distance(pixel_y)` — 카메라 높이(20cm)와 하향 피치(15°)로 바운딩박스
하단 y좌표를 지면 거리(m)로 환산. 단안 카메라 + 지면 평면 가정의 고전적 기법.
카메라 장착이 바뀌면 `update_camera_geometry()`로 런타임 갱신 가능.

### 인도 주행 특화 로직
- `ROAD_VEHICLE_CLASS_IDS`(차/버스/트럭)가 우측에 보이면 "도로 경계"로 판단 →
  크루즈 시 좌측으로 넛지, 회피 방향 선택 시 우측 회피 금지 (차도로 내려가지 않음).

---

## 3. 페일세이프 계층 (상태머신보다 상위)

### 3-1. 무장(arming) 규칙
- `set_auto_mode(True)`는 **살아있는 운영자 하트비트 + 살아있는 카메라 프레임**이 모두 있어야 성공.
  갓 부팅한/연결 끊긴 서버를 눈감고 몰 수 없게 하는 장치.
- 하트비트·프레임 타임스탬프는 의도적으로 "stale" 상태로 초기화된다.

### 3-2. 워치독 (LINK_LOST)
- **비전 루프와 독립된 스레드**에서 동작 (10Hz). 카메라 스레드가 죽으면 상태머신 자체가
  호출되지 않으므로, 같은 루프에 두면 워치독도 같이 죽는다 — 반드시 분리할 것.
- 운영자 하트비트 `WATCHDOG_LINK_TIMEOUT_SEC`(2.5s) 또는 프레임 `WATCHDOG_FRAME_TIMEOUT_SEC`(1.0s)
  초과 시: 즉시 브레이크 + auto 해제 + **재무장(re-arm) 요구**. 자동 재개하지 않는다.
- `_apply()`에도 이중 게이트: 워치독 트립/재무장 대기 중엔 상태머신에 버그가 있어도
  STOP 외엔 모터에 도달 못 함. (의도된 중복 방어)
- 수동 주행도 커버: `manual_command()`가 하트비트를 갱신하므로 수동 전진 래치도 워치독 보호를 받는다.

### 3-3. 주행가능 증거 게이트 (HOLD_NO_EVIDENCE)
- 인도 주행은 기본값을 반전해야 한다: "장애물 없음 → 주행"이 아니라
  **"주행 가능한 지면의 양성 증거 없음 → 정지"**. 연석·인도 끝·낙차는 '감지되는 물체'가 아니라
  '감지의 부재'로 나타나기 때문.
- `set_evidence_provider(fn)`로 free-space 인지 모듈을 플러그인하는 구조.
  `fn(percept) -> {"ok", "source", "confidence", "lookahead_m"}`.
- **`REQUIRE_DRIVABLE_EVIDENCE = False`가 기본** — free-space 모듈이 아직 없어서 게이트는 비활성
  (스캐폴딩 상태). 실외 주행 전 반드시 모듈 구현 후 켜야 함.
- 증거 없이 주행 중일 때의 완화 장치: 크루즈 속도 캡(`NO_EVIDENCE_SPEED_CAP` 35%) +
  블라인드 주행 리쉬(`NO_EVIDENCE_LEASH_SEC` 30s 연속 주행 시 정차 + 재무장 요구).
  속도 캡을 회피 기동에는 적용하지 않는 이유: 기동 구간은 `CORNERING_SPEED` 기준으로
  시간 환산되므로 전역 캡을 걸면 계산된 거리보다 덜 이동한다.

---

## 4. config.py — 캘리브레이션 노트 (재사용 시 반드시 재측정)

| 파라미터 | 값 | 비고 |
|----------|-----|------|
| `BOT_SPEED_MPS_PER_PCT` | 0.0032 | **가장 중요.** 스로틀 1%당 m/s. 실측법: 평지에서 일정 스로틀로 2m 주행 시간 측정 → `(2/초)/스로틀%`. 섀시·타이어·기어비·배터리 바뀌면 재측정. (2026-07-28 실측: 35% 스로틀에서 3.008s에 34cm) |
| `STEERING_CENTER / MAX_LEFT / MAX_RIGHT` | 100 / 40 / 160 | 서보 개체별 캘리브레이션 필요 |
| `CAMERA_HEIGHT_CM / CAMERA_PITCH_DEG` | 20 / 15 | 거리 추정의 전제. 장착 바뀌면 갱신 |
| `CORNERING_SPEED` | 50 | 기동 구간은 arc-length 기반이라 속도를 올려도 클리어런스 동일, 시간만 단축. 단 ~60 이상은 구동계 스풀업이 구간 시간을 잠식 |
| `SAFE_CORRIDOR_LEFT_X / RIGHT_X` | 200 / 440 | 640×480 프레임 기준 전방 회랑(픽셀) |
| `OBSTACLE_TRIGGER_DIST_M / _Y` | 1.20 / 300 | "경로를 막는 장애물" 판정 기준 |
| `CRITICAL_STOP_DIST_M / _Y` | 0.50 / 450 | 긴급 브레이크 발동 거리 |

카메라 운용 노트: 내비 카메라(/dev/video0)와 전방 모니터링 카메라(/dev/video2)가
**하나의 USB 2.0 허브를 공유** → 두 캡처 모두 MJPG 강제 필수 (YUYV 2개는 대역폭 초과로
두 번째 카메라가 안 열림). 모니터링 카메라는 추론/내비게이션에 절대 연결하지 말 것.

---

## 5. 통합 방법

`integration_example.py` 참고. 핵심 계약:

1. 카메라 캡처 스레드에서 매 프레임: `nav.process_vision_navigation(frame, yolo_results)` 호출
   (이 호출이 프레임 워치독을 먹여 살린다)
2. 운영자 UI(대시보드)에서 주기적으로: `nav.notify_heartbeat()` (데드맨 스위치)
3. 자율주행 시작: `nav.set_auto_mode(True)` — 1, 2가 살아있어야 성공
4. 종료 시: `nav.shutdown()` → `driver.stop()` → `driver.cleanup()`

원본 프로젝트(`~/SSAFY/segTest/main.py`)의 FastAPI 연동 패턴:
- `POST /api/heartbeat` → `notify_heartbeat()` — arm 요청에서는 **하트비트를 갱신하지 않는다**
  (브레이크를 못 잡는 curl 한 방으로 무장할 수 없게)
- `POST /api/auto_mode` → `set_auto_mode()`
- `POST /api/manual_command` → `manual_command(cmd, param)`
- `GET /api/telemetry` → `nav.last_telemetry` (nav_state, needs_rearm, evidence_ok 등 포함)
