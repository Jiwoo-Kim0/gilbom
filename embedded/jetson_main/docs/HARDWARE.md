# HARDWARE.md — 젯슨 하드웨어·코드 인벤토리 (Phase 0)

- 수집일: 2026-07-30, Jetson Orin Nano (`Linux ubuntu-desktop 5.15.148-tegra aarch64`)
- 수집 방법: `python3 scripts/hardware_inventory.py --codebase /home/ubuntu/DrivingCore`
  (재실행하면 언제든 최신 상태로 재검증 가능)

## 1. I2C

| 버스 | 발견 주소 | 판정 |
|---|---|---|
| 0 | 0x50, 0x57 (커널 점유) | EEPROM 계열 내부 장치 — 주행 무관 |
| 1 | 0x25, **0x40 (커널 점유)** | 0x40은 온보드 INA3221 전원 모니터. **모터 HAT의 0x40과 다른 버스의 다른 칩** — 혼동 주의 |
| 2 | 없음 | — |
| 4 | 0x3c (커널 점유) | 내부 장치 — 주행 무관 |
| 5 | 스캔 시간 초과 | 내부 버스로 추정 — 주행 무관 |
| **7** | **0x40 (응답), 0x60 (응답)** | **40핀 헤더(핀 3/5). 구동 모터 HAT = 0x40, 조향 서보 보드 = 0x60 — 실응답 확인** |
| 9 | 감지 명령 미지원 | — |

- IMU(MPU6050 클론, **버스 1** 0x68): 배선·방향 검증 완료, **현재는 배선 임시 해제 상태**.
  ※ 2026-08-04: 이 '임시 해제'가 0803 실외 실패의 직접 원인이었다 — 회피·회전이 IMU
  게이트 뒤여서 장애물·코너가 전부 정지로 떨어졌다. 지금은 그 둘이 IMU 없이 돌고,
  게이트는 SLOPE(pitch)에만 남았다 — DEAD_END 의 yaw 는 회전 상한과 U턴 목표각 용도이고,
  없으면 시간으로 대체된다(`handlers.py` `_dead_end_uturn`). 배선하면 SLOPE 와 내리막 위치
  고수(0806 `_hold_command`)가 열리고 TURN 진행 판정·재정향 회전 상한이 시간에서 yaw 실측으로
  올라간다 — **있으면 더 정확해지고, 없어도 미션은 돈다.**
  - 재배선: 헤더 17(3.3V)/25(GND)/27(SDA)/28(SCL) 네 가닥 → `i2cdetect -y -r 1`에서 0x68 확인.
  - WHO_AM_I가 0x72를 돌려주는 클론 (드라이버가 경고만 하고 진행).
  - 장착·축: 칩면 하늘(+z), x 화살표 = 차 정면(+x). 클론 z축 자이로가 오른손 규칙 반대라
    config `yaw_sign: -1`로 보정 (좌회전 = yaw +). 방향 재검증은 `python scripts/imu_check.py`.

## 2. 카메라

| 노드 | 장치 | 역할 |
|---|---|---|
| /dev/video0 | Web Camera (32e6:d412) | **주행용 전방 카메라 — 세그멘테이션 입력** (`config.yaml perception.nav_camera_device`) |
| /dev/video1 | Web Camera | video0과 동일 장치의 메타데이터 노드 |
| /dev/video2 | Logitech Brio 100 (046d:094c) | **점자블록 파손 검사 카메라 — INSPECT 캡처 입력** (`config.yaml perception.inspect_camera_device`, 0731 역할 확정) |
| /dev/video3 | Brio 100 | video2의 메타데이터 노드 |

- config.yaml은 `/dev/videoN` 대신 `/dev/v4l/by-id/...` 고정 경로를 쓴다 — 번호는 USB 재연결·재부팅 순서에 따라 바뀐다. cv2로 by-id 경로를 열 때는 `CAP_V4L2` 백엔드 명시 필수 (자동 감지가 이미지 시퀀스로 오해, `perception/camera.py`가 처리).
- 두 카메라가 USB 2.0 허브 하나를 공유 → 동시 사용 시 MJPG 압축 포맷 필수 (이전 프로젝트 실측 교훈).
- 기타 USB (0731 4포트 만재 실측): Xbox360 컨트롤러(/dev/input/js0, 수동 폴백 조이스틱 후보), u-blox 7 GPS(/dev/ttyACM0 — 2026-08-04 기준 보고 좌표 태깅에 배선됨: `main.build_gps`), Bluetooth, 허브류.
- **터치 센서(출발 확인 — 0805부터 안전 인터록) — 0804 배선 확정**: `VCC = pin 1 (3.3V)`,
  `SIG = pin 16`, `GND = pin 14`. config `hardware.touch.pin: 16`, `active_high: true`.
  VCC가 **3.3V(pin 1)** 인 것이 중요하다: pin 2·4는 5V이고 젯슨 GPIO는 3.3V 입력이라
  5V 신호는 핀을 상하게 한다. 구동 I2C(3·5)·IMU(27·28·17·25)와 겹치지 않는다.
  구현은 `hardware/jetson/touch.py`(BOARD 모드 폴링 + 누름 엣지 1회 소비).
  **이 센서가 죽으면 로봇이 출발하지 못하는 것이 아니라, 원격 출발을 막던 확인이
  사라진다** — 인터록은 센서 부재를 감지하면 경고를 남기고 스스로 내려간다
  (`driving/start_interlock.py`, config `hardware.touch.required_for_start`).
  즉 여기 반응이 없으면 안전장치가 조용히 빠진 상태이므로 기동 로그를 확인할 것.
  ※ 핀 16이 헤더에서 GPIO로 노출돼 있지 않으면 `sudo /opt/nvidia/jetson-io/jetson-io.py`
  로 핀맥스를 GPIO로 바꿔야 한다(재부팅 필요). 반응이 전혀 없을 때 여기를 먼저 볼 것 —
  브링업 시절의 `hdr40-gpio-enable.dtbo`는 정리 때 지웠고, 필요하면 jetson-io가 다시 만든다.
- 카메라는 녹화 도구(`tools/data_recorder`)와 동시 사용 불가 — 주행 전 종료할 것.

## 3. 구동/조향 제어 방식 (기존 코드 추출: `/home/ubuntu/DrivingCore`)

- 라이브러리: `adafruit_pca9685`(모터 HAT) + `adafruit_servokit`(조향), `busio` I2C. 폴백 경로로 `Jetson.GPIO`, 전원 프로브에 `smbus2`.
- 구동(모터 HAT 0x40): PCA9685 60Hz, **PWM=ch5, IN1=ch4, IN2=ch3** — TB6612FNG **B채널**(PWMB/BIN1/BIN2) 배선과 일치. 듀티 0~100% → 0~65535. 전진 IN1=H/IN2=L, 후진 반대, **숏브레이크 IN1=IN2=H + PWM 100%** (경사 정지용).
- 조향(서보 보드 0x60): ServoKit ch0, 각도 직접 지정.

## 4. 예상값 대 실측값 검증표

| 항목 | 예상값(명세) | 검증 결과 | 차이/조치 |
|---|---|---|---|
| 구동 보드·주소 | 모터 HAT 0x40 | **일치** (버스 7 실응답) | — |
| 구동 채널 | TB6612FNG B채널 | **일치** (ch5/4/3 = PWMB/BIN1/BIN2) | — |
| 구동 방식 | PWM 스로틀 전·후진 | **일치** (+숏브레이크 지원 확인) | brake는 SLOPE 하강 대비로 인터페이스에 포함 |
| 조향 보드·채널 | PCA9685 0x60 ch0 | **일치** (버스 7 실응답) | — |
| 서보 모델 | MG996R | 소프트웨어로 검증 불가 | 명세값 신뢰, 실물 라벨 확인 권장 |
| 조향 중심 | 100° | **일치** (기존 코드 실측값 100) | — |
| 왼쪽 최대 | 중심-45° | **일치 — 단 Phase 0이 잰 값과 다른 것을 잰 것**: 중심-60°(절대 40°)는 **서보 가동 범위**이고, **주행 가능 한계는 중심-45°**다. 45°를 넘기면 조향은 들어가지만 **전·후진이 안 된다**(0731 실측) | 45°는 여유분이 아니라 **한계 그 자체**. config `left_offset_limit_deg: 45` |
| 오른쪽 최대 | 중심+30° | **경계 일치, 단 위험**: 절대 130°가 링키지 걸림 경계. 그 지점에서 끌림으로 회전 부족, 넘어서면 조향 후 **전·후진 불가**(실측). 기존 코드는 기동 시 +25°(절대 125)로 제한 | 30°를 상한으로 유지하되 **지속 조향은 +25° 이하 권장**. config `right_offset_limit_deg: 30`. **2026-08-06부터 주행 계층의 우측 최대 명령은 +20°다** — TURN·ESCAPE·DEAD_END, 그리고 **AVOID 회피 선회**(`_bypass_command` → `_turn_steer_deg` 로 `turn.steer_right_deg` 를 공유한다). 권장 상한 25 대비 **5°, 하드 리밋 30 대비 10° 여유**가 남는다. 20 을 고른 것은 여유 때문이 아니라 실측이다: 같은 90% 스로틀에서 우 20° = 17.6 dps(반경 0.94m)이고 우 28° = 17.5 dps 로 **깊게 줘도 이득이 없다**. 좌측 최대는 25°(좌 한계 45 대비 20° 여유)이고 실측 10.9 dps·반경 1.51m — **우측이 60% 빠르고 반경이 38% 작다.** 그래서 U턴 방향을 `right` 로 고정했다(config `dead_end.uturn_direction`) |
| 주행 속도 | 보행 속도 약 4km/h | **차이 큼**: 기존 실측 캘리브레이션 0.0032 m/s per 1%스로틀 → 100%에서도 약 0.32m/s(≈1.2km/h) | 4km/h는 현 구동계로 불가 가능성. 스로틀 기본 50% 설정, **구동계 속도 재실측 필요** (평지 2m 주행 시간 측정) |
| IMU | (미도착) | 버스 7에 0x68 응답 없음 — 정상 | 도착 시 `hardware/jetson/imu.py` 구현 + 본 스크립트 재실행 |

## 5. config.yaml 반영 내역 (주행 관련 확정값)

- `hardware.i2c_bus: 7`, `drive_motor: {address 0x40, ch 5/4/3, 60Hz}`, `steering_servo: {address 0x60, ch 0, center 100}` — 실측·코드 추출로 확정
- `steering_servo.left_offset_limit_deg: 45 / right_offset_limit_deg: 30` — **주행 가능 조향 한계**(여유분이 아니다). 좌우 어느 쪽이든 초과하면 조향은 들어가도 **전·후진이 불가능**해진다 → 하드웨어 계층(`SteeringServo.clamp_offset_deg`)에서 강제 클램프
- `perception.nav_camera_device` — 주행용 Web Camera (by-id 고정 경로), `perception.inspect_camera_device` — 검사용 Brio 100 (by-id 고정 경로, 0731 확정)
- 속도·조향 이득 등 나머지 주행 튜닝값은 스켈레톤 기본값 — 필드 튜닝 대상
