"""진입점 — 단일 스레드 루프: 인지 입력 → fsm.step() → 하드웨어 명령.

인지·주행 판단·하드웨어 쓰기는 전부 이 루프 한 스레드에서 돈다. 하드웨어 명령 실행은
이 루프 한 곳뿐이다(single writer). 예외는 조작자 입력 리더 하나뿐이며(--no-operator로
끈다) 그쪽은 js 장치 이벤트를 래치만 하는 읽기 전용이라 single writer를 깨지 않는다.
기본은 dry-run(모의 장치·모의 인지, 로그만)이다:
  --live       를 줘야만 실물 모터·서보가 움직인다.
  --perception real 을 줘야만 실추론(카메라 + 세그 + 점자블록 검사)이 돈다
               (ultralytics·cv2 필요 — 보드 전용. 개발 PC는 mock만 가능).

  python3 main.py                          # dry-run + 기본 시나리오(obstacle_avoid)
  python3 main.py --scenario braille_trace    # 발견→최우선 접근→도착(저속 탐지)→복귀
  python3 main.py --live                   # 실구동 (주의: 모의 인지로 실제 모터가 돈다)
  python3 main.py --live --perception real # 보드 실주행 (실추론 + 실물 구동)
  scripts/drive.sh --live --comms-config comms.yaml  # MQTT 원격 미션

--comms-config를 주면 원격 미션 모드다: 자동 출발하지 않고 서버의 START_MISSION을
기다리며, 상태·텔레메트리·결함 event를 MQTT로 낸다. 안 주면 통신 코드는 import조차
되지 않는다 — paho·credential 없이도 종전 그대로 돈다.
"""

import argparse
import logging
import os
import signal
import time
from typing import Optional

import yaml

import reporting
from comms.codec import utc_now
from driving.state_machine import StateMachineParams, StateMachine
from driving.states import DriveState
from hardware.interfaces import DriveMotor, Imu, SteeringServo
from hardware.jetson.imu import JetsonImu
from perception.mocks import ScriptedSegmentationProvider

logger = logging.getLogger("robot")

_DEFAULT_CONFIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml")


def load_config(path: str = _DEFAULT_CONFIG) -> dict:
    """config.yaml 로드 — 튜닝값·경로의 단일 출처. 테스트도 이 함수를 쓴다."""
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def build_real_perception(config: dict, root: str, optional_inspect: bool = False):
    """실추론 조립 — 보드 전용 (ultralytics·cv2·numpy 필요). import를 이 안으로
    미뤄 개발 PC의 mock 경로가 무거운 의존성에 걸리지 않게 한다.
    실패는 즉시 예외로 죽인다 — 반쯤 눈먼 채 주행을 시작하는 것보다 낫다.

    optional_inspect: 검사 카메라 장치가 없으면 죽는 대신 (inspect_camera=None,
    inspector=None)으로 돌려준다 — 주행만 테스트할 때 검사 카메라를 빼고 다니는
    운용(웹 대시보드)용. 실주행(main)은 기본값 False로 여전히 즉시 죽는다."""
    from perception.braille import BrailleInspector, BrailleParams
    from perception.camera import Camera
    from perception.segmentation import (ClassNames, CostmapParams, FreeSpaceParams,
                                         PostParams, YoloSegmentationProvider)

    perception_cfg = config["perception"]
    seg = perception_cfg["segmentation"]
    nav_camera = Camera(str(perception_cfg["nav_camera_device"]))
    inspect_device = str(perception_cfg["inspect_camera_device"])
    if optional_inspect and not os.path.exists(inspect_device):
        logger.warning("검사 카메라 없음(%s) — 주행 전용: 점자블록 검사 비활성",
                       inspect_device)
        inspect_camera = None
    else:
        inspect_camera = Camera(inspect_device)
    # 클래스 역할(주행가능/차도/비용)은 7클래스 우선순위 표 하나에서만 파생된다
    classes = ClassNames.from_config(seg)
    provider = YoloSegmentationProvider(
        camera=nav_camera,
        model_path=os.path.join(root, seg["model_path"]),
        imgsz=int(seg["imgsz"]),
        conf=float(seg["conf"]),
        classes=classes,
        post=PostParams.from_config(seg["post"]),
        costmap=CostmapParams.from_config(seg["costmap"], classes),
        free_space=FreeSpaceParams.from_config(seg["free_space"]),
    )
    inspector = None
    if inspect_camera is not None:
        braille_cfg = perception_cfg["braille"]
        inspector = BrailleInspector(
            model_path=os.path.join(root, braille_cfg["model_path"]),
            imgsz=braille_cfg["imgsz"],
            conf=float(braille_cfg["conf"]),
            iou=float(braille_cfg["iou"]),
            params=BrailleParams.from_config(braille_cfg),
        )
    return provider, nav_camera, inspect_camera, inspector


def build_devices(config: dict, live: bool):
    """장치 조립. dry-run은 mock, --live만 실물. IMU는 항상 젯슨 실물 드라이버다 —
    셋업이 첫 read()로 지연되므로 센서 미배선 환경에서도 안전하고, 그 경우
    reading=None(게이트 닫힘)이라는 현실을 그대로 반영한다."""
    hardware_section = config["hardware"]
    servo_config = hardware_section["steering_servo"]
    imu_config = hardware_section["imu"]
    imu: Imu = JetsonImu(
        bus=imu_config["bus"], address=imu_config["address"],
        forward_axis=imu_config["forward_axis"], up_axis=imu_config["up_axis"],
        gyro_bias_samples=imu_config["gyro_bias_samples"],
        complementary_alpha=imu_config["complementary_alpha"],
        yaw_sign=imu_config["yaw_sign"],
    )

    if live:
        from hardware.jetson.drive_motor import JetsonDriveMotor
        from hardware.jetson.steering_servo import JetsonSteeringServo
        motor_config = hardware_section["drive_motor"]
        motor: DriveMotor = JetsonDriveMotor(
            address=motor_config["address"],
            pwm_frequency_hz=motor_config["pwm_frequency_hz"],
            pwm_channel=motor_config["pwm_channel"],
            in1_channel=motor_config["in1_channel"],
            in2_channel=motor_config["in2_channel"],
        )
        servo: SteeringServo = JetsonSteeringServo(
            address=servo_config["address"], channel=servo_config["channel"],
            center_deg=servo_config["center_deg"],
            left_offset_limit_deg=servo_config["left_offset_limit_deg"],
            right_offset_limit_deg=servo_config["right_offset_limit_deg"],
        )
    else:
        from hardware.mocks import MockDriveMotor, MockSteeringServo
        motor = MockDriveMotor()
        servo = MockSteeringServo(
            center_deg=servo_config["center_deg"],
            left_offset_limit_deg=servo_config["left_offset_limit_deg"],
            right_offset_limit_deg=servo_config["right_offset_limit_deg"],
        )
    return motor, servo, imu


def build_operator(config: dict, machine: StateMachine, enabled: bool):
    """조작자 입력(Xbox 컨트롤러) 연결 — IDLE·ESTOP에서 나올 수 있는 유일한 경로.

    왜 필요한가: IDLE과 ESTOP은 설계상 **수동 개입만이 출구**인 흡수 상태다. 그런데
    main 루프에는 request_start()의 기동 1회분 말고는 어떤 입력 경로도 없었고
    request_estop_release()를 부르는 코드가 아예 없었다 — 경계 소실이나 차도 위험
    판정이 한 번 걸리면 프로세스 재시작이 유일한 복구 수단이었다. 판정 오탐률이
    실외에서 낮지 않으므로 그건 '미션이 끝났다'와 같은 뜻이다.

      A 버튼 → 출발 요청 + ESTOP 해제 (IDLE·ESTOP 양쪽의 출구)
      B 버튼 → 수동 비상 정지

    리더는 읽기 전용 데몬 스레드다(scripts/xbox_controller.py 재사용). 장치를 만지지
    않으므로 '하드웨어 명령은 apply_command 한 곳'이라는 single writer 계약은 그대로다.
    장치가 없으면 None을 돌려주고 주행은 종전대로 진행한다 — 컨트롤러를 요구해서
    주행 자체를 막지는 않는다.
    """
    device = str(config["hardware"]["joystick"]["device"])
    if not enabled:
        return None
    if not os.path.exists(device):
        logger.warning("조작자 입력 장치 없음(%s) — IDLE·ESTOP에 들어가면 복구 수단이 "
                       "프로세스 재시작뿐입니다", device)
        return None
    from scripts.xbox_controller import XboxControllerReader

    def on_resume() -> None:
        machine.request_start()
        machine.request_estop_release()
        logger.info("[조작] A — 출발/ESTOP 해제 요청")

    reader = XboxControllerReader(config["hardware"]["joystick"],
                                  on_manual_toggle=on_resume)
    reader.start()
    logger.info("조작자 입력 연결: %s (A=출발·ESTOP 해제, B=비상 정지)", device)
    return reader


def build_gps(config: dict):
    """GPS 리더 연결 — 보고의 좌표 태깅(명세 4단계)에만 쓰인다. 없으면 None.

    장치가 없어도 주행은 그대로 한다: 좌표가 없다고 파손 사실을 버릴 이유는 없고,
    보고 레코드에 fix=false로 남으므로 통신 코드가 지도에 올릴지 판단할 수 있다.
    리더는 읽기 전용 데몬 스레드다(scripts/gps_reader.py) — 장치를 만지지 않으므로
    '하드웨어 명령은 apply_command 한 곳'이라는 single writer 계약은 그대로다.
    """
    gps_cfg = config["hardware"]["gps"]
    device = str(gps_cfg["device"])
    if not os.path.exists(device):
        logger.warning("GPS 장치 없음(%s) — 보고에 좌표가 빠진다 (주행은 계속)", device)
        return None
    from scripts.gps_reader import GpsReader

    reader = GpsReader(device, int(gps_cfg["baud"]))
    reader.start()
    logger.info("GPS 연결: %s", device)
    return reader


def apply_command(motor: DriveMotor, servo: SteeringServo, command) -> None:
    """DriveCommand → 장치 쓰기. 주행 중 장치에 명령을 쓰는 유일한 지점(single writer).
    장치 읽기(imu.read·camera.read)는 루프가 직접 한다.
    예외는 장치 생성자의 1회성 안전 초기화(모터 정지·조향 중립)뿐이다."""
    servo.set_offset_deg(command.steering_offset_deg)
    if command.brake:
        motor.brake()
    elif command.throttle_pct == 0:
        motor.stop()
    else:
        motor.set_throttle_pct(command.throttle_pct)


def capture_verdict(camera, inspector, flush_frames: int = 0):
    """검사 카메라 1장 캡처 → 탐지 모델 판정 1회 → (판정, 사진). 실패는 (None, 사진|None).

    사진을 함께 돌려주는 이유는 보고(명세 4단계 '사진 촬영 + gps 태깅')다 — 판정 근거가
    된 프레임과 보고에 실리는 사진이 같아야 하고, 보고 시점에 다시 읽으면 그 사이
    로봇이 움직인 다른 화면이 올라간다.

    캡처는 전방 주행 카메라가 아니라 하향 마운트된 검사 전용 카메라(Brio 100)로 한다 —
    전방 카메라에는 발밑 블록이 화면 하단 가장자리에만 걸리기 때문이다. 탐지 모델
    (models/braille_defect.pt, 3클래스 탐지)의 실행은 I/O이므로
    FSM 밖이고, 이 함수가 그 유일한 실행 지점이다 (TRACE 상시 탐지와 INSPECT 정밀
    판정이 같은 추론을 공유한다 — 판정 알고리즘이 두 벌로 갈라지지 않게).
    같은 가중치를 단독 실행하는 원본 번들은 tools/braille_detect/ 다.

    flush_frames: 본 캡처 전에 버리는 read 횟수. 카메라를 띄엄띄엄 읽으면 V4L2 내부
    버퍼(BUFFERSIZE=1)에 낡은 프레임이 남아 블록 N 보고에 블록 N-1 사진이 실린다.
    TRACE는 매 주기 읽으므로 0으로 부르고, INSPECT는 정차 상태라 몇 장 버려도
    settle_sec·timeout_sec 대비 무해하다. 버리는 read의 실패는 무시한다.
    """
    if inspector is None or camera is None:
        return None, None
    for _ in range(flush_frames):
        camera.read()
    image = camera.read()
    if image is None:
        logger.warning("[탐지] 캡처 실패 — 이번 주기 판정 생략")
        return None, None
    try:
        return inspector.inspect(image), image
    except Exception:
        # 일시 추론 오류(CUDA OOM·손상 프레임 등)로 미션 전체가 죽어선 안 된다.
        logger.exception("[탐지] 추론 실패 — 이번 주기 판정 생략")
        return None, image


def run_detection(camera, inspector, machine) -> Optional[bool]:
    """TRACE 상시 탐지 1주기: 주행 중 캡처 → 판정 → 파손 후보면 FSM에 통지.

    돌려주는 값은 계측용이다(reporting.TraceSession) — 파손 후보였나(True/False),
    또는 추론 자체가 못 돈 경우 None.

    통지를 받은 FSM은 INSPECT로 정차해 같은 모델로 정밀 판정을 다시 한다 — 단
    정차 쿨다운(driving.inspect.cooldown_sec) 안이면 통지를 무시하고 추종을 계속한다
    (같은 파손 위에서 정차가 무한 반복되는 것을 막는다). 주행 중 판정을 그대로
    신뢰하지 않는 이유는 모션 블러다 — defect_partial(≈55px)부터 먼저 무너지므로
    여기서는 '멈춰 볼 가치가 있는가'만 판단한다(1차 스크리닝).
    검사기가 없는 mock 인지에서는 아무 일도 하지 않는다 — TRACE는 소실·타임아웃으로
    정상 종료되므로 흐름 검증에 지장이 없다.
    """
    verdict, _ = capture_verdict(camera, inspector, flush_frames=0)
    if verdict is None:
        return None
    logger.debug("[탐지] %s — %s", "후보" if verdict.is_defect else "정상", verdict.reason)
    if verdict.is_defect:
        logger.info("[탐지] 파손 후보 — %s → 정차 판정 요청", verdict.reason)
        machine.notify_defect_found()
    return bool(verdict.is_defect)


def detection_due(frame, arrived_proximity: float) -> bool:
    """이번 틱에 탐지 추론을 돌릴 상황인가 — 블록에 **도착했는가**로 판단한다.

    TRACE는 이제 발견 즉시 시작되고 접근 구간까지 포함한다(블록 최우선 이동). 그
    구간에서는 탐지를 돌리지 않는다. 이유가 둘이고 둘 다 실측 근거가 있다:
      1. 하향 검사 카메라는 로봇 바로 앞 노면을 본다 — 아직 멀리 있는 블록은 그
         화면에 없다. 없는 것을 찾는 추론이다.
      2. imgsz 960 추론은 Orin Nano에서 수백 ms급이고 그 시간 동안 단일 스레드 루프
         전체가 멈춘다(인지·조향 갱신 포함). 접근 구간은 순찰 속도로 달리는 구간이라
         그 정지가 곧 '눈먼 채 최고 속도로 주행'이 된다 — 저속 추종 구간에서 감당
         가능하던 비용과 같은 비용이 아니다.
    기준은 새 임계값이 아니라 FSM이 '블록에 붙었다'로 쓰는 것과 같은 값
    (driving.inspect.enter_proximity — 추종 비중과 탐지 저속이 완성되는 지점)이다.
    """
    return frame is not None and frame.braille_proximity >= arrived_proximity


def run_inspection(camera, inspector, machine, flush_frames: int = 0,
                   on_defect=None) -> None:
    """INSPECT 1회 실행: 정차 화면 캡처 → 정밀 판정 → (이상이면 보고) → 완료 통지.

    mock 인지(검사기 없음)에서는 즉시 완료 통지만 한다 — FSM 흐름 검증용.
    캡처 실패·판정 실패도 완료로 통지한다: INSPECT에 갇혀 있는 것보다 쿨다운을
    걸고 지나가는 편이 낫다 (타임아웃은 통지 유실 대비 이중 안전판).

    on_defect(verdict, image, captured_at): 이상 판정 시 호출되는 보고 훅 (명세 4단계
    '사진 촬영 + gps 태깅'). 보고 실패는 여기서 흡수되지 않고 훅 안에서 처리된다 —
    reporting.write_report는 예외를 올리지 않는다. 훅을 인자로 받는 이유는 이 함수가
    보고 정책(경로·쿨다운·좌표·전송)을 몰라도 되게 하는 것이다.
    captured_at은 **캡처 직전** UTC 시각이다 — 판정 추론이 수백 ms 걸리므로 보고 시점
    시각을 쓰면 사진과 시각이 어긋난다. 서버는 미래 시각의 event를 거부한다(계약 §4).
    """
    captured_at = utc_now()
    verdict, image = capture_verdict(camera, inspector, flush_frames)
    if verdict is None:
        if inspector is None or camera is None:
            logger.info("[INSPECT] 모의 판정 — 검사 모델 없음, 즉시 완료 통지")
        machine.notify_inspection_done()
        return
    logger.info("[INSPECT] 판정: %s — %s (띠 %d개, 불량 띠 %d개, 국소 파손 %d곳)",
                "불량" if verdict.is_defect else "정상", verdict.reason,
                verdict.band_count, verdict.defect_band_count, verdict.partial_count)
    if verdict.is_defect and on_defect is not None:
        on_defect(verdict, image, captured_at)
    machine.notify_inspection_done()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default=_DEFAULT_CONFIG, help="config.yaml 경로")
    parser.add_argument("--scenario", default="obstacle_avoid",
                        help="mock_scenarios의 시나리오 이름")
    parser.add_argument("--live", action="store_true",
                        help="실구동 모드 (기본은 dry-run: 모의 장치에 로그만)")
    parser.add_argument("--perception", choices=("mock", "real"), default="mock",
                        help="인지 소스: mock=시나리오 재생(기본), real=카메라+실추론(보드 전용)")
    parser.add_argument("--comms-config", default=None,
                        help="MQTT v2 설정 경로. 주면 원격 미션 모드, 안 주면 종전 자동 시작")
    parser.add_argument("--duration", type=float, default=None,
                        help="최대 실행 시간(초) — 미지정 시 시나리오 종료(mock) / 무제한(real)")
    parser.add_argument("--no-operator", action="store_true",
                        help="조작자 입력(Xbox: A=출발·ESTOP 해제, B=비상 정지)을 붙이지 않는다")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")

    config = load_config(args.config)
    config_root = os.path.dirname(os.path.abspath(args.config))

    if args.live:
        logger.warning("실구동 모드: 실제 모터·서보가 움직입니다!")
    else:
        logger.info("dry-run 모드: 모든 장치 명령은 로그로만 나갑니다 (--live로 실구동)")

    nav_camera = None
    inspect_camera = None
    inspector = None
    if args.perception == "real":
        provider, nav_camera, inspect_camera, inspector = build_real_perception(
            config, config_root)
        # 검사 카메라 프라이밍: V4L2 스트리밍은 첫 read에서 시작되고 AE(자동 노출)는
        # 그때부터 수렴한다. 이 카메라는 INSPECT 때만 읽히므로 프라이밍이 없으면
        # 첫 검사가 노출이 덜 잡힌 화면으로 판정된다. 두 카메라 동시 스트리밍은
        # MJPG 강제로 이미 전제된 운용이다 (docs/HARDWARE.md).
        inspect_camera.read()
        logger.info("실추론 인지: 주행 카메라 %s(세그멘테이션) + 검사 카메라 %s(점자블록 파손)",
                    config["perception"]["nav_camera_device"],
                    config["perception"]["inspect_camera_device"])
    else:
        scenarios = config["mock_scenarios"]
        if args.scenario not in scenarios:
            parser.error(f"시나리오 '{args.scenario}' 없음 — 선택지: {', '.join(scenarios)}")
        provider = ScriptedSegmentationProvider.from_config(scenarios[args.scenario])
    motor, servo, imu = build_devices(config, live=args.live)

    # 터치 센서 — 명세의 '터치 시 주행 탐지 시작'. 핀 미지정이면 None(조작자 입력만).
    from hardware.jetson.touch import build_touch_sensor
    touch = build_touch_sensor(config)

    # 보고(사진 + GPS 태깅) — 명세 4·5단계. 전송은 젯슨의 통신 코드가 이어받는다.
    gps = build_gps(config) if args.perception == "real" else None
    report_cfg = config["reporting"]
    report_dir = os.path.join(config_root, str(report_cfg["output_dir"]))
    save_images = bool(report_cfg["save_images"])
    crosswalk_gate = reporting.ReportGate(
        confirm_frames=int(report_cfg["crosswalk_confirm_frames"]),
        cooldown_sec=float(report_cfg["crosswalk_cooldown_sec"]))
    # 파손 보고는 INSPECT 정차 판정 1회당 1건이므로 프레임 확정이 필요 없다 —
    # 쿨다운만 걸어 같은 타일 재판정의 중복 보고를 막는다.
    defect_gate = reporting.ReportGate(
        confirm_frames=1, cooldown_sec=float(report_cfg["defect_cooldown_sec"]))

    def gps_status():
        return gps.status() if gps is not None else None

    def report_defect(verdict, image, captured_at) -> None:
        # 로컬 기록과 서버 전송은 **각각 독립**이다. 쿨다운은 로컬 기록에만 건다:
        # 서버 쪽 중복은 event_id 멱등 처리가 받고(계약 §3.4), 여기서 같은 게이트로
        # 묶으면 로컬 디스크 정책 하나가 결함 보고 유실로 이어진다.
        if defect_gate.should_report(True, time.monotonic()):
            reporting.write_report(
                report_dir, reporting.KIND_DEFECT, gps_status(), image=image,
                save_images=save_images,
                extra={"reason": verdict.reason, "bands": verdict.band_count,
                       "defect_bands": verdict.defect_band_count,
                       "partials": verdict.partial_count})
        if comms_runtime is not None:
            try:
                comms_runtime.report_inspection(verdict, image, captured_at)
            except Exception:
                # 업로드·발행 실패가 주행을 멈추게 하지 않는다. event는 Outbox가
                # 재전송하고, 그마저 실패해도 로봇은 계속 순찰해야 한다.
                logger.exception("[INSPECT] 결함 보고 실패 — 주행은 계속한다")

    trace_session = reporting.TraceSession()

    def log_transition(prev: DriveState, new: DriveState, reason: str, now: float) -> None:
        # 안전·운용 요구: 모든 상태 전이를 타임스탬프와 함께 로깅.
        # 이 훅이 상태 전이 알림의 유일한 출구다 — 3단계 MQTT 발행부도 여길 구독한다.
        logger.info("[FSM] %s → %s (%s) t=%.3f", prev.value, new.value, reason, now)
        # 추종 세션 계측 — 세션이 끝난 틱에만 한 줄 남는다 (사진 없음: 커버리지 기록이다)
        summary = trace_session.on_transition(prev, new, reason, now)
        if summary is not None:
            logger.info("[추종] 세션 종료 — %.1f초, 탐지 %d회, 파손 후보 %d회",
                        summary["duration_sec"], summary["detections"],
                        summary["defect_candidates"])
            reporting.write_report(report_dir, reporting.KIND_TRACE, gps_status(),
                                   save_images=False, extra=summary)

    machine = StateMachine(params=StateMachineParams.from_config(config["driving"]),
                           now=time.monotonic(), on_event=log_transition)
    operator = build_operator(config, machine, enabled=not args.no_operator)
    comms_runtime = None
    if args.comms_config:
        # 통신 의존성은 옵션을 준 실기 경로에서만 import한다.
        # dry-run·단위 테스트가 paho/credential 없이도 계속 돌아간다.
        # GPS 리더는 위에서 연 것을 **공유**한다 — 같은 시리얼 장치를 통신 쪽이
        # 또 열면 NMEA 문장을 두 리더가 나눠 읽어 양쪽 좌표가 다 깨진다.
        # (mock 인지라 gps가 None이면 runtime이 스스로 만들어 소유한다)
        from communication import CommunicationRuntime
        comms_runtime = CommunicationRuntime.build(
            args.comms_config, config, live=args.live, gps_reader=gps)
        comms_runtime.start()
        logger.info("MQTT 원격 미션 모드 — START_MISSION 전까지 정지 대기")
    else:
        machine.request_start()
    if args.perception == "mock":
        logger.info("시나리오 '%s' 시작 — 상태: %s", args.scenario, machine.state.value)
    else:
        logger.info("실주행 시작 — 상태: %s (Ctrl+C/SIGTERM 또는 --duration으로 종료)",
                    machine.state.value)

    def on_terminate(signum, frame) -> None:
        # SIGTERM(systemd stop)도 Ctrl+C와 같은 종료 경로로 보낸다. 이 핸들러가
        # 없으면 파이썬 기본 동작이 프로세스를 즉시 죽여 finally 절이 통째로
        # 건너뛰어진다 — 바퀴가 마지막 명령 그대로 돌고, SHUTTING_DOWN state와
        # presence OFFLINE(GRACEFUL_SHUTDOWN) 대신 브로커의 LWT가 나간다.
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, on_terminate)

    period = 1.0 / config["driving"]["loop_hz"]
    settle_sec = float(config["driving"]["inspect"]["settle_sec"])
    detect_period_sec = float(config["driving"]["trace"]["detect_period_sec"])
    arrived_proximity = float(config["driving"]["inspect"]["enter_proximity"])
    flush_frames = int(config["perception"]["inspect_capture_flush_frames"])
    started = time.monotonic()
    inspected_entry = None   # INSPECT 1회 실행 마커 — 같은 진입(entered_at)에 재실행 금지
    last_detect_at = None    # TRACE 상시 탐지의 직전 추론 시각 (주기 제한)
    mission_started = False  # 최초 CRUISE 진입 여부 — 시작 요청 유지의 종료 조건

    def mock_finished() -> bool:
        # 실추론 provider에는 finished가 없다 — real 모드는 duration/Ctrl+C가 출구
        return bool(getattr(provider, "finished", False))

    try:
        while not mock_finished():
            now = time.monotonic()
            if args.duration is not None and now - started >= args.duration:
                break

            # 시작 요청은 1회성으로 소비된다 — 기동 직후 첫 프레임이 불량(카메라
            # 워밍업·노출 안정화 전)이면 유실되므로, 최초 CRUISE 진입 전까지는 매 틱
            # 재요청한다. 진입 후에는 재요청하지 않는다: 경계 소실 폴백(IDLE)의
            # '수동 재시작이 유일한 출구' 안전 설계를 침범하지 않기 위해서다.
            # 원격 미션 모드에서는 자동 재시작을 하지 않는다 — 출발은 서버의
            # START_MISSION만이 시킨다. 대신 큐에 쌓인 명령을 여기서 소비한다.
            if comms_runtime is not None:
                # Paho 스레드는 큐에만 넣고, 실제 FSM 요청은 제어 루프가 소비한다.
                urgent_stop = comms_runtime.poll_before_step(machine)
                if urgent_stop:
                    # PAUSE·CANCEL·ESTOP은 새 YOLO 추론을 기다리지 않는다.
                    # 공통 가드가 인지보다 우선이므로 frame=None으로도 즉시
                    # 정지 전이가 되며, result는 하드웨어 쓰기 뒤에 나간다.
                    command = machine.step(None, None, now)
                    apply_command(motor, servo, command)
                    comms_runtime.poll_after_step(machine, command, camera_ok=None)
                    time.sleep(max(0.0, period - (time.monotonic() - now)))
                    continue
            elif not mission_started and machine.state is DriveState.IDLE:
                machine.request_start()

            # 조작자 비상 정지(B) 래치 소비. 래치는 스로틀 입력을 다 놓으면 리더가
            # 스스로 풀지만(컨트롤러 정책), FSM의 ESTOP은 A(해제 요청)로만 나온다.
            if operator is not None and operator.estop_latched:
                machine.request_estop()

            # 터치 센서 — 누름 엣지 1회가 시작 요청 + ESTOP 해제다(Xbox A와 같은 역할).
            # IDLE·ESTOP의 출구를 하나 더 주는 것이고, 엣지이므로 누르고 있어도
            # 자동 재출발이 되지는 않는다.
            if touch is not None and touch.pressed():
                machine.request_start()
                machine.request_estop_release()
                logger.info("[터치] 시작/ESTOP 해제 요청")

            frame = provider.get_latest()          # 인지 입력 (풀 방식)
            if mock_finished():
                break
            reading = imu.read()                   # IMU (미도착 → None → 경사·회차 비활성)
            command = machine.step(frame, reading, now)   # 주행 판단 (순수)
            apply_command(motor, servo, command)   # 하드웨어 명령 (single writer)
            if comms_runtime is not None:
                # 명령 result의 COMPLETED는 반드시 실제 하드웨어 쓰기 뒤에만 나간다.
                comms_runtime.poll_after_step(
                    machine, command, camera_ok=(frame is not None))
            if machine.state is not DriveState.IDLE:
                mission_started = True

            # 횡단보도 점자블록 **미설치 구역** 보고 (명세 5단계) — 주행 상태는 바꾸지
            # 않는다. 판정은 인지 계층이 하고(세그 프레임 필드), 여기서는 연속 프레임
            # 확정 + 쿨다운만 걸어 기록한다. 증거 사진은 판정 근거가 된 주행 카메라
            # 프레임 그대로다 — 다시 읽으면 그새 횡단보도가 화면에서 벗어난다.
            if frame is not None and crosswalk_gate.should_report(
                    frame.braille_missing_at_crosswalk, now):
                missing_image = getattr(provider, "last_image", None)
                reporting.write_report(
                    report_dir, reporting.KIND_MISSING, gps_status(),
                    image=missing_image,
                    save_images=save_images,
                    extra={"note": "횡단보도-인도 이음부 점자블록 미설치"})
                # 서버 보고 — 파손과 달리 게이트를 공유한다. 파손은 INSPECT 정차가
                # 블록당 1회를 보장하지만 미설치는 이 게이트가 판정 자체이고, 서버
                # 멱등 처리로는 프레임마다 달라지는 event_id 를 묶을 수 없다.
                if comms_runtime is not None:
                    try:
                        comms_runtime.report_missing_braille(missing_image)
                    except Exception:
                        # 파손과 같은 원칙 — 보고가 실패해도 순찰은 계속한다.
                        logger.exception("[미설치] 서버 보고 실패 — 주행은 계속한다")

            # TRACE: 블록에 **도착한 뒤** 저속 추종하는 동안 탐지 모델을
            # detect_period_sec 주기로 돌린다(상시 탐지). 접근 구간(도착 전)에는 돌리지
            # 않는다 — 근거는 detection_due(). 매 틱 돌리지 않는 이유는 imgsz 960 추론이
            # 단일 스레드 루프 전체를 수백 ms 멈춰 세우기 때문이다 — 그 사이 인지·조향
            # 갱신도 함께 멈춘다. 파손 후보가 나오면 FSM이 INSPECT로 정차한다.
            if machine.state is DriveState.TRACE and detection_due(frame,
                                                                   arrived_proximity):
                if last_detect_at is None or now - last_detect_at >= detect_period_sec:
                    last_detect_at = now
                    trace_session.count_detection(
                        run_detection(inspect_camera, inspector, machine))
            elif machine.state is not DriveState.TRACE:
                last_detect_at = None   # 추종을 떠나면 주기를 새로 시작한다

            # INSPECT: 정밀 판정도 I/O → FSM 밖(여기)이 유일한 실행 지점.
            # entered_at 마커로 진입 1회당 정확히 1번만 돌되, 정지 '명령'과 물리적
            # 정차 사이의 감속 시간(settle_sec)을 기다렸다 캡처한다 — 굴러가는 중
            # 캡처는 모션 블러로 defect_partial(≈55px)부터 무너진다.
            if machine.state is DriveState.INSPECT:
                entry = machine.context.entered_at
                if entry != inspected_entry and now - entry >= settle_sec:
                    inspected_entry = entry
                    run_inspection(inspect_camera, inspector, machine, flush_frames,
                                   on_defect=report_defect)

            # 루프 주기 유지 (main.py에서만 허용) — 작업 시간을 차감한 잔여만 잔다.
            # 고정 sleep이면 보드에서 실주기가 period + 추론 시간으로 늘어진다.
            time.sleep(max(0.0, period - (time.monotonic() - now)))
    except KeyboardInterrupt:
        logger.info("종료 요청(Ctrl+C/SIGTERM) — 안전 정지 진행")
    finally:
        apply_command(motor, servo, machine.step(None, None, time.monotonic()))
        if comms_runtime is not None:
            # 바퀴를 세운 뒤에 닫는다 — shutdown이 진행 중 미션을 FAILED로 닫고
            # presence OFFLINE(GRACEFUL_SHUTDOWN)까지 보낸 뒤 소켓을 내린다.
            comms_runtime.shutdown()
        if operator is not None:
            operator.stop()
        if gps is not None:
            gps.stop()
        if touch is not None:
            touch.close()
        for device in (nav_camera, inspect_camera):
            if device is not None:
                device.close()

    logger.info("종료 — 최종 상태: %s (안전 정지 완료)", machine.state.value)


if __name__ == "__main__":
    main()
