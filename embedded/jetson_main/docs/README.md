# docs — 설계·실측 문서

저장소 진입점은 루트의 `README.md`다. 여기에는 **본문이 길어 루트에 두면 방해가 되는
문서**만 둔다. 코드와 함께 읽어야 하는 문서(`models/README.md`,
`tools/braille_detect/README.md`)는 대상 옆에 co-locate 한다.

| 문서 | 성격 | 신뢰도 |
|---|---|---|
| [`HARDWARE.md`](HARDWARE.md) | Phase 0 젯슨 하드웨어·코드 인벤토리 | **실측** — `config.yaml`의 하드웨어 수치가 여기서 나왔다 |
| [`ALGORITHMS.md`](ALGORITHMS.md) | 세그멘테이션 이후 얹을 알고리즘 조사 | **조사 문서 — 결정 아님.** 채택하려면 `README.md` 로드맵과 `config.yaml`에 반영할 것 |

## 문서를 어디에 둘 것인가

| 문서 | 위치 | 이유 |
|---|---|---|
| 저장소 개요·FSM·로드맵 | 루트 `README.md` | 진입점 |
| 실측 결과 / 장문 조사 | `docs/` | 길고, 코드 옆에 있을 이유가 없다 |
| 모델 카드 (스펙·성능·한계) | `models/README.md` | 가중치 바로 옆에서 읽어야 한다 |
| 외부에서 받은 원본 문서 | `tools/braille_detect/` 등 반입 위치 | **원본은 고치지 않는다** — 우리 판단은 우리 문서에. 부득이한 수정은 그 폴더 문서에 명시한다 |
| 튜닝 숫자·경로 | 문서가 아니라 `config.yaml` | 프로젝트 규칙: 숫자의 단일 출처 |

`HARDWARE.md`는 코드 주석(`hardware/jetson/*.py`, `driving/state_machine.py`)과
`config.yaml`이 근거로 인용한다 — 옮기거나 이름을 바꾸면 그 참조들도 함께 고칠 것.
