"""주행 상태머신 — 순수 step() + 공통 가드 + on_event 훅.

순수성 계약 (스레드 확장의 전제):
  - step()은 입력(컨텍스트, 세그멘테이션 프레임, IMU 측정, 현재 시각)만으로
    출력(다음 컨텍스트, 주행 명령)을 계산한다.
  - 이 모듈 안에서 I/O·로깅·time.sleep·시계 접근을 절대 하지 않는다.
    모든 대기(재확인 3초 등)는 '현재 시각 - 기준 시각' 비교로만 구현한다.
  - 상태 전이를 외부에 알리는 유일한 출구는 StateMachine의 on_event 훅이다.
    (로깅은 main.py가, 이후 통신(MQTT)은 3단계에서 이 훅을 구독한다)


패키지 구성 (한 파일이 2200줄이 되어 넷으로 나눴다 — import 경로는 그대로다):
  data      파라미터·외부 요청·컨텍스트와 전이 헬퍼 (자료)
  steering  조향·방향 선택 (순수 기하)
  handlers  상태별 핸들러와 하위 국면 (판단)
  machine   가드 체인 → 디스패치 → 슬루율 제한 + 보관 래퍼 (조립)
"""

# 이 파일의 import 는 전부 **재수출**이다 — 호출부·테스트가 종전처럼
# `from driving.state_machine import X` 로 쓰게 하려는 것이고, 그래서 패키지로
# 나눈 사실이 바깥에 새지 않는다. 밑줄 이름 둘은 테스트가 직접 쓰는 내부 함수다.
from driving.state_machine.data import (ExternalRequests, GuardResult,
                                        StateMachineContext, StateMachineParams,
                                        initial_context)
from driving.state_machine.handlers import _attempt_direction
from driving.state_machine.steering import _bypass_direction
from driving.state_machine.machine import OnEvent, StateMachine, step

__all__ = ["ExternalRequests", "GuardResult", "OnEvent", "StateMachine",
           "StateMachineContext", "StateMachineParams", "initial_context", "step"]
