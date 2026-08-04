#!/usr/bin/env bash
# 실주행 런처 — torch가 요구하는 cusparseLt 경로를 잡고 main.py를 실행한다.
# (pip torch가 시스템에 없는 libcusparseLt.so.0을 찾는 Jetson 특유 문제 —
#  경로 근거는 ~/.claude 메모리 jetson-torch-cusparselt-ldpath)
#
#   scripts/drive.sh                          # dry-run + 실추론 (모터 로그만)
#   scripts/drive.sh --live                   # 야외 실주행 (실추론 + 실구동)
#   전달 인자는 main.py로 그대로 넘어간다 (--duration, --config 등)
set -euo pipefail

ROBOT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export LD_LIBRARY_PATH="$HOME/.local/lib/python3.10/site-packages/nvidia/cusparselt/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

exec python3 "$ROBOT_ROOT/main.py" --perception real "$@"
