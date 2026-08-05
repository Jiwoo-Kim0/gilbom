# tools — 주행 프로세스와 별개로 도는 도구들

여기 있는 것은 **`main.py`가 import 하지 않는다.** 주행 런타임(`driving/`,
`perception/`, `hardware/`)과 섞이지 않게 분리해 둔 폴더다. 그래서 이 안의
스크립트가 요구하는 패키지가 없어도 실주행에는 아무 영향이 없다.

| 폴더 | 무엇 | 언제 쓰나 |
|---|---|---|
| [`braille_detect/`](braille_detect/) | 점자블록 탐지 모델의 **원본 배포 번들** — 단독 추론 스크립트 + 현장 실행 헬퍼 + 배포 문서 | 주행 없이 **탐지만** 확인할 때. TensorRT 엔진 빌드도 여기서 한다 |
| [`data_recorder/`](data_recorder/) | 컨트롤러 수동 조작 + 카메라 2대 녹화 (웹 대시보드) | `config.yaml`의 임계값을 재보정할 **실사 영상**을 확보할 때 |
| `gps_test/` | u-blox 7 NMEA 진단 3종 (`gps_test`, `gps_sats`, `gps_diag`) | GPS가 fix를 못 잡을 때. 주행이 쓰는 리더는 `scripts/gps_reader.py`다 |

`led_test/`(40핀 헤더 GPIO 점멸 실험 + 핀맥스 덤프)는 **2026-08-04에 삭제했다** —
종료된 브링업 실험이고 주행과 무관한 40여 개 파일이었다.

## 주의: 카메라와 주행은 동시에 못 쓴다

`data_recorder`와 `braille_detect`는 둘 다 USB 카메라를 점유한다. `main.py`
실주행(`--perception real`) 전에 반드시 종료할 것 — `docs/HARDWARE.md`에 같은 경고가
있다. 두 카메라 동시 스트리밍 자체가 MJPG 강제를 전제한 운용이라 여유가 없다.

## 가중치는 `models/`에 하나만 있다

`braille_detect/`는 원본 번들이지만 `best.pt`를 갖고 있지 않다 —
`models/braille_defect.pt`와 SHA가 같은 5.4MB 중복이었다. 번들 스크립트가
`./best.pt` → 없으면 `../../models/braille_defect.pt` 순으로 찾는다. 이 폴더만
젯슨에 따로 복사해 쓸 때는 가중치를 함께 넣으면 그쪽이 우선한다
(자세한 내용은 `braille_detect/README.md`의 '포함 파일').
