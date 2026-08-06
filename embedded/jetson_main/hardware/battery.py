"""배터리 잔량 — 전압·전류 센서가 없는 동안 **켜져 있던 시간으로 역산**한다.

이 로봇에는 잔량을 읽을 경로가 없다. 팩(DXF 4셀)에서 나오는 선은 전원선뿐이고
모터 HAT·젯슨 어느 쪽도 전압을 되돌려 주지 않는다. 그래서 계약 §9(배터리 등급·조치)를
채우는 값이 하나도 없었고, state·telemetry 의 battery 는 계속 valid=false/UNKNOWN 으로
나갔다 — 관제는 잔량을 영영 모르고, LOW·CRITICAL 로 순찰을 멈추는 안전 경로도 통째로
죽어 있었다.

여기서 그 자리를 시간으로 메운다. 근거는 실측 하나다:

    2026-08-05 — DXF 4셀 팩, **3시간 실사용 후 충전기 표시 55% 소모**
    → 55 / 3 = 시간당 18.33%, 만충에서 완전 방전까지 약 5.5시간

그래서 이 모듈이 내는 값은 **측정이 아니라 추정**이고, 그 사실을 숨기지 않는다:
  · telemetry 의 battery.source 는 ESTIMATED 다 (계약 §6.3).
  · health.battery 는 OK 가 아니라 DEGRADED 다 — 잔량을 읽는 장치가 없다는 뜻이고,
    측정 경로가 붙기 전까지는 이 값이 계속 노란불이어야 한다.

정확도의 한계도 그대로 적어 둔다. 실측은 '주행을 포함한 3시간'이라 정차·대기 시간의
소모까지 같은 한 개의 비율에 뭉쳐 있다. 세워 둔 채 켜 놓으면 실제보다 빨리 닳는 것으로
보이는데, 그 방향의 오차는 안전한 쪽(일찍 멈춘다)이라 그대로 둔다.

**기동 시점의 잔량은 만충으로 본다** (`resume_on_restart: false` — 기본값).
운용이 그렇게 정해져 있기 때문이다: 매 실행 전에 완충하고 나가고, 쉬는 동안에도
계속 충전한다. 이 운용에서 '기동 = 만충' 은 추정이 아니라 사실에 가깝고, 반대로
지난 잔량을 이어받으면 충전을 하고도 낮은 값이 따라와 순찰이 막힌다.

그래서 **충전을 알리는 버튼 같은 것은 두지 않는다** — 충전한 뒤 다시 켜면 그것이
곧 선언이다. 값은 계약이 정한 자리(telemetry·state·events/battery)로만 나간다.

전제가 깨지는 경우는 하나 남는다: **켜 둔 채로 충전**했거나 완충 없이 프로세스가
재시작된 경우. 켜 둔 채 충전한 쪽은 로봇이 알 방법이 없어(충전기를 꽂아도 신호가
없다) 추정이 계속 내려간다 — 다시 켜면 맞는다. 재시작 쪽까지 막아야 하면 config 의
`resume_on_restart: true` 로 바꾼다. 그러면 기록(state_file)의 잔량을 이어받고,
충전은 사람이 선언해야 한다:
    python3 -m hardware.battery --full     (충전 뒤, 다음 기동 전에)
    python3 main.py --battery-full ...     (충전 직후 기동이라면 이쪽)

실제 전압·전류 센서(INA219 등)가 붙으면 이 모듈을 통째로 갈아 끼운다 —
TelemetrySampler(battery=...) 가 요구하는 것은 set_battery() 인자를 돌려주는
호출 가능 객체 하나뿐이고, 그때 source 는 MEASURED 가 된다.
원시 읽기 코드는 tools/data_recorder/battery.py 에 이미 있다(미배선).
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from pathlib import Path

log = logging.getLogger(__name__)

#: 계약 §6.3 의 battery.source. 이 모듈이 내는 값은 언제나 이쪽이다.
SOURCE_ESTIMATED = "ESTIMATED"
#: health.battery (계약 §5.3). 잔량을 읽는 장치가 없으므로 OK 를 자칭하지 않는다.
HEALTH_ESTIMATED = "DEGRADED"
SECONDS_PER_HOUR = 3600.0


class RuntimeBatteryEstimator:
    """가동 시간 → 잔량(%). TelemetrySampler(battery=...) 에 그대로 넘어간다.

    호출 1회가 표본 1개다 (telemetry 주기 = 5초). 값은 단조 감소한다 —
    충전을 감지할 수단이 없으므로 올라가는 유일한 경로는 mark_full() 이다.
    락을 두는 이유는 웹 대시보드가 같은 객체를 읽을 수 있어서다.
    """

    def __init__(
        self,
        *,
        drain_pct_per_hour: float,
        full_pct: float,
        persist_step_pct: float,
        state_path=None,
        resume_on_restart: bool = False,
        monotonic=time.monotonic,
    ) -> None:
        rate = float(drain_pct_per_hour)
        if rate <= 0.0:
            raise ValueError(f"battery.estimate.drain_pct_per_hour: {rate} must be > 0")
        full = float(full_pct)
        if not 0.0 < full <= 100.0:
            raise ValueError(f"battery.estimate.full_pct: {full} out of range (0, 100]")
        self._rate = rate
        self._full = full
        self._step = max(0.0, float(persist_step_pct))
        self._path = None if state_path is None else Path(state_path)
        self._monotonic = monotonic
        self._lock = threading.Lock()
        # 기본은 '기동 = 만충' 이다 (모듈 머리말). 기록을 이어받는 것은 완충 없이
        # 재시작하는 운용으로 바꿨을 때뿐이다.
        self._baseline_pct = self._restore() if resume_on_restart else full
        self._started_at = self._monotonic()
        self._persisted_pct = self._baseline_pct

    # -- 읽기 -------------------------------------------------------------

    def percent(self) -> float:
        """지금 잔량 추정치. 0 아래로는 내려가지 않는다."""
        with self._lock:
            elapsed_h = (self._monotonic() - self._started_at) / SECONDS_PER_HOUR
            return max(0.0, min(100.0, self._baseline_pct - self._rate * elapsed_h))

    def remaining_sec(self) -> int:
        """0% 까지 남은 시간(초). 계약 §6.3 의 estimated_runtime_sec 에 실린다."""
        return int(self.percent() / self._rate * SECONDS_PER_HOUR)

    def health(self) -> str:
        """health.battery — 추정인 동안은 DEGRADED 다 (모듈 docstring 참조)."""
        return HEALTH_ESTIMATED

    def __call__(self) -> dict:
        """RobotState.set_battery() 인자 + telemetry 전용 extra.

        extra 는 state·event 에 실리지 않는다 — TelemetrySampler 가 떼어 낸다.
        """
        percent = self.percent()
        self._maybe_persist(percent)
        return {
            "valid": True,
            "percent": percent,
            "source": SOURCE_ESTIMATED,
            "extra": {"estimated_runtime_sec": self.remaining_sec()},
        }

    # -- 충전 반영·기록 ---------------------------------------------------

    def mark_full(self) -> float:
        """기준점을 만충으로 되돌린다. 기동(--battery-full)과 CLI 만 부른다.

        돌고 있는 로봇에 '충전했다'를 알리는 창구는 두지 않는다 — 충전하고 다시
        켜는 것이 곧 선언이다 (모듈 머리말).
        """
        with self._lock:
            self._baseline_pct = self._full
            self._started_at = self._monotonic()
        self._write(self._full)
        log.info("배터리 추정 기준점을 만충(%.1f%%)으로 재설정했다", self._full)
        return self._full

    def close(self) -> None:
        """종료 직전 마지막 값을 남긴다 — 다음 기동이 여기서 이어받는다."""
        self._write(self.percent())

    def _maybe_persist(self, percent: float) -> None:
        """persist_step_pct 만큼 닳았을 때만 쓴다 — SD 카드에 5초마다 쓰지 않는다."""
        if self._path is None or abs(self._persisted_pct - percent) < self._step:
            return
        self._write(percent)

    def _write(self, percent: float) -> None:
        if self._path is None:
            return
        record = {"percent": round(float(percent), 2), "rate_pct_per_hour": self._rate}
        temp = self._path.with_suffix(self._path.suffix + f".{os.getpid()}.tmp")
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            temp.write_text(json.dumps(record), encoding="utf-8")
            # 원자적 교체 — 쓰는 도중 전원이 끊겨도 반쪽짜리 파일이 남지 않는다.
            os.replace(temp, self._path)
        except OSError:
            # 기록에 실패해도 추정은 계속한다. 대가는 재시작 때 이어받지 못하는 것뿐이다.
            log.warning("배터리 추정 기록 실패: %s", self._path, exc_info=True)
            return
        self._persisted_pct = float(percent)

    def _restore(self) -> float:
        """기록이 있으면 그 잔량으로 시작한다. 없거나 깨졌으면 만충으로 본다."""
        if self._path is None or not self._path.exists():
            return self._full
        try:
            stored = json.loads(self._path.read_text(encoding="utf-8"))
            percent = float(stored["percent"])
        except (OSError, ValueError, TypeError, KeyError):
            log.error("배터리 추정 기록을 읽지 못했다 (%s) — 만충으로 시작한다",
                      self._path, exc_info=True)
            return self._full
        if not 0.0 <= percent <= 100.0:
            log.error("배터리 추정 기록이 범위를 벗어났다 (%s: %r) — 만충으로 시작한다",
                      self._path, percent)
            return self._full
        log.info("배터리 추정 이어받기: %.1f%% (%s)", percent, self._path)
        return percent

    # -- 조립 -------------------------------------------------------------

    @classmethod
    def from_config(cls, section: dict, *, state_dir=None, **kwargs):
        """config.yaml 의 battery.estimate 절 → 추정기. 절이 없거나 꺼져 있으면 None.

        None 이면 배터리 경로 전체가 붙지 않는다 — 없는 값을 지어내는 대신
        종전대로 valid=false/UNKNOWN 이 나간다 (계약 §5.5).
        기본값을 코드에 두지 않는 것은 이 저장소 규칙이다: 튜닝 숫자는 config.yaml
        에만 있고, 켰는데 값이 없으면 조용히 흘리지 않고 여기서 죽는다.
        """
        section = section or {}
        if not section.get("enabled", False):
            return None
        state_file = section.get("state_file")
        state_path = None
        if state_file:
            state_path = Path(state_file)
            if not state_path.is_absolute() and state_dir is not None:
                state_path = Path(state_dir) / state_path
        missing = [name for name in ("drain_pct_per_hour", "full_pct", "persist_step_pct")
                   if section.get(name) is None]
        if missing:
            raise ValueError(f"battery.estimate: {', '.join(missing)} is required")
        return cls(
            drain_pct_per_hour=section["drain_pct_per_hour"],
            full_pct=section["full_pct"],
            persist_step_pct=section["persist_step_pct"],
            state_path=state_path,
            resume_on_restart=bool(section.get("resume_on_restart", False)),
            **kwargs,
        )


def build_from_config(document: dict, *, config_dir=None):
    """config.yaml 문서 → 추정기 하나 (battery.estimate 절). 꺼져 있으면 None.

    한 프로세스에 하나만 만든다 — 둘을 만들면 같은 시각에 서로 다른 잔량을 말한다.
    부르는 곳은 통신 런타임 조립(CommunicationRuntime.build)과 이 모듈의 CLI 뿐이다.
    """
    section = ((document or {}).get("battery") or {}).get("estimate")
    return RuntimeBatteryEstimator.from_config(section, state_dir=config_dir)


def _main() -> None:
    """`python3 -m hardware.battery` — 마지막으로 기록된 잔량 추정을 본다.

    기본 운용에서는 기동할 때마다 만충으로 시작하므로(resume_on_restart: false)
    이 명령의 --full 은 다음 기동에 아무 영향이 없다 — 그때는 그냥 다시 켜면 된다.
    **켜 둔 채 충전한 경우**의 자리는 대시보드의 '충전 완료' 버튼이다: 돌고 있는
    프로세스는 자기 추정값을 쓰고 이 파일을 읽지 않는다.
    resume_on_restart: true 로 바꿔 둔 구성에서만 --full 이 다음 기동에 반영된다.
    """
    import argparse

    import yaml

    parser = argparse.ArgumentParser(description=_main.__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--config", default=str(root / "config.yaml"),
                        help="battery 절이 있는 config.yaml 경로")
    parser.add_argument("--comms-config", default=str(root / "comms.yaml"),
                        help="상대 state_file 을 푸는 기준 (mqtt.outbox_path 와 같은 규칙)")
    parser.add_argument("--full", action="store_true",
                        help="충전 완료 — 잔량 추정을 만충으로 되돌린다 (없으면 현재 값만 본다)")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    with open(args.config, encoding="utf-8") as handle:
        document = yaml.safe_load(handle) or {}
    section = ((document.get("battery") or {}).get("estimate")) or {}
    state_dir = Path(args.comms_config).resolve().parent
    # 보여 주는 것은 **기록된** 값이다 — 그래서 여기서만 이어받기를 켠다.
    estimator = RuntimeBatteryEstimator.from_config(
        dict(section, resume_on_restart=True), state_dir=state_dir)
    if estimator is None:
        parser.error("config.yaml 의 battery.estimate 가 꺼져 있다 — 기록할 것이 없다")
    if args.full:
        estimator.mark_full()
        if not section.get("resume_on_restart", False):
            print("※ resume_on_restart 가 꺼져 있다 — 다음 기동은 어차피 만충으로 "
                  "시작한다 (이 명령은 기록만 맞춰 둔다)")
    print(f"기록된 배터리 ≈ {estimator.percent():.1f}%  "
          f"(0% 까지 {estimator.remaining_sec() / SECONDS_PER_HOUR:.1f}시간)")


if __name__ == "__main__":
    _main()
