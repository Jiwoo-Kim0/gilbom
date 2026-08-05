#!/usr/bin/env bash
# 배포 후 점검 — 보드에서 실행한다. **주행 전에 이걸 통과시키는 것이 배포의 끝이다.**
#
#   scripts/preflight.sh
#
# 확인 순서는 '못 고치면 주행이 불가능한 것' → '없어도 주행은 되는 것'이다.
# 앞의 것이 하나라도 실패하면 종료 코드가 0이 아니다(deploy.sh가 그걸 그대로 쓴다).
# 뒤의 것(장치 존재·GPS)은 경고만 낸다 — 배선을 뽑고 주행 로직만 보는 운용이 있다.
set -uo pipefail

# 보드 파이썬은 python3(3.10 고정)이다. 개발 PC에서 이 스크립트 자체를 점검할 때만
# PY=python 처럼 덮어쓴다 — 보드 동작을 바꾸는 노브가 아니다.
PY="${PY:-python3}"

ROBOT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROBOT_ROOT"
export LD_LIBRARY_PATH="$HOME/.local/lib/python3.10/site-packages/nvidia/cusparselt/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

FAIL=0
WARN=0
pass() { printf '  [OK]   %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1"; FAIL=$((FAIL + 1)); }
warn() { printf '  [WARN] %s\n' "$1"; WARN=$((WARN + 1)); }

echo "== 1. 파이썬 / 의존성 (실추론 경로) =="
"$PY" -c 'import sys; assert sys.version_info[:2] == (3, 10), sys.version' \
    && pass "$("$PY" -V 2>&1) (보드는 3.10 고정)" \
    || fail "python3 버전이 3.10이 아니다 — wheel 호환이 깨진다 (requirements.txt)"

for module in yaml numpy cv2 ultralytics; do
    if "$PY" -c "import $module" 2>/dev/null; then
        pass "import $module"
    else
        fail "import $module 실패 — 실추론(--perception real)이 기동하지 못한다"
    fi
done
"$PY" -c 'import serial' 2>/dev/null \
    && pass "import serial (GPS)" \
    || warn "import serial 실패 — GPS 좌표 없이 보고된다 (fix=false)"

echo "== 2. config.yaml + 7클래스 우선순위 표 =="
"$PY" - <<'PY'
import sys
from main import load_config
from perception.segmentation import ClassNames, CostmapParams, FreeSpaceParams, PostParams

problems = []
config = load_config()
seg = config["perception"]["segmentation"]
try:
    classes = ClassNames.from_config(seg)
    CostmapParams.from_config(seg["costmap"], classes)
    post = PostParams.from_config(seg["post"])
    FreeSpaceParams.from_config(seg["free_space"])
except Exception as error:                      # 표가 깨졌으면 여기서 죽는 것이 맞다
    print(f"  [FAIL] config 파싱 실패: {error}")
    sys.exit(1)

if len(seg["classes"]) != 7:
    problems.append(f"classes 표가 7개가 아니다 ({len(seg['classes'])}개)")
if classes.braille not in classes.drivable:
    problems.append("점자블록이 주행가능이 아니다 — 블록 위에서 경계 소실 정지가 걸린다")
if not classes.roadway:
    problems.append("차도 취급 클래스가 없다 — 차도 방어가 전부 꺼진다")
if post.roadway_risk_fraction >= post.roadway_critical_fraction:
    problems.append("차도 임계가 뒤집혔다 — 선회 구간 없이 곧바로 ESTOP이 된다")
if config["driving"]["inspect"]["confirm_sec"] >= config["driving"]["turn"]["confirm_sec"]:
    problems.append("블록 확정이 회전 확정보다 늦다 — 코너에서 유도선을 놓친다")

for problem in problems:
    print(f"  [FAIL] {problem}")
if not problems:
    print(f"  [OK]   classes 7개 · 주행가능 {list(classes.drivable)} · 차도 {list(classes.roadway)}")
sys.exit(1 if problems else 0)
PY
[ $? -eq 0 ] || FAIL=$((FAIL + 1))

echo "== 3. 모델 가중치 =="
"$PY" - <<'PY'
import os, sys
from main import load_config

config = load_config()
root = os.path.dirname(os.path.abspath("config.yaml"))
missing = []
for label, path in (("세그", config["perception"]["segmentation"]["model_path"]),
                    ("탐지", config["perception"]["braille"]["model_path"])):
    full = os.path.join(root, path)
    if os.path.exists(full):
        print(f"  [OK]   {label} {path} ({os.path.getsize(full) // 1024} KB)")
    else:
        missing.append(f"{label} 가중치 없음: {path}")
for problem in missing:
    print(f"  [FAIL] {problem}")
sys.exit(1 if missing else 0)
PY
[ $? -eq 0 ] || FAIL=$((FAIL + 1))

echo "== 4. 순수 계층 테스트 (주행 판단 전체) =="
if "$PY" -m pytest -q >/tmp/preflight_pytest.log 2>&1; then
    pass "$(tail -1 /tmp/preflight_pytest.log)"
else
    fail "pytest 실패 — /tmp/preflight_pytest.log 확인"
    tail -20 /tmp/preflight_pytest.log | sed 's/^/         /'
fi

echo "== 5. dry-run 시나리오 (FSM 흐름) =="
for scenario in obstacle_avoid roadway_escape braille_trace crosswalk_missing; do
    if "$PY" main.py --scenario "$scenario" --no-operator --duration 12 \
            >/tmp/preflight_$scenario.log 2>&1; then
        pass "시나리오 $scenario"
    else
        fail "시나리오 $scenario 실패 — /tmp/preflight_$scenario.log"
    fi
done

echo "== 6. 장치 (없으면 경고 — 배선 없이 주행 로직만 보는 운용이 있다) =="
"$PY" - <<'PY'
import os
from main import load_config

config = load_config()
perception, hardware = config["perception"], config["hardware"]
for label, path in (
        ("주행 카메라", perception["nav_camera_device"]),
        ("검사 카메라", perception["inspect_camera_device"]),
        ("GPS", hardware["gps"]["device"]),
        ("조이스틱", hardware["joystick"]["device"])):
    print(f"  [{'OK' if os.path.exists(path) else '--'}]   {label}: {path}")

pin = (hardware.get("touch") or {}).get("pin")
print(f"  [{'OK' if pin is not None else '--'}]   터치 센서(시작 트리거): "
      + (f"핀 {pin}" if pin is not None else "핀 미지정 (시작은 Xbox A 버튼만)"))
PY
"$PY" -c "
from hardware.jetson.imu import JetsonImu
from main import load_config
c = load_config()['hardware']['imu']
imu = JetsonImu(bus=c['bus'], address=c['address'], forward_axis=c['forward_axis'],
                up_axis=c['up_axis'], gyro_bias_samples=c['gyro_bias_samples'],
                complementary_alpha=c['complementary_alpha'], yaw_sign=c['yaw_sign'])
print('  [OK]   IMU 응답 (경사·회차 활성)' if imu.read() is not None
      else '  [WARN] IMU 무응답 — 경사(SLOPE)·회차(DEAD_END)만 비활성. 장애물 회피·회전·차도 이탈은 IMU 없이도 돈다')
" 2>/dev/null || warn "IMU 확인 실패 (버스 권한? i2c 그룹 확인)"

echo
if [ "$FAIL" -gt 0 ]; then
    echo "== 점검 실패: $FAIL 건 (경고 $WARN 건) — 주행하지 말 것"
    exit 1
fi
echo "== 점검 통과 (경고 $WARN 건) — scripts/drive.sh 로 진행 가능"
