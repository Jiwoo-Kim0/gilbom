"""telemetry 표본 수집 + 5초 주기 발행.  [F-007]

telemetry 에 실리는 값의 판정은 전부 여기서 한다 — main.py 는 조립만 한다.
payload 조립은 하지 않는다. 값만 모아 factory.telemetry() 에 넘긴다.
버퍼도 재전송 큐도 없다: 계약 §6.1·§16-4 는 최신 표본 1개만 발행하고 재연결 후
과거 표본을 보내지 않기를 요구한다. 끊긴 동안의 표본은 그냥 버린다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §6
"""

from __future__ import annotations

import logging
import time
from pathlib import Path

from comms import TopicKind
from comms.codec import utc_now

log = logging.getLogger(__name__)

#: 발행 주기 (계약 §6.1). 메인 루프 tick(0.5초)이 이 기한을 넘었는지만 본다.
TELEMETRY_INTERVAL_SEC = 5.0

#: mode 가 이 셋 중 하나면 주행 명령이 남아 있어도 moving 은 false 다 (§6.4).
NOT_MOVING_MODES = frozenset({"PAUSED", "SAFE_STOP", "ERROR"})

#: 연속 실패가 이만큼 쌓이면 한 번 튄 게 아니라 구조적으로 깨진 것으로 본다.
FAILURE_ALERT_THRESHOLD = 3

#: Jetson thermal zone. 개발 PC 에는 없는 경로다 — 없으면 온도가 null 이 된다.
THERMAL_ROOT = Path("/sys/class/thermal")


def jetson_temp_c(root=THERMAL_ROOT):
    """읽을 수 있는 thermal zone 중 최고값 (계약 §6.4). 못 읽으면 None.

    zone 종류를 가리지 않는다 — junction(tj-thermal)도 zone 목록에 있으므로 최고값
    계산에 이미 들어간다. 특정 zone 을 골라 돌려주면 더 뜨거운 zone 을 가려서
    과소보고만 만든다.
    /sys 값은 밀리도라 1000 으로 나눈다. 경로가 없는 개발 PC 에서도 예외를 던지지
    않는다 — 온도 하나 때문에 표본 전체를 잃을 이유가 없다.
    """
    highest = None
    try:
        zones = sorted(Path(root).glob("thermal_zone*/temp"))
    except OSError:
        return None
    for zone in zones:
        try:
            milli = int(zone.read_text().strip())
        except (OSError, ValueError, TypeError):
            # TypeError: tegra 보드 일부 zone 은 EAGAIN 을 반환하는데, 텍스트 IO 가
            # raw read 의 None 을 디코드하려다 OSError 가 아닌 TypeError 로 튄다.
            continue  # 이 zone 만 건너뛴다. 나머지는 계속 읽는다.
        celsius = milli / 1000.0
        if highest is None or celsius > highest:
            highest = celsius
    return highest


class TelemetrySampler:
    """5초마다 최신 표본 1개를 만들어 발행한다 (계약 §6.1).

    state 와 달리 '전이 즉시' 가 없다 — 조건 없이 주기 하나뿐이다.
    battery 는 센서에서 직접 읽지 않고 RobotState 를 거친다: state 와 telemetry 가
    각자 읽으면 같은 시점에 서로 다른 잔량을 보고하게 된다.
    """

    def __init__(
        self,
        client,
        state,
        *,
        gps=None,
        battery=None,
        motor=None,
        interval_sec: float = TELEMETRY_INTERVAL_SEC,
        monotonic=time.monotonic,
        now=utc_now,
        thermal_root=THERMAL_ROOT,
    ) -> None:
        self._client = client
        self._state = state
        self._gps = gps
        self._battery = battery
        self._motor = motor
        self._interval = interval_sec
        self._monotonic = monotonic
        self._now = now
        self._thermal_root = thermal_root
        self._next_due = None  # None 이면 아직 한 번도 안 보냈다.
        self._failures = 0  # 연속 실패 횟수. 성공하면 0 으로 돌아간다.

    # -- 발행 타이밍 (계약 §6.1) -------------------------------------------

    def poll(self) -> bool:
        """메인 루프가 매 tick 부른다. 기한이 됐을 때만 발행한다."""
        now = self._monotonic()
        if self._next_due is not None and now < self._next_due:
            return False
        # 발행이 실패해도 기한은 민다. 실패한 표본을 다음 tick 에 다시 시도하면
        # 그건 계약이 금지한 재전송이 된다.
        self._next_due = now + self._interval
        try:
            published = self.publish()
        except Exception:
            # telemetry 는 유실 허용이다 (QoS 0). 표본 하나 때문에 루프를 세우지 않는다.
            self._failures += 1
            if self._failures == FAILURE_ALERT_THRESHOLD:
                # GPS 가 한 번 튄 게 아니라 계속 깨져 있다. 넘는 순간 한 번만 올린다 —
                # 5초마다 찍히는 로그는 헤드리스 로봇에서 아무도 보지 않는다.
                log.error(
                    "telemetry failed %d times in a row", self._failures, exc_info=True
                )
            else:
                log.warning("telemetry sample dropped", exc_info=True)
            return False
        if self._failures:
            log.info("telemetry recovered after %d failed sample(s)", self._failures)
            self._failures = 0
        return published

    def publish(self) -> bool:
        """지금 표본을 만들어 발행한다. Outbox 를 거치지 않는다 (QoS 0·retain false)."""
        payload = self._client.messages.telemetry(**self.sample())
        return self._client.publish(TopicKind.TELEMETRY, payload)

    # -- 값 생성 경계 (계약 §6.4) ------------------------------------------

    def sample(self) -> dict:
        """factory.telemetry() 에 넘길 값 묶음. payload 를 만들지는 않는다.

        읽기 전용이 아니다: 배터리 센서를 읽어 RobotState 를 갱신한다.
        그래서 배터리는 telemetry 주기(5초)에만 새로 읽힌다 — TELEMETRY_INTERVAL_SEC
        을 바꾸면 배터리 갱신 주기도 같이 바뀌고, R-4 의 배터리 임계 event 가
        그 주기에 묶인다. 읽기를 떼어낼지는 R-4 에서 정한다.
        """
        self._refresh_battery()
        snapshot = self._state.snapshot()
        valid, lat, lon, heading_deg = self._position()
        return dict(
            position_valid=valid,
            lat=lat,
            lon=lon,
            heading_deg=heading_deg,
            moving=self._moving(snapshot.mode),
            battery_valid=snapshot.battery_valid,
            battery_percent=snapshot.battery_percent,
            battery_state=snapshot.battery_state,
            battery_source=snapshot.battery_source,
            jetson_temp_c=jetson_temp_c(self._thermal_root),
            # 보관함은 start_mission()·end_mission() 이 통째로 움직인다 — 셋이
            # 반쯤 채워져 factory 가 표본을 거부하는 상태가 생기지 않는다.
            mission_id=snapshot.mission_id,
            mission_state=snapshot.mission_state,
            mission_elapsed_sec=snapshot.mission_elapsed_sec,
            mission_incident_count=snapshot.mission_incident_count,
            sampled_at=self._now(),
        )

    def _refresh_battery(self) -> None:
        """센서 읽기는 RobotState 로만 들어간다 — state·telemetry 의 단일 출처."""
        if self._battery is None:
            return
        self._state.set_battery(**self._battery())

    def _position(self) -> tuple:
        """(valid, lat, lon, heading_deg). Fix 가 없으면 넷 다 null 이다 (§16-7).

        직전 좌표를 복사하지 않는다 — GPS 가 잠깐 끊긴 것과 그 자리에 서 있는 것은
        지도에서 완전히 다른 뜻이다.
        """
        reading = (self._gps() if self._gps is not None else None) or {}
        if not reading.get("valid"):
            return False, None, None, None
        lat, lon = reading.get("lat"), reading.get("lon")
        if lat is None or lon is None:
            # valid 를 자칭해도 좌표가 없으면 Fix 가 아니다.
            return False, None, None, None
        # heading 은 그대로 넘긴다. 범위 검사는 factory 가 한다 (§16-8).
        return True, lat, lon, reading.get("heading_deg")

    def _moving(self, mode: str) -> bool:
        """주행 PWM 명령 중이고 PAUSED·SAFE_STOP·ERROR 가 아닌지 (§6.4)."""
        if self._motor is None or not self._motor():
            return False
        return mode not in NOT_MOVING_MODES
