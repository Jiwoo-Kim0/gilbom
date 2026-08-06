"""출발 인터록 — 원격 출발 신호 하나만으로는 바퀴가 돌지 않게 한다.

웹 '시작' 버튼도 서버의 START_MISSION 도 **로봇 옆에 사람이 있다는 증거**가 아니다.
누른 사람은 화면 앞에 있고, 로봇은 인도 어딘가에 있다. 그 사이에 사람이 지나가는지,
바퀴 앞에 뭐가 있는지, 애초에 로봇이 출발해도 되는 자리인지는 화면이 알려 주지 않는다.
그래서 출발에 두 번째 신호를 요구한다: **로봇 몸체의 터치 센서**. 이건 원격으로
누를 수 없으므로, 눌렸다는 사실 자체가 '사람이 로봇 앞에 서 있다' 는 증거다.

규칙은 하나다 — `machine.request_start()` 로 가는 **모든** 경로가 여기를 지난다.
IDLE 에서 나오는 유일한 트리거가 request_start 이므로(driving/states.py 전이표),
그 한 곳만 막으면 '멈춰 있던 로봇이 달리기 시작하는' 모든 경우가 덮인다:

    웹 '시작' · 서버 START_MISSION · 서버 RESUME(IDLE 폴백) · Xbox A · 자동 출발
        → arm()  (신호 접수. 아직 안 달린다)
    터치 센서 누름 엣지
        → touched()  (확인. 이것도 arm 을 겸한다 — 아래 참조)
    둘 다 유효창 안에 있으면 → poll() 이 True 한 번, 그때 request_start() 가 나간다

**터치는 확인이자 그 자체로 출발 신호다.** 명세의 '터치 시 주행 탐지 시작' 을 그대로
두기 위해서다 — 로봇 앞에 선 사람이 터치로 출발시키는 것은 종전과 같고, 이 인터록이
새로 막는 것은 '멀리서 온 신호만으로 출발하는 것' 하나다.

**일시정지·안전정지에서의 재개는 지나지 않는다** (request_resume·release_safe_stop).
그때 로봇은 이미 사람에게서 멀리 있고, 장애물이 치워졌거나 관제가 재개를 누른 것을
사람이 다시 만지러 가야 한다면 순찰이 성립하지 않는다. 터치가 필요한 것은 '서 있던
로봇이 처음 달리기 시작하는' 순간뿐이다.

두 신호에 각각 유효창을 두는 이유는 순서를 강요하지 않기 위해서다. 현장에서는
'터치하고 화면으로 뛰어가기' 도, '시작 누르고 로봇으로 걸어가기' 도 일어난다.
다만 터치 쪽 창이 훨씬 짧다 — 터치는 '지금 여기 사람이 있다' 는 증거이고, 그 증거는
빨리 상한다. 창을 넘기면 신호는 버려지고 처음부터 다시 한다.
"""

from __future__ import annotations

import logging
import time

log = logging.getLogger(__name__)


class StartInterlock:
    """출발 신호 + 터치 확인의 논리곱. 순수 로직이라 장치를 모른다.

    시계는 monotonic 하나뿐이고 주입할 수 있다 — 부르는 쪽(메인 루프·명령 처리)이
    시각을 넘겨 주지 않아도 되게 해서, 호출부가 인터록의 시간 규칙을 몰라도 되게 한다.
    """

    def __init__(self, *, enabled: bool, arm_timeout_sec: float,
                 touch_valid_sec: float, monotonic=time.monotonic) -> None:
        self._enabled = bool(enabled)
        self._arm_timeout = float(arm_timeout_sec)
        self._touch_valid = float(touch_valid_sec)
        self._monotonic = monotonic
        self._armed_at = None
        self._armed_source = None
        self._touched_at = None

    @classmethod
    def from_config(cls, section: dict, **kwargs) -> "StartInterlock":
        """config.yaml 의 hardware.touch 절 → 인터록.

        `pin` 이 없으면 애초에 확인할 장치가 없으므로 꺼진 채로 만든다 —
        요구를 켜 두고 통과시키는 것보다 '이 구성에는 인터록이 없다' 가 정확하다.
        """
        section = section or {}
        enabled = bool(section.get("required_for_start", False))
        if section.get("pin") is None:
            enabled = False
        return cls(
            enabled=enabled,
            arm_timeout_sec=float(section.get("arm_timeout_sec", 0.0)),
            touch_valid_sec=float(section.get("touch_valid_sec", 0.0)),
            **kwargs,
        )

    @classmethod
    def disabled(cls, **kwargs) -> "StartInterlock":
        """인터록 없는 구성 — arm() 이 언제나 즉시 True 다 (종전 동작)."""
        return cls(enabled=False, arm_timeout_sec=0.0, touch_valid_sec=0.0, **kwargs)

    @property
    def required(self) -> bool:
        return self._enabled

    def disable(self, reason: str) -> None:
        """센서가 없거나 죽었다 — 인터록을 내린다 (통과 + 경고).

        막는 쪽으로 넘어지지 않는 이유: 확인할 장치가 없다고 로봇을 세워 두면
        터치 센서 단선 하나가 곧 '출발 불가' 다. 대신 그 사실을 로그로 남긴다.
        """
        if self._enabled:
            log.warning("출발 인터록 해제 — %s. 터치 확인 없이 출발한다", reason)
        self._enabled = False

    # -- 신호 --------------------------------------------------------------

    def arm(self, source: str = "") -> bool:
        """출발 신호 접수. **지금 출발시켜도 되면 True** (인터록이 없거나 터치가 이미 있다).

        False 면 부르는 쪽은 아무것도 하지 않는다 — 터치가 오는 순간 poll() 이 낸다.
        """
        if not self._enabled:
            return True
        # 같은 신호를 매 tick 다시 받는 경로(자동 출발)가 있어 첫 접수만 남긴다.
        first = self._armed_at is None or self._armed_source != source
        self._armed_at = self._monotonic()
        self._armed_source = source
        if self._consume():
            return True
        if first:
            log.info("출발 신호 접수(%s) — 터치 확인 대기", source or "미상")
        return False

    def touched(self) -> None:
        """터치 누름 엣지. 확인 신호이자 그 자체로 출발 신호다 (모듈 머리말)."""
        if not self._enabled:
            return
        self._touched_at = self._monotonic()

    def poll(self) -> bool:
        """메인 루프가 매 tick 부른다. 두 신호가 모두 유효하면 True 한 번."""
        if not self._enabled:
            return False
        return self._consume()

    def disarm(self) -> None:
        """접수해 둔 출발 신호를 버린다 — 명령이 만료·거절됐거나 정지가 걸렸을 때.

        안 버리면 만료된 명령이 남긴 무장이 살아 있다가, 한참 뒤의 터치 한 번에
        아무도 시키지 않은 출발이 된다.
        """
        self._armed_at = None
        self._armed_source = None

    # -- 표시 --------------------------------------------------------------

    def status(self) -> dict:
        """대시보드·로그용. 읽기만 한다 — 여기서 신호를 소비하지 않는다."""
        if not self._enabled:
            return {"required": False, "waiting": False}
        now = self._monotonic()
        armed = self._fresh(self._armed_at, self._arm_timeout, now)
        return {
            "required": True,
            # 출발 신호는 받아 뒀는데 터치가 없어서 못 가는 상태
            "waiting": armed and not self._fresh(self._touched_at, self._touch_valid, now),
            "source": self._armed_source if armed else None,
        }

    # -- 내부 --------------------------------------------------------------

    def _fresh(self, stamp, window: float, now: float) -> bool:
        return stamp is not None and now - stamp <= window

    def _consume(self) -> bool:
        """두 신호가 살아 있으면 둘 다 쓰고 True. 상한 신호는 그 자리에서 버린다."""
        now = self._monotonic()
        if not self._fresh(self._armed_at, self._arm_timeout, now):
            self.disarm()
            return False
        if not self._fresh(self._touched_at, self._touch_valid, now):
            self._touched_at = None
            return False
        log.info("출발 확인 — %s + 터치", self._armed_source or "출발 신호")
        self.disarm()
        self._touched_at = None
        return True
