"""drive/·detect/ 하드웨어 모듈 단위 테스트.

장치 없이 돈다. 시리얼·I2C·카메라·모델은 전부 주입해서 파서와 판정만 시험한다.
개발 PC 에서 import 조차 안 되는 라이브러리(serial·cv2·ultralytics)를 쓰는 경로는
"없으면 FAULT" 로 끝나는지만 본다 — 실제 동작은 Orin 에서 확인한다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §5.3·§8.3·§10.2
"""

import queue
import struct
import time

import pytest

from detect import camera as camera_module
from detect import inference as inference_module
from detect.camera import Camera
from detect.inference import DEFAULT_CLASS_MAP, Detector
from drive import motor as motor_module
from drive import safety as safety_module
from drive.motor import MotorController
from drive.safety import STALE_SEC, WATCHDOG_SEC, LidarReader


class FakeClock:
    def __init__(self, start: float = 1000.0) -> None:
        self.value = start

    def monotonic(self) -> float:
        return self.value

    def advance(self, seconds: float) -> None:
        self.value += seconds


@pytest.fixture
def clock():
    return FakeClock()


# --------------------------------------------------------------------------
# drive/safety.py — YDLidar 프레임 파서
# --------------------------------------------------------------------------


def lidar_frame(start_deg: float, end_deg: float, distances_m) -> bytes:
    """YDLidar 0xAA55 프레임 한 장. 파서가 읽는 필드만 채운다."""
    payload = bytearray([0xAA, 0x55, 0x00, len(distances_m)])
    # 센서는 0~360 도로 보낸다. -180~180 으로 접는 것은 파서(장착 오프셋)의 몫이다.
    payload += struct.pack(
        "<HH", int(start_deg % 360 * 64) << 1, int(end_deg % 360 * 64) << 1
    )
    payload += b"\x00\x00"  # checksum — 파서가 검사하지 않는다
    for distance in distances_m:
        payload += struct.pack("<H", int(round(distance * 4000)))
    return bytes(payload)


def reader(clock, **config) -> LidarReader:
    settings = {"lidar_angle_offset_deg": 0.0, "stop_distance_m": 0.40}
    settings.update(config)
    return LidarReader(settings, monotonic=clock.monotonic)


def test_the_parser_turns_a_frame_into_points(clock):
    lidar = reader(clock)
    lidar._consume(bytearray(lidar_frame(350.0, 10.0, [1.5, 0.9, 2.0])))
    assert lidar.front_distance_m() == 0.9


def test_a_partial_frame_waits_for_the_rest(clock):
    lidar = reader(clock)
    frame = lidar_frame(350.0, 10.0, [1.5, 0.9])
    buffer = bytearray(frame[:-2])
    lidar._consume(buffer)
    assert lidar.front_distance_m() is None  # 아직 덜 왔다
    buffer.extend(frame[-2:])
    lidar._consume(buffer)
    assert lidar.front_distance_m() == 0.9


def test_garbage_before_the_header_is_skipped(clock):
    lidar = reader(clock)
    lidar._consume(bytearray(b"\x01\x02\x03" + lidar_frame(355.0, 5.0, [1.2])))
    assert lidar.front_distance_m() == 1.2


def test_points_outside_the_scan_range_are_dropped(clock):
    lidar = reader(clock, min_scan_distance_m=0.10, max_scan_distance_m=4.00)
    lidar._consume(bytearray(lidar_frame(355.0, 5.0, [0.05, 9.0, 1.1])))
    assert lidar.front_distance_m() == 1.1


def test_points_outside_the_front_fov_are_ignored(clock):
    lidar = reader(clock)
    lidar._consume(bytearray(lidar_frame(80.0, 100.0, [0.2, 0.2])))
    # 옆에 붙은 벽은 전방 정지 판정 대상이 아니다.
    assert lidar.front_distance_m() is None


def test_the_mounting_offset_rotates_the_scan(clock):
    """장착 오프셋(실측 200도)을 빼야 0도가 정면이 된다."""
    lidar = reader(clock, lidar_angle_offset_deg=200.0)
    lidar._consume(bytearray(lidar_frame(195.0, 205.0, [0.8, 0.8, 0.8])))
    assert lidar.front_distance_m() == 0.8


def test_check_reports_an_obstacle_inside_the_stop_distance(clock):
    lidar = reader(clock, stop_distance_m=0.40)
    lidar._consume(bytearray(lidar_frame(355.0, 5.0, [0.35])))
    assert lidar.check() == (True, 0.35)


def test_check_reports_a_clear_path_beyond_the_stop_distance(clock):
    lidar = reader(clock, stop_distance_m=0.40)
    lidar._consume(bytearray(lidar_frame(355.0, 5.0, [1.20])))
    assert lidar.check() == (False, 1.2)


def test_no_scan_means_a_null_distance_not_a_magic_number(clock):
    """계약 §3.1 — 의미 있는 값이 없으면 임의의 숫자 대신 null 이다."""
    obstacle, distance_m = reader(clock).check()
    assert (obstacle, distance_m) == (False, None)
    assert distance_m != 99.0  # 원본의 front_min_dist 매직값이 새지 않는다


def test_a_measurement_expires_so_a_passed_obstacle_stops_counting(clock):
    """지나쳐 온 장애물의 거리가 계속 남아 있으면 없는 것 때문에 서 있게 된다."""
    lidar = reader(clock, scan_ttl_sec=0.5)
    lidar._consume(bytearray(lidar_frame(355.0, 5.0, [0.35])))
    assert lidar.check() == (True, 0.35)
    clock.advance(0.6)
    assert lidar.check() == (False, None)


def test_a_fresh_sweep_replaces_the_expired_one(clock):
    lidar = reader(clock, scan_ttl_sec=0.5)
    lidar._consume(bytearray(lidar_frame(355.0, 5.0, [0.35])))
    clock.advance(0.6)
    lidar._consume(bytearray(lidar_frame(355.0, 5.0, [1.20])))
    assert lidar.check() == (False, 1.2)


def test_expired_points_do_not_pile_up_in_memory(clock):
    lidar = reader(clock, scan_ttl_sec=0.5)
    for _ in range(3):
        lidar._consume(bytearray(lidar_frame(0.0, 20.0, [1.0] * 20)))
        clock.advance(0.6)
    lidar._consume(bytearray(lidar_frame(0.0, 20.0, [1.0] * 20)))
    assert len(lidar._points) == 20  # 마지막 한 바퀴만 남는다


# --------------------------------------------------------------------------
# drive/safety.py — health (계약 §5.3)
# --------------------------------------------------------------------------


class FakeSerial:
    def __init__(self, data: bytes = b"") -> None:
        self.buffer = bytearray(data)
        self.is_open = True
        self.written = []

    @property
    def in_waiting(self) -> int:
        return len(self.buffer)

    def read(self, size: int) -> bytes:
        chunk = bytes(self.buffer[:size])
        del self.buffer[:size]
        return chunk

    def write(self, data) -> None:
        self.written.append(data)

    def flushInput(self) -> None:
        pass

    def close(self) -> None:
        self.is_open = False


def wait_until(predicate, timeout: float = 2.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.02)
    return False


def test_lidar_health_is_unknown_before_it_starts(clock):
    assert reader(clock).health() == "UNKNOWN"


def test_lidar_health_is_fault_when_the_port_will_not_open(clock):
    def broken(port, baudrate):
        raise OSError("no such device")

    lidar = LidarReader({}, serial_factory=broken, monotonic=clock.monotonic)
    lidar.start()
    try:
        assert wait_until(lambda: lidar.health() == "FAULT")
    finally:
        lidar.stop()


def test_lidar_health_walks_from_ok_to_degraded_to_fault(clock):
    port = FakeSerial(lidar_frame(355.0, 5.0, [1.0] * 20))
    lidar = LidarReader(
        {"lidar_angle_offset_deg": 0.0, "min_scan_points": 10},
        serial_factory=lambda *_: port,
        monotonic=clock.monotonic,
    )
    lidar.start()
    try:
        assert wait_until(lambda: lidar.health() == "OK")
        clock.advance(STALE_SEC + 0.1)
        assert lidar.health() == "DEGRADED"  # 패킷은 왔는데 늦는다
        clock.advance(WATCHDOG_SEC)
        assert lidar.health() == "FAULT"
    finally:
        lidar.stop()


def test_a_sparse_scan_is_degraded(clock):
    lidar = reader(clock, min_scan_points=10)
    lidar._running = True
    lidar._connected = True
    lidar._consume(bytearray(lidar_frame(355.0, 5.0, [1.0, 1.1])))
    # 각도 커버리지를 믿을 수 없다 — 장애물이 없다고 말하면 안 된다.
    assert lidar.health() == "DEGRADED"


def test_the_watchdog_reopens_the_port(clock):
    opened = []

    def factory(port, baudrate):
        opened.append(port)
        return FakeSerial()

    lidar = LidarReader({}, serial_factory=factory, monotonic=clock.monotonic)
    lidar.start()
    try:
        assert wait_until(lambda: len(opened) == 1)
        clock.advance(WATCHDOG_SEC + 1)
        assert wait_until(lambda: len(opened) >= 2)
    finally:
        lidar.stop()


def test_the_module_singleton_is_unknown_until_it_starts():
    # main.py 가 start() 를 부르기 전 상태 — 개발 PC 에서 계속 이 상태다.
    assert safety_module.health() == "UNKNOWN"
    assert safety_module.check() == (False, None)


# --------------------------------------------------------------------------
# drive/motor.py
# --------------------------------------------------------------------------


class FakeDriver:
    def __init__(self, broken: bool = False) -> None:
        self.broken = broken
        self.throttles = []
        self.angles = []

    def drive(self, speed_percent) -> None:
        self.throttles.append(speed_percent)

    def halt(self) -> None:
        if self.broken:
            raise OSError("i2c bus is gone")
        self.throttles.append(0)

    def steer(self, angle) -> None:
        self.angles.append(angle)


def test_is_driving_follows_the_command_not_the_wheels():
    driver = FakeDriver()
    controller = MotorController({}, driver=driver)
    assert controller.is_driving() is False
    controller.forward(60)
    assert controller.is_driving() is True
    controller.stop()
    assert controller.is_driving() is False
    assert driver.throttles == [60, 0]


def test_stop_never_raises_even_when_the_bus_is_gone():
    controller = MotorController({}, driver=FakeDriver(broken=True))
    controller.stop()  # 예외가 올라오면 안전 정지 경로가 죽는다
    assert controller.is_driving() is False


def test_a_missing_driver_is_a_fault_not_a_healthy_mock():
    """원본은 I2C 실패 시 조용히 Mock 으로 떨어진다 — 그러면 없는 하드웨어가 OK 로 보고된다."""
    controller = MotorController({"hardware_mode": "PCA9685"})
    assert controller.health() == "UNKNOWN"
    controller.start()  # 개발 PC 에는 busio·board 가 없다
    assert controller.health() == "FAULT"
    assert controller.is_driving() is False


def test_a_wired_driver_is_ok():
    controller = MotorController({}, driver=FakeDriver()).start()
    assert controller.health() == "OK"


def test_steering_is_clamped_to_the_measured_limits():
    driver = FakeDriver()
    controller = MotorController(
        {"steering_max_left": 40, "steering_max_right": 160, "steering_center": 100},
        driver=driver,
    )
    controller.steer(10)
    controller.steer(200)
    controller.center()
    assert driver.angles == [40, 160, 100]


def test_the_motor_singleton_is_quiet_before_it_starts():
    assert motor_module.health() == "UNKNOWN"
    assert motor_module.is_driving() is False
    motor_module.stop()  # 기동 전 정지 요청도 예외를 내지 않는다


# --------------------------------------------------------------------------
# detect/camera.py — 증거 이미지 규격 (계약 §8)
# --------------------------------------------------------------------------


class FakeFrame:
    def __init__(self, width: int, height: int) -> None:
        self.shape = (height, width, 3)


class FakeImaging:
    """cv2 대역. 품질을 낮출수록 작아지는 인코더를 흉내낸다."""

    IMWRITE_JPEG_QUALITY = 1

    def __init__(self) -> None:
        self.encoded = []

    def resize(self, frame, size):
        return FakeFrame(size[0], size[1])

    def imencode(self, extension, frame, params):
        quality = params[1]
        self.encoded.append(quality)
        return True, b"x" * (quality * 10_000)


def camera_with(tmp_path, **config) -> tuple:
    imaging = FakeImaging()
    settings = {"evidence_dir": str(tmp_path / "evidence")}
    settings.update(config)
    return Camera(settings, imaging=imaging), imaging


def test_evidence_is_resized_to_the_long_edge(tmp_path):
    camera, imaging = camera_with(tmp_path, evidence_long_edge_px=1280)
    path, meta = camera.capture_evidence(FakeFrame(1920, 1080))
    assert (meta["image_width"], meta["image_height"]) == (1280, 720)
    assert path.exists()


def test_a_small_frame_is_left_alone(tmp_path):
    camera, _imaging = camera_with(tmp_path, evidence_long_edge_px=1280)
    _path, meta = camera.capture_evidence(FakeFrame(640, 480))
    assert (meta["image_width"], meta["image_height"]) == (640, 480)


def test_quality_drops_until_the_evidence_fits_the_contract_limit(tmp_path):
    camera, imaging = camera_with(
        tmp_path, evidence_jpeg_quality=80, evidence_max_bytes=512_000
    )
    path, _meta = camera.capture_evidence(FakeFrame(640, 480))
    assert imaging.encoded == [80, 70, 60, 50]  # 500KB 아래로 내려갈 때까지
    assert path.stat().st_size <= 512_000


def test_an_image_that_never_fits_is_dropped_but_the_meta_survives(tmp_path):
    """이미지가 없어도 결함 event 자체는 나가야 한다 (§8.3)."""
    camera, _imaging = camera_with(tmp_path, evidence_max_bytes=1)
    path, meta = camera.capture_evidence(FakeFrame(640, 480))
    assert path is None
    assert meta["captured_at"].endswith("Z")


def test_evidence_meta_carries_a_contract_timestamp(tmp_path):
    camera, _imaging = camera_with(tmp_path)
    _path, meta = camera.capture_evidence(FakeFrame(640, 480))
    assert set(meta) == {"captured_at", "image_width", "image_height"}
    # comms.codec.utc_now() 포맷 — 직접 만들지 않는다.
    assert meta["captured_at"][-1] == "Z" and meta["captured_at"][10] == "T"


def test_camera_health_reports_the_device(tmp_path, clock):
    def broken(index, width, height):
        raise OSError("no camera")

    camera = Camera({}, capture_factory=broken, monotonic=clock.monotonic)
    assert camera.health() == "UNKNOWN"
    camera.start()
    try:
        assert camera.health() == "FAULT"
        assert camera.read() is None
    finally:
        camera.stop()


def test_a_missing_encoder_still_reports_the_frame_size(tmp_path):
    """인코딩은 못 해도 원본 크기는 안다 — 아는 값을 0 으로 보고하지 않는다 (§3.1)."""
    camera = Camera({"evidence_dir": str(tmp_path)}, imaging=None)
    camera._load_imaging = lambda: None  # cv2 가 없는 개발 PC
    path, meta = camera.capture_evidence(FakeFrame(1280, 720))
    assert path is None
    assert (meta["image_width"], meta["image_height"]) == (1280, 720)


def test_the_camera_singleton_is_quiet_before_it_starts():
    assert camera_module.health() == "UNKNOWN"
    assert camera_module.read() is None
    path, meta = camera_module.capture_evidence(None)
    assert path is None and meta["image_width"] == 0


# --------------------------------------------------------------------------
# detect/inference.py — 매핑·정규화·중복 제거 (계약 §8.3·§11.3)
# --------------------------------------------------------------------------


class FakeBox:
    def __init__(self, class_id, confidence, xyxy) -> None:
        self.cls = [class_id]
        self.conf = [confidence]
        self.xyxy = [xyxy]


class FakeResult:
    def __init__(self, boxes) -> None:
        self.boxes = boxes


class FakeModel:
    def __init__(self, boxes, names=None) -> None:
        self.boxes = boxes
        self.names = names or {0: "linear", 1: "linear_damaged", 2: "mystery"}

    def __call__(self, frame, conf=None, verbose=False):
        return [FakeResult(self.boxes)]


def detector_with(clock, boxes, **config) -> Detector:
    settings = {"confirm_frames": 1, "cooldown_sec": 0.0, "confidence_threshold": 0.35}
    settings.update(config)
    return Detector(settings, model=FakeModel(boxes), monotonic=clock.monotonic)


FRAME = FakeFrame(640, 480)


def test_a_damaged_class_becomes_an_incident(clock):
    detector = detector_with(clock, [FakeBox(1, 0.87, [64.0, 96.0, 320.0, 240.0])])
    detection = detector.infer(FRAME)
    assert detection["normal"] is False
    assert detection["block_type"] == "LINEAR"
    assert detection["defect_type"] == "DAMAGED"
    assert detection["confidence"] == pytest.approx(0.87)
    assert detection["bbox_norm"] == [0.1, 0.2, 0.5, 0.5]


def test_a_normal_class_is_counted_not_published(clock):
    detector = detector_with(clock, [FakeBox(0, 0.9, [0.0, 0.0, 10.0, 10.0])])
    detection = detector.infer(FRAME)
    assert detection["normal"] is True
    assert detection["defect_type"] is None


def test_an_unmapped_class_never_claims_a_defect(clock):
    """모르는 클래스에 결함 딱지를 붙이면 없는 사건을 만든다."""
    detector = detector_with(clock, [FakeBox(2, 0.9, [0.0, 0.0, 10.0, 10.0])])
    detection = detector.infer(FRAME)
    assert detection["block_type"] == "UNKNOWN"
    assert detection["normal"] is True


def test_the_class_map_can_be_replaced_by_config(clock):
    detector = Detector(
        {"confirm_frames": 1, "cooldown_sec": 0.0},
        model=FakeModel([FakeBox(2, 0.9, [0.0, 0.0, 10.0, 10.0])]),
        class_map={"mystery": {"block_type": "DOT", "defect_type": "DAMAGED"}},
        monotonic=clock.monotonic,
    )
    detection = detector.infer(FRAME)
    assert (detection["block_type"], detection["defect_type"]) == ("DOT", "DAMAGED")


def test_the_bounding_box_is_normalized_and_ordered(clock):
    # 뒤집힌 좌표·프레임 밖 좌표가 와도 0.0~1.0, min <= max 여야 한다 (§8.3).
    detector = detector_with(clock, [FakeBox(1, 0.9, [700.0, 500.0, -20.0, -10.0])])
    bbox = detector.infer(FRAME)["bbox_norm"]
    assert bbox == [0.0, 0.0, 1.0, 1.0]
    assert all(0.0 <= value <= 1.0 for value in bbox)


def test_the_most_confident_box_wins(clock):
    detector = detector_with(
        clock,
        [
            FakeBox(0, 0.40, [0.0, 0.0, 10.0, 10.0]),
            FakeBox(1, 0.95, [0.0, 0.0, 10.0, 10.0]),
        ],
    )
    assert detector.infer(FRAME)["defect_type"] == "DAMAGED"


def test_a_low_confidence_box_is_not_an_observation(clock):
    detector = detector_with(clock, [FakeBox(1, 0.10, [0.0, 0.0, 10.0, 10.0])])
    assert detector.infer(FRAME) is None


def test_a_single_frame_is_not_a_confirmation(clock):
    """계약 §8.1 — tracking 을 통과한 관측만 확정이다. 프레임마다 발행하지 않는다."""
    detector = detector_with(clock, [FakeBox(1, 0.9, [0.0, 0.0, 10.0, 10.0])], confirm_frames=3)
    assert detector.infer(FRAME) is None
    assert detector.infer(FRAME) is None
    assert detector.infer(FRAME) is not None


def test_the_cooldown_stops_the_same_block_from_repeating(clock):
    detector = detector_with(
        clock, [FakeBox(1, 0.9, [0.0, 0.0, 10.0, 10.0])], confirm_frames=1, cooldown_sec=30.0
    )
    assert detector.infer(FRAME) is not None
    for _ in range(5):
        assert detector.infer(FRAME) is None
    clock.advance(30.0)
    assert detector.infer(FRAME) is not None


def test_an_empty_frame_resets_the_streak(clock):
    detector = detector_with(clock, [FakeBox(1, 0.9, [0.0, 0.0, 10.0, 10.0])], confirm_frames=2)
    detector.infer(FRAME)
    detector._model.boxes = []
    assert detector.infer(FRAME) is None  # 놓쳤다 — 연속이 끊긴다
    detector._model.boxes = [FakeBox(1, 0.9, [0.0, 0.0, 10.0, 10.0])]
    assert detector.infer(FRAME) is None  # 처음부터 다시 센다
    assert detector.infer(FRAME) is not None


def test_a_broken_model_does_not_raise(clock):
    class Exploding:
        names = {}

        def __call__(self, *args, **kwargs):
            raise RuntimeError("cuda is on fire")

    detector = Detector({}, model=Exploding(), monotonic=clock.monotonic)
    assert detector.infer(FRAME) is None


def test_the_detector_singleton_is_quiet_before_it_starts():
    assert inference_module.infer(FRAME) is None
    assert inference_module.MODEL_NAME and inference_module.MODEL_VERSION


def test_the_default_class_map_only_uses_contract_enums():
    from comms.contract import BLOCK_TYPES, DEFECT_TYPES

    for mapped in DEFAULT_CLASS_MAP.values():
        assert mapped["block_type"] in BLOCK_TYPES
        assert mapped["defect_type"] is None or mapped["defect_type"] in DEFECT_TYPES


# --------------------------------------------------------------------------
# config.yaml 단일 출처
# --------------------------------------------------------------------------


def test_the_detect_interval_comes_from_config_not_from_a_constant():
    previous = dict(inference_module._config)
    try:
        inference_module.configure({"interval_sec": 0.75})
        assert inference_module.interval_sec() == 0.75
        inference_module.configure({})
        assert inference_module.interval_sec() == inference_module.DEFAULTS["interval_sec"]
        inference_module.configure({"interval_sec": "빠르게"})
        assert inference_module.interval_sec() == inference_module.DEFAULTS["interval_sec"]
    finally:
        inference_module.configure(previous)


def test_the_shipped_config_carries_the_measured_calibration(monkeypatch):
    from comms.config import load_document

    import main

    for name in ("MQTT_HOST", "MQTT_PASSWORD", "UPLOAD_URL", "UPLOAD_API_KEY"):
        monkeypatch.setenv(name, "x")
    monkeypatch.setenv("MQTT_ID", "RBT-001")
    monkeypatch.setenv("ROBOT_UUID", "6f1b6b1e-9c2a-4c1e-9f3a-2b6d5a4c7e10")
    document = load_document(main.CONFIG_PATH)

    # driving_test 에서 실측으로 맞춘 값이라 임의로 바뀌면 안 된다.
    assert document["safety"]["lidar_angle_offset_deg"] == 200.0
    assert document["safety"]["stop_distance_m"] == 0.40
    assert document["drive"]["steering_center"] == 100
    assert document["drive"]["pca9685_address"] == 0x40
    assert document["drive"]["servo_address"] == 0x60
    # 클래스 매핑은 계약 enum 만 쓴다.
    from comms.contract import BLOCK_TYPES, DEFECT_TYPES

    for mapped in document["detect"]["classes"].values():
        assert mapped["block_type"] in BLOCK_TYPES
        assert mapped["defect_type"] is None or mapped["defect_type"] in DEFECT_TYPES


def test_detections_are_dispatched_to_the_contract_paths(tmp_path):
    """main 은 배분만 한다 — 결함은 event 로, 정상은 미션 집계로."""
    import main
    from events import EventPublisher
    from robot_state import RobotState
    from test_events import RecordingClient

    client, state = RecordingClient(), RobotState()
    events = EventPublisher(client, state, model_name="m", model_version="1")
    state.start_mission("MIS-20260726-001", zone_id="A-01")
    detections = queue.Queue()
    detections.put(
        {
            "normal": False,
            "block_type": "LINEAR",
            "defect_type": "DAMAGED",
            "confidence": 0.9,
            "bbox_norm": [0.1, 0.2, 0.3, 0.4],
            "image_path": None,
            "captured_at": "2026-07-26T05:15:34.120Z",
            "image_width": 1280,
            "image_height": 720,
        }
    )
    detections.put({"normal": True, "block_type": "LINEAR", "defect_type": None})

    main.drain_detections(events, state, detections)

    incident = client.incidents()[-1]
    assert incident["incident"]["block_type"] == "LINEAR"
    assert incident["evidence"]["captured_at"] == "2026-07-26T05:15:34.120Z"
    assert incident["evidence"]["image_id"] is None
    assert state.mission_incident_count == 1
    assert state.mission_normal_detection_count == 1
    assert detections.empty()


def test_a_normal_detection_without_a_mission_is_not_an_error():
    import main
    from events import EventPublisher
    from robot_state import RobotState
    from test_events import RecordingClient

    client, state = RecordingClient(), RobotState()
    detections = queue.Queue()
    detections.put({"normal": True, "block_type": "DOT", "defect_type": None})
    main.drain_detections(EventPublisher(client, state), state, detections)
    assert client.published == []


def test_configure_reaches_every_hardware_module(monkeypatch):
    import main

    main.configure_hardware(
        {
            "safety": {"stop_distance_m": 0.55},
            "drive": {"steering_center": 90},
            "detect": {"confidence_threshold": 0.5, "model_version": "1.2.3"},
        }
    )
    try:
        assert safety_module._config["stop_distance_m"] == 0.55
        assert motor_module._config["steering_center"] == 90
        assert camera_module._config["confidence_threshold"] == 0.5
        assert inference_module.MODEL_VERSION == "1.2.3"
        # 설정만 받았을 뿐 장치를 열지 않았다.
        assert safety_module.health() == "UNKNOWN"
        assert camera_module.health() == "UNKNOWN"
    finally:
        inference_module.MODEL_VERSION = "0.0.0-stub"
