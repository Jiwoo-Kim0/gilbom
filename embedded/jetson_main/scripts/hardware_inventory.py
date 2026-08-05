#!/usr/bin/env python3
"""Phase 0 — 젯슨 하드웨어·코드 인벤토리.

젯슨에서 실행하면 markdown 보고서를 stdout으로 출력한다. 결과는 docs/HARDWARE.md로 정리한다.

    python3 scripts/hardware_inventory.py [--codebase <기존 코드 경로>] > /tmp/inventory.md

수집 항목:
  1. I2C: /dev/i2c-* 전 버스 i2cdetect 스캔, 발견 주소 나열
  2. 카메라: /dev/video* 목록 (+ v4l2-ctl 있으면 상세, 없으면 sysfs 이름 폴백)
  3. USB: lsusb
  4. config.yaml 검증: 설정된 모터/서보 I2C 주소가 실제 버스에 보이는지 대조
  5. (선택) 기존 코드베이스에서 모터/서보 제어 방식 추출 (라이브러리·주소·채널)
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys


def sh(cmd, timeout_sec=10):
    """외부 명령 실행 → (성공 여부, stdout+stderr 텍스트)."""
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_sec)
        out = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode == 0, out.strip()
    except FileNotFoundError:
        return False, f"(명령 없음: {cmd[0]})"
    except subprocess.TimeoutExpired:
        return False, "(시간 초과)"


def scan_i2c():
    """모든 /dev/i2c-* 버스를 i2cdetect로 스캔. {버스번호: {주소: 상태}} 반환."""
    result = {}
    for dev in sorted(glob.glob("/dev/i2c-*"), key=lambda p: int(p.rsplit("-", 1)[1])):
        bus = int(dev.rsplit("-", 1)[1])
        ok, out = sh(["i2cdetect", "-y", "-r", str(bus)])
        found = {}
        if ok:
            for line in out.splitlines()[1:]:
                m = re.match(r"^([0-9a-f]0):((?:\s+(?:[0-9a-f]{2}|--|UU))+)\s*$", line)
                if not m:
                    continue
                row_base = int(m.group(1), 16)
                for col, cell in enumerate(m.group(2).split()):
                    if cell != "--":
                        # i2cdetect 행은 16개 열이지만 첫 행은 0x03부터 시작해
                        # 열 인덱스가 곧 하위 니블이 아니다 → 셀 값으로 역산
                        addr = row_base + (int(cell, 16) - row_base if cell != "UU" else col)
                        if cell == "UU":
                            # UU: 커널 드라이버가 점유 중 (주소 = 행베이스+열, 첫 행 보정)
                            addr = row_base + col + (3 if row_base == 0 else 0)
                            found[addr] = "UU(커널 드라이버 점유)"
                        else:
                            found[int(cell, 16)] = "응답"
        result[bus] = {"ok": ok, "found": found, "raw": out}
    return result


def scan_cameras():
    devices = sorted(glob.glob("/dev/video*"))
    detail = ""
    if shutil.which("v4l2-ctl"):
        _, detail = sh(["v4l2-ctl", "--list-devices"])
    else:
        lines = ["(v4l2-ctl 없음 → sysfs 이름 폴백)"]
        for node in sorted(glob.glob("/sys/class/video4linux/video*")):
            try:
                with open(os.path.join(node, "name")) as f:
                    lines.append(f"{os.path.basename(node)}: {f.read().strip()}")
            except OSError:
                pass
        detail = "\n".join(lines)
    return devices, detail


def scan_codebase(root):
    """기존 코드베이스에서 모터/서보 제어 단서(라이브러리 import, I2C 주소, 채널) 추출."""
    patterns = {
        "라이브러리": re.compile(
            r"^\s*(?:from|import)\s+(adafruit_pca9685|adafruit_servokit|Jetson\.GPIO"
            r"|smbus2|busio)\b"),
        "I2C 주소": re.compile(r"(ADDRESS|address)\s*[=:]\s*(0x[0-9A-Fa-f]{2})"),
        "채널/조향": re.compile(
            r"(STEERING_CENTER|STEERING_MAX_LEFT|STEERING_MAX_RIGHT|MANEUVER_RIGHT_MAX"
            r"|_CHANNEL|_CH\b|channels?\[)\s*[=:\[]?\s*(\d+)?"),
    }
    hits = []
    for path in sorted(glob.glob(os.path.join(root, "**", "*.py"), recursive=True)):
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                for lineno, line in enumerate(f, 1):
                    for kind, pat in patterns.items():
                        if pat.search(line):
                            rel = os.path.relpath(path, root)
                            hits.append(f"- `{rel}:{lineno}` [{kind}] `{line.strip()[:100]}`")
                            break
        except OSError:
            pass
    return hits


def load_expected(config_path):
    """config.yaml의 하드웨어 절을 읽어 검증 대상 (버스, 주소, 라벨) 목록을 만든다."""
    try:
        import yaml
        with open(config_path) as f:
            config_data = yaml.safe_load(f)
        hardware_section = config_data["hardware"]
        bus = hardware_section["i2c_bus"]
        return [
            (bus, hardware_section["drive_motor"]["address"], "구동 모터 HAT (PCA9685)"),
            (bus, hardware_section["steering_servo"]["address"], "조향 서보 보드 (PCA9685)"),
            (hardware_section["imu"]["bus"], hardware_section["imu"]["address"], "IMU (미도착 — 부재가 정상)"),
        ]
    except Exception as e:  # config가 없어도 인벤토리 자체는 계속
        print(f"<!-- config.yaml 로드 실패: {e} -->", file=sys.stderr)
        return []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_config = os.path.join(
        os.path.dirname(os.path.abspath(__file__)), os.pardir, "config.yaml")
    parser.add_argument("--config", default=default_config,
                        help="검증 대조에 쓸 config.yaml 경로")
    parser.add_argument("--codebase", default=None,
                        help="모터/서보 제어 방식을 추출할 기존 코드베이스 경로")
    args = parser.parse_args()

    print("# 하드웨어 인벤토리 (Phase 0 자동 수집)")
    ok, host = sh(["uname", "-a"])
    print(f"\n- 실행 환경: `{host}`" if ok else "")

    print("\n## 1. I2C 버스 스캔")
    i2c = scan_i2c()
    for bus, info in i2c.items():
        if not info["ok"]:
            print(f"- 버스 {bus}: 스캔 실패 — {info['raw'][:120]}")
        elif info["found"]:
            addrs = ", ".join(f"0x{a:02x} ({s})" for a, s in sorted(info["found"].items()))
            print(f"- 버스 {bus}: {addrs}")
        else:
            print(f"- 버스 {bus}: 발견 장치 없음")

    print("\n## 2. config.yaml 대조 검증")
    for bus, addr, label in load_expected(args.config):
        info = i2c.get(bus)
        if info is None or not info["ok"]:
            verdict = "버스 스캔 불가"
        elif addr in info["found"]:
            verdict = f"확인됨 ({info['found'][addr]})"
        else:
            verdict = "응답 없음 — 전원(모터 배터리)/배선 확인 필요"
        print(f"- 버스 {bus} 0x{addr:02x} {label}: {verdict}")

    print("\n## 3. 카메라")
    devices, detail = scan_cameras()
    print(f"- 노드: {', '.join(devices) if devices else '없음'}")
    print("```")
    print(detail)
    print("```")

    print("\n## 4. USB 장치 (lsusb)")
    _, usb = sh(["lsusb"])
    print("```")
    print(usb)
    print("```")

    if args.codebase:
        print(f"\n## 5. 기존 코드베이스 추출 ({args.codebase})")
        hits = scan_codebase(args.codebase)
        print("\n".join(hits) if hits else "- 단서 없음")


if __name__ == "__main__":
    main()
