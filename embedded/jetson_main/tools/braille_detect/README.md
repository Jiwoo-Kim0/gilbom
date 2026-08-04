# 점자블럭 이상탐지 — Jetson Orin Nano 배포

이 폴더를 통째로 Jetson Orin Nano 로 복사한 뒤 아래 절차를 따르세요.

## 모델

YOLO26n, imgsz 960, 28 epoch, 3 클래스. NMS-free 구조라 ONNX 출력이 이미 디코딩된
`[1, 300, 6]` 이고 후처리가 단순합니다.

| 클래스 | 뜻 |
|---|---|
| `normal` | 정상 점자블럭 띠 |
| `defect_full` | 불량 점자블럭 띠 (파손·결손·돌기 소실·잡초 피복) |
| `defect_partial` | 띠 안의 국소 파손 지점 |

## 성능

박스 단위 (원본 test 1,736장):

| 클래스 | P | R | mAP50 | mAP50-95 |
|---|---|---|---|---|
| normal | 0.912 | 0.980 | 0.985 | 0.962 |
| defect_full | 0.885 | 0.918 | 0.968 | 0.908 |
| defect_partial | 0.570 | 0.430 | 0.460 | 0.257 |

이미지 단위 정상/불량 판정 (`band_argmax`, 원본 test 1,736장):

| precision | recall | F1 | accuracy |
|---|---|---|---|
| **0.956** | **0.859** | **0.905** | **0.940** |

실제 촬영 환경 (주행 카메라 높이에서 직접 촬영):

| 항목 | 값 |
|---|---|
| 실사 231장 불량 판정률 | **14.3%** (육안 라벨링으로 실측한 실제 불량률 15.3%) |
| 실사 홀드아웃 15장 정확도 | **0.933** (오탐 0건, 미탐 1건) |
| 점자블럭 없는 노면 300장 오탐 | **0%** |

`defect_partial` 의 박스 AP 는 낮습니다. 파손 지점이 화면 면적의 0.5% 수준이라 원래 어려운
클래스이고, 그래서 판정의 1차 근거는 띠 단위(`normal` vs `defect_full`)로 두었습니다.
`defect_partial` 은 띠가 정상으로 판정됐을 때 2곳 이상 잡히면 불량으로 승격시키는 보조
역할만 합니다.

## 포함 파일

| 파일 | 용도 |
|---|---|
| `../../models/braille_defect.pt` | TensorRT 엔진을 빌드할 원본 가중치 (원본 번들의 `best.pt`) |
| ~~`best.onnx`~~ | **2026-08-04 삭제.** 어느 코드도 `.onnx` 를 쓰지 않았다 (엔진은 `.pt` 에서 빌드한다). `trtexec` 경로가 필요하면 `yolo export model=../../models/braille_defect.pt format=onnx` 로 다시 만든다 |
| `model_info.json` | 클래스 이름, 입력 크기, 불량 판정 규칙 |
| `jetson_infer.py` | 추론 실행 스크립트 |
| `field_run.sh` | 현장 실행 헬퍼 (tmux — §5-1) |
| `sample_output.jpg` | 판정 결과 예시 (잡초 피복 → `defect_full` 0.47) |

> **원본 번들과 달라진 점 두 개.** ① `best.pt` 를 이 폴더에 두지 않는다 — 저장소의
> `models/braille_defect.pt` 와 SHA 가 같은 5.4MB 중복이었다. `jetson_infer.py` 가
> 이 폴더의 `best.pt` → 없으면 `../../models/braille_defect.pt` 순으로 찾으므로,
> 이 폴더만 젯슨에 따로 복사해 쓸 때는 가중치를 함께 넣으면 그쪽이 우선한다.
> 엔진 빌드 결과도 가중치 옆(`models/braille_defect.engine`)에 생기며 이는
> `config.yaml` 의 `perception.braille.engine_path` 와 같은 경로다.
> ② `--save` 파일명에 프레임 번호를 붙인다 — 동영상·카메라 소스는 `r.path` 가 매
> 프레임 같아서 원본 그대로면 결과가 한 장으로 계속 덮어써졌다.
> 판정 알고리즘(`decide`/`_band_argmax`/`_iou`)은 원본과 한 글자도 다르지 않다 —
> 이 함수들이 `perception/braille.py` 의 이식 원본이다.

## 1. 환경 준비

JetPack 이 설치된 상태에서 Jetson 용으로 빌드된 PyTorch / torchvision 휠을 먼저 설치해야
합니다. PyPI 의 일반 `torch` 는 aarch64 CUDA 빌드가 아니라 동작하지 않습니다. 설치 방법은
Ultralytics 의 Jetson 가이드(<https://docs.ultralytics.com/guides/nvidia-jetson/>)에
JetPack 버전별로 정리되어 있습니다.

```bash
sudo apt-get install -y python3-pip libopenblas-base libopenmpi-dev
# (가이드에 따라 JetPack 버전에 맞는 torch / torchvision 휠 설치)
pip3 install ultralytics
```

`ultralytics` 는 YOLO26 을 지원하는 버전이어야 합니다(개발 PC 기준 8.4.104 에서 검증).
TensorRT 는 JetPack 에 포함되어 있으므로 별도 설치가 필요 없습니다.

전원 모드를 최대로 올려두면 추론 속도가 크게 달라집니다.

```bash
sudo nvpmodel -m 0     # MAXN
sudo jetson_clocks
```

## 2. 첫 실행 (TensorRT 엔진 자동 빌드)

```bash
python3 jetson_infer.py --source 0
```

엔진(`models/braille_defect.engine`)이 없으면 가중치로부터 FP16 엔진을 자동으로 빌드합니다. 보드 성능에 따라
수 분에서 십수 분 걸리며, 한 번 만들어지면 이후 실행에서 재사용됩니다.

> TensorRT 엔진은 빌드한 GPU 아키텍처와 TensorRT 버전에 종속됩니다. 개발 PC 에서 만든
> 엔진은 Jetson 에서 동작하지 않으므로 반드시 보드에서 빌드해야 합니다.

엔진 빌드 중 메모리가 부족하면 스왑을 늘리거나, 데스크톱 GUI 를 끈 상태에서 실행하세요.

```bash
sudo systemctl isolate multi-user.target   # GUI 종료 (되돌리려면 graphical.target)
```

> YOLO26 은 비교적 새 아키텍처라 보드에서 TensorRT 엔진 빌드가 되는지는 실기 확인이
> 필요합니다. 개발 PC 에서 ONNX 내보내기까지는 검증했습니다. 엔진 빌드가 실패하면
> `--no-trt` 로 `.pt` 직접 추론이 가능합니다(느리지만 동작 확인용으로 충분).

## 3. 사용법

```bash
python3 jetson_infer.py --source 0                 # USB 카메라
python3 jetson_infer.py --source video.mp4 --save  # 동영상, 결과 저장
python3 jetson_infer.py --source ./images --save   # 폴더 일괄 처리
python3 jetson_infer.py --source 0 --show          # 화면 표시 (q 로 종료)
python3 jetson_infer.py --no-trt                   # 엔진 없이 .pt 로 바로 실행
```

## 4. 불량 판정 규칙

`model_info.json` 의 `decision_rule` 에 따라 자동으로 결정됩니다. 현재 배포본은
**`band_argmax`** 입니다.

`normal` 과 `defect_full` 은 같은 띠에 붙은 배타적 라벨이라 모델이 둘을 동시에 냅니다.
그래서 같은 띠에 대한 경쟁 예측을 IoU 0.55 로 묶고, 묶음별 최고 신뢰도 클래스를 채택합니다.
`defect_full` 이 이긴 띠가 하나라도 있으면 불량입니다.

> `defect_full` 박스의 존재 여부만으로 세면 안 됩니다. 실측 결과 그렇게 세면 실사에서
> 66.7% 가 불량으로 나오는데, 같은 데이터에서 묶음 argmax 로는 14.3% 이고 육안 실측
> 실제값이 15.3% 입니다.

### 운영점 조정

`--band-conf` 로 조절합니다. 실측 결과는 아래와 같습니다.

| `--band-conf` | 원본test P | R | F1 | 실사홀드아웃 acc | handmade 판정률 |
|---|---|---|---|---|---|
| **0.00 (기본)** | 0.956 | **0.859** | **0.905** | **0.933** | 14.3% |
| 0.50 | 0.959 | 0.854 | 0.903 | 0.867 | 13.9% |
| 0.60 | 0.966 | 0.824 | 0.890 | 0.867 | 12.6% |
| 0.80 | 0.986 | 0.730 | 0.839 | 0.867 | 12.1% |

**기본값 0(순수 argmax)이 F1 최대이고 실사 정확도도 가장 높습니다.** 올려도 정밀도 이득이
재현율 손실보다 작으므로, 특별히 오탐을 극도로 줄여야 하는 경우가 아니면 그대로 쓰세요.

`--defect-conf` 는 `band_argmax` 에서 **띠 판정에 영향을 주지 않습니다.** 띠가 정상으로
판정됐을 때 `defect_partial` 승격 조건에만 쓰입니다.

## 5. 속도가 부족할 때

1. **세로 사진 전용 직사각 입력** — 원본이 9:16 세로 비율이라 정사각 입력은 좌우 패딩에
   연산을 낭비합니다. 높이·너비를 따로 주면 연산량이 약 40% 줄어듭니다.
   ```bash
   python Tools/export_jetson.py --weights <best.pt> --imgsz 960 544
   ```
2. **입력 크기 축소** — 개발 PC 에서 더 작은 `imgsz` 로 다시 내보냅니다. 단
   `defect_partial` 은 원래 작아서 축소에 민감합니다(960 기준 약 55px).
   ```bash
   python Tools/export_jetson.py --weights <best.pt> --imgsz 768
   ```

개발 PC(RTX 4050) 기준 imgsz 960 에서 추론 3.9ms + 후처리 0.8ms 입니다. Jetson 실측은
보드에서 직접 확인하세요.

## 5-1. 현장 실행 (휴대폰 핫스팟 + SSH)

밖에서 휴대폰으로 젯슨에 접속해 돌리는 시나리오입니다. **핫스팟 등록과 엔진 빌드는 반드시
집/사무실에서 미리 끝내세요.** 현장에서 엔진을 빌드하면 십수 분이 걸리고, 그 사이 SSH 가
끊기면 처음부터 다시 해야 합니다.

### 나가기 전에 (유선/집 와이파이 상태에서)

```bash
# 1) 필수 패키지
sudo apt-get install -y tmux avahi-daemon
chmod +x field_run.sh

# 2) 호스트명 확인 — 현장에서 IP 대신 이걸로 접속한다
hostname                      # 예: jetson-orin  ->  jetson-orin.local

# 3) 휴대폰 핫스팟을 미리 등록해 자동 접속되게 한다 (휴대폰 핫스팟을 켠 상태에서)
nmcli device wifi list
sudo nmcli device wifi connect "<핫스팟이름>" password "<비밀번호>"
sudo nmcli connection modify "<핫스팟이름>" connection.autoconnect yes \
                                            connection.autoconnect-priority 100

# 4) TensorRT 엔진을 미리 빌드해 둔다 (십수 분 소요, 한 번만)
python3 jetson_infer.py --source ./sample_images --no-trt   # 먼저 동작 확인
python3 jetson_infer.py --source ./sample_images            # 엔진 빌드 + 실행
ls -lh ../../models/braille_defect.engine                   # 생겼는지 확인

# 5) SSH 서버 확인
sudo systemctl enable --now ssh
```

### 현장에서

1. 휴대폰 핫스팟을 켭니다. 젯슨에 전원을 넣으면 3번에서 등록해둔 핫스팟에 자동 접속합니다.
2. 휴대폰 SSH 앱(Termius, JuiceSSH 등)으로 접속합니다.

```bash
ssh <사용자>@<호스트명>.local        # 예: ssh nvidia@jetson-orin.local
```

3. 실행합니다.

```bash
cd ~/jetson_final/tools/braille_detect
./field_run.sh                    # USB 카메라 0번, tmux 안에서 실행
```

`field_run.sh` 는 tmux 세션에서 돌리므로 **SSH 가 끊겨도 추론이 계속됩니다.**

```bash
./field_run.sh log                # 판정 로그 실시간 보기
tmux attach -t jumja              # 화면 되찾기 (빠져나오기: Ctrl+B 누른 뒤 D)
./field_run.sh stop               # 중지
```

결과 이미지는 `output/` 에 `*_defect.jpg` / `*_ok.jpg` 로 저장됩니다.

### 접속이 안 될 때

`.local` 이름으로 안 되면 IP 를 직접 찾아야 합니다.

- **안드로이드**: 핫스팟 설정에 접속된 기기 목록이 있고 거기서 IP 를 볼 수 있습니다.
- **아이폰**: 목록을 보여주지 않습니다. 아래처럼 젯슨이 자기 IP 를 알려주게 해두는 편이 낫습니다.

```bash
# 부팅 때마다 IP 를 파일에 남기고, 화면이 있으면 콘솔에도 띄운다
sudo bash -c 'cat > /etc/rc.local <<EOF
#!/bin/sh -e
sleep 15
ip -4 addr show wlan0 | grep inet > /home/<사용자>/current_ip.txt
exit 0
EOF
chmod +x /etc/rc.local'
```

가장 확실한 방법은 **고정 IP** 입니다. 휴대폰 핫스팟 대역(안드로이드는 보통
`192.168.43.x`, 아이폰은 `172.20.10.x`)에 맞춰 잡아두세요.

```bash
sudo nmcli connection modify "<핫스팟이름>" \
    ipv4.method manual ipv4.addresses 172.20.10.5/28 ipv4.gateway 172.20.10.1 \
    ipv4.dns 8.8.8.8
```

이후에는 `ssh <사용자>@172.20.10.5` 로 바로 접속됩니다.

### 카메라 주의

`--source 0` 은 **USB(UVC) 카메라** 기준입니다. CSI 카메라(IMX219 등)는 인덱스로 열리지
않고 GStreamer 파이프라인이 필요하므로, USB 웹캠을 쓰는 것이 가장 확실합니다. 카메라가
잡히는지는 미리 확인하세요.

```bash
ls /dev/video*
v4l2-ctl --list-devices        # 없으면: sudo apt-get install -y v4l-utils
```

헤드리스(모니터 없음) 환경에서는 `--show` 를 쓸 수 없습니다. `field_run.sh` 는 `--save` 로
동작합니다.

## 6. trtexec 로 직접 변환하는 경우

`jetson_infer.py` 의 자동 빌드 대신 수동으로 엔진을 만들려면. 번들에 `best.onnx` 는
없습니다(2026-08-04 삭제 — 소비처가 없었고 10MB였습니다). 먼저 export 합니다:

```bash
yolo export model=../../models/braille_defect.pt format=onnx opset=17
/usr/src/tensorrt/bin/trtexec \
    --onnx=braille_defect.onnx \
    --saveEngine=best.engine \
    --fp16
```

생성된 엔진을 `models/braille_defect.engine` 으로 두면 `jetson_infer.py` 가 그대로 사용합니다
(이 폴더에 `best.pt`+`best.engine` 을 넣어도 그쪽이 우선합니다 — '포함 파일' 항목 참고).

## 7. 알려진 한계

- **`defect_partial` 은 약합니다** (mAP50 0.46). 실사 라벨링 시 시트 해상도로는 국소
  파손을 신뢰할 수 없어 라벨하지 않았습니다. 판정은 띠 단위에 의존합니다.
- **실사 홀드아웃이 15장뿐**입니다. 재현율 0.667 은 표본 3장 기준이라 신뢰구간이 넓습니다.
- **결손·마모 판정은 학습 라벨의 정의를 따릅니다.** 원본 데이터셋은 오염·변색을 정상으로
  라벨링했고, 실사 150장도 같은 관례(돌기가 살아있으면 정상, 잡초가 타일 1/4 이상 가리면
  불량)로 라벨했습니다. 기준을 바꾸려면 재라벨링이 필요합니다.
