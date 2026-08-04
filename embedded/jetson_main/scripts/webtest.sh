#!/usr/bin/env bash
# 주행 테스트 웹 대시보드 런처 — cusparseLt 경로 처리는 drive.sh와 동일.
#
#   scripts/webtest.sh            # dry-run + 실추론 + 웹 관제 (모터 로그만)
#   scripts/webtest.sh --live     # 야외 실주행 — 웹 '시작' 버튼으로 출발
#   scripts/webtest.sh --live --comms-config comms.yaml    # + MQTT 원격 미션
#
# --comms-config를 주면 서버 명령과 이 화면의 버튼이 같은 FSM을 민다.
# 카메라는 한 프로세스만 열 수 있으므로 main.py와 동시 실행은 불가하다.
#
# 접속: http://<젯슨IP>:8200  (같은 공유기의 폰·노트북에서)
set -euo pipefail

ROBOT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export LD_LIBRARY_PATH="$HOME/.local/lib/python3.10/site-packages/nvidia/cusparselt/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

exec python3 "$ROBOT_ROOT/scripts/web_test.py" "$@"
