#!/usr/bin/env bash
# 현장 실행 헬퍼. 휴대폰 SSH 로 접속해 한 줄만 치면 되도록 만든 것.
#
# 하는 일:
#   - tmux 세션 안에서 추론을 돌린다. SSH 가 끊겨도 프로세스가 살아있고, 다시 접속해
#     `tmux attach -t jumja` 로 화면을 되찾을 수 있다.
#   - 결과 이미지와 로그를 output/ 에 남긴다 (헤드리스라 --show 는 못 쓴다).
#
# 사용법:
#   ./field_run.sh                 # 카메라 0번으로 실행
#   ./field_run.sh 1               # 카메라 1번
#   ./field_run.sh ./photos        # 폴더 일괄 처리
#   ./field_run.sh stop            # 중지
#   ./field_run.sh log             # 최근 로그 보기

set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SESSION=jumja
SRC="${1:-0}"
LOG="$HERE/output/run_$(date +%Y%m%d_%H%M%S).log"

case "$SRC" in
  stop)
    tmux kill-session -t "$SESSION" 2>/dev/null && echo "중지했습니다." || echo "실행 중인 세션이 없습니다."
    exit 0 ;;
  log)
    latest="$(ls -t "$HERE"/output/run_*.log 2>/dev/null | head -1)"
    [ -n "${latest:-}" ] && tail -f "$latest" || echo "로그가 없습니다."
    exit 0 ;;
esac

command -v tmux >/dev/null || { echo "tmux 가 없습니다: sudo apt-get install -y tmux"; exit 1; }
mkdir -p "$HERE/output"

if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "이미 실행 중입니다. 화면 보기: tmux attach -t $SESSION"
  echo "중지: ./field_run.sh stop"
  exit 0
fi

# --save 로 판정된 프레임을 남긴다. 엔진이 없으면 첫 실행에서 자동 빌드된다.
tmux new-session -d -s "$SESSION" \
  "cd '$HERE' && python3 jetson_infer.py --source '$SRC' --save 2>&1 | tee '$LOG'"

echo "실행 시작 (tmux 세션: $SESSION)"
echo "  화면 보기 : tmux attach -t $SESSION   (빠져나오기: Ctrl+B 누른 뒤 D)"
echo "  로그      : ./field_run.sh log"
echo "  중지      : ./field_run.sh stop"
echo "  결과      : $HERE/output/"
