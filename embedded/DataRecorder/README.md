# DataRecorder

엑스박스 컨트롤러로 봇을 조종하면서 카메라 2대의 주행 영상을 녹화하는 프로그램.
녹화 시작/중지는 웹 대시보드 또는 컨트롤러 A 버튼으로 제어한다.

## 실행

```bash
cd /home/ubuntu/SSAFY/DataRecorder
python3 main.py
# 대시보드: http://<jetson-ip>:8100  (segTest가 8000을 쓰므로 8100)
```

## 조작 (Xbox 360 컨트롤러, /dev/input/js0)

| 입력 | 동작 |
|---|---|
| 왼쪽 스틱 X | 조향 (STEERING_CENTER ±60°, 락 범위는 motor_driver가 클램프) |
| 왼쪽 스틱 Y | 전진(위) / 후진(아래) |
| RT | 전진 (스틱과 병용, 큰 값 적용) |
| LT | 후진 (최대 45%) |
| B | 비상정지 — 모든 스로틀 입력을 놓으면 재무장 |
| A | 녹화 시작/중지 토글 |
| X / Y | 최대 스로틀 −10% / +10% (범위 20~100%, 시작값 70%) |

전진 속도는 THROTTLE_MIN(12%)~현재 최대 스로틀 사이로 스케일된다.
DrivingModule의 MAX_SPEED=85 클램프는 이 프로세스 안에서만 100으로 올린다
(파일은 건드리지 않음).

컨트롤러가 끊기면 즉시 모터 정지. (xpad 모듈은 직접 빌드한 것 —
커널 업데이트 시 `/home/ubuntu/xpad-build`에서 재빌드 필요)

## 출력 (recordings/<YYYYmmdd_HHMMSS>/)

- `nav.mp4` — 주행 카메라 (video0, 640x480)
- `front.mp4` — 전방 카메라 (video2, 640x480)
- `control_log.csv` — 20Hz 조향/스로틀 로그: `t_wall, t_rel, nav_frame,
  front_frame, angle, speed, direction`. 각 행의 `*_frame`이 그 시점까지
  기록된 프레임 번호이므로 영상 프레임과 조작 라벨을 정렬할 수 있다
  (모방학습 데이터셋 용도).

## 주의

- 두 카메라가 USB 2.0 허브 하나를 공유하므로 캡처는 MJPG 강제
  (DrivingModule/config.py 참고). 실측 프레임레이트는 설정값의 절반 수준
  (nav ~15fps, front ~7.5fps)이며, 영상 파일은 **실측 fps**로 기록되므로
  재생 속도는 정상이다.
- 하드웨어 설정(카메라 인덱스, 조향 한계, I2C 주소)은 DrivingModule/config.py를
  그대로 import해서 쓴다. 이 폴더에 `config.py`를 만들면 안 됨
  (motor_driver의 `import config`가 잘못 해석된다) — 로컬 설정은
  `recorder_config.py`에 있다.
