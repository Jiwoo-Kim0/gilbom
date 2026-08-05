#!/usr/bin/env bash
# 젯슨 배포 — 저장소를 보드로 밀어넣고 배포 후 점검(preflight)까지 한 번에 돈다.
#
#   scripts/deploy.sh <젯슨_리눅스_계정>@<젯슨IP>[:<경로>]
#   scripts/deploy.sh ubuntu@192.168.0.42                   # 기본 경로 ~/jetson_final
#   scripts/deploy.sh ubuntu@192.168.0.42:/home/ubuntu/bot  # 경로 지정
#   scripts/deploy.sh ubuntu@192.168.0.42 --no-check        # 점검 생략(전송만)
#
# 계정명은 **젯슨의 리눅스 계정**이다 — 개발 PC(Windows)의 사용자명이 아니다.
# 이 저장소에 남은 보드 경로가 전부 /home/ubuntu/* 이므로(docs/HARDWARE.md의
# /home/ubuntu/DrivingCore, tools/data_recorder의 /home/ubuntu/SSAFY) 기본은 ubuntu다.
# 확실히 하려면 젯슨에서 `whoami`.
#
# rsync 대신 tar | ssh 를 쓴다 — ssh·tar 만 있으면 되므로 개발 PC가 Windows
# (Git Bash)여도 그대로 돈다. rsync 는 Git Bash 기본 구성에 없다.
#
# 전송하지 않는 것: 실행 산출물(__pycache__·.pytest_cache), 보고 출력(reports/),
# 녹화 영상(tools/data_recorder/recordings/), git 메타. 모델 가중치(models/*.pt)는
# **전송한다** — 그것이 배포의 본체다(11MB).
set -euo pipefail

if [ $# -lt 1 ]; then
    sed -n '2,15p' "$0" >&2
    exit 2
fi

TARGET="$1"; shift
RUN_CHECK=1
for arg in "$@"; do
    case "$arg" in
        --no-check) RUN_CHECK=0 ;;
        *) echo "알 수 없는 인자: $arg" >&2; exit 2 ;;
    esac
done

HOST="${TARGET%%:*}"
REMOTE_DIR="${TARGET#*:}"
[ "$REMOTE_DIR" = "$TARGET" ] && REMOTE_DIR="jetson_final"   # 경로 미지정 → 홈 기준
# '~/bot' → 'bot'. 원격 명령의 따옴표 안에서는 ~ 가 확장되지 않아 그대로 두면 보드에
# '~' 라는 이름의 디렉터리가 생긴다. 상대 경로는 원격 셸의 시작 위치(홈) 기준이라 결과가 같다.
REMOTE_DIR="${REMOTE_DIR#\~/}"

ROBOT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROBOT_ROOT"

echo "== 전송: $ROBOT_ROOT → $HOST:$REMOTE_DIR"
tar --exclude='__pycache__' \
    --exclude='.pytest_cache' \
    --exclude='.git' \
    --exclude='reports' \
    --exclude='tools/data_recorder/recordings' \
    -czf - . | ssh "$HOST" "mkdir -p '$REMOTE_DIR' && tar -xzf - -C '$REMOTE_DIR'"

if [ "$RUN_CHECK" -eq 0 ]; then
    echo "== 전송 완료 (점검 생략). 보드에서: cd $REMOTE_DIR && scripts/preflight.sh"
    exit 0
fi

echo "== 배포 후 점검 (보드에서 실행)"
# -t 를 주지 않는다: 비대화형이라 preflight 의 종료 코드가 그대로 이 스크립트의
# 종료 코드가 되고, CI 처럼 쓸 수 있다. -n 은 그 CI 사용을 실제로 성립시킨다 —
# 없으면 ssh 가 이 스크립트의 stdin 을 삼켜서 파이프로 부를 때 멈춘다.
ssh -n "$HOST" "cd '$REMOTE_DIR' && chmod +x scripts/*.sh && scripts/preflight.sh"

cat <<EOF

== 배포 완료: $HOST:$REMOTE_DIR
다음 순서로 확인하고 나갈 것 (근거는 README '젯슨 배포' 절):
  1) ssh $HOST "cd $REMOTE_DIR && scripts/drive.sh --duration 20"   # 실추론·모터 로그만
  2) ssh $HOST "cd $REMOTE_DIR && scripts/webtest.sh"               # 웹 관제로 오버레이 확인
  3) 야외에서 scripts/drive.sh --live                               # 실구동
EOF
