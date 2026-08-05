"""tests 공통 픽스처 — config.yaml 로딩과 파라미터 묶음 조립을 한 곳에 둔다.

숫자(대기 시간·임계값)를 테스트에 하드코딩하지 않는 것이 이 프로젝트 방침이라
모든 테스트 파일이 같은 설정을 읽는다. 파일마다 따로 조립하면 '어느 절을 어떻게
읽는가'가 여러 벌로 갈라지고, 실제로 갈라진 뒤 config 구조를 바꿀 때 네 곳을
고쳐야 했다 — 그래서 조립도 여기 하나만 둔다. 전부 세션 스코프 읽기 전용이다.
"""

import pytest

from driving.state_machine import StateMachineParams
from main import load_config
from perception.segmentation import (ClassNames, CostmapParams, FreeSpaceParams,
                                     PostParams)


@pytest.fixture(scope="session")
def config() -> dict:
    return load_config()


@pytest.fixture(scope="session")
def seg(config) -> dict:
    return config["perception"]["segmentation"]


@pytest.fixture(scope="session")
def classes(seg) -> ClassNames:
    """7클래스 우선순위 표 → 클래스 역할 묶음. 주행가능·차도·비용의 단일 출처."""
    return ClassNames.from_config(seg)


@pytest.fixture(scope="session")
def post(seg) -> PostParams:
    return PostParams.from_config(seg["post"])


@pytest.fixture(scope="session")
def costmap(seg, classes) -> CostmapParams:
    return CostmapParams.from_config(seg["costmap"], classes)


@pytest.fixture(scope="session")
def fs(seg) -> FreeSpaceParams:
    return FreeSpaceParams.from_config(seg["free_space"])


@pytest.fixture(scope="session")
def params(config) -> StateMachineParams:
    return StateMachineParams.from_config(config["driving"])
