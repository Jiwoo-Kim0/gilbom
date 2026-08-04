"""
Jetson Orin Nano 에서 실행하는 점자블럭 이상탐지 추론 스크립트.

첫 실행 시 best.pt 로부터 TensorRT FP16 엔진(best.engine)을 자동 빌드한다. 엔진 빌드는
보드 성능에 따라 수 분에서 십수 분 걸리며, 한 번 만들어지면 이후 실행에서 재사용된다.

판정 규칙은 model_info.json 의 decision_rule 을 따른다.
    band_argmax  : 클래스가 [normal, defect_full, defect_partial] 인 모델. 현재 배포본.
                   같은 띠에 대한 경쟁 예측을 IoU 로 묶고 묶음별 최고 신뢰도 클래스를
                   채택한다. defect_full 이 이긴 띠가 하나라도 있으면 불량.
                   운영점은 --band-conf 로 조절한다(--defect-conf 는 국소 파손 승격에만 작용).
    any_defect   : 클래스가 [block, defect] 인 모델. defect 박스가 --defect-conf 이상
                   --min-defects 개 이상 잡히면 불량.
    argmax_class : 클래스가 [normal, defect] 인 모델. 가장 신뢰도 높은 박스의 클래스가 판정.

사용 예)
    python3 jetson_infer.py --source 0                  # USB 카메라
    python3 jetson_infer.py --source test.mp4 --save
    python3 jetson_infer.py --source ./images --save
    python3 jetson_infer.py --source 0 --band-conf 0.6  # 오탐을 더 줄이고 싶을 때
    python3 jetson_infer.py --no-trt                    # 엔진 빌드 없이 .pt 로 바로 실행
"""

import argparse
import json
import sys
import time
from pathlib import Path

import cv2

HERE = Path(__file__).resolve().parent
# 가중치는 저장소 단일 사본을 쓴다 — 원본 번들은 best.pt 를 이 폴더에 함께 뒀지만
# 그러면 models/braille_defect.pt 와 5.4MB 짜리 같은 파일이 두 벌이 된다(SHA 일치).
# 엔진도 가중치 옆에 빌드되므로 config.yaml 의 perception.braille.engine_path 와
# 자동으로 같은 경로가 된다. 번들만 따로 복사해 쓰려면 best.pt 를 이 폴더에 두면
# 그쪽이 우선한다 — 아래 폴백 순서가 그것이다.
LOCAL_PT = HERE / "best.pt"                             # 번들 단독 복사 시
REPO_PT = HERE.parent.parent / "models" / "braille_defect.pt"   # 저장소 안에서 실행 시
GREEN = (0, 200, 0)
RED = (0, 0, 255)


def load_info():
    info_path = HERE / "model_info.json"
    if not info_path.exists():
        sys.exit(f"[오류] model_info.json 이 없습니다: {info_path}")
    return json.loads(info_path.read_text(encoding="utf-8"))


def get_model(info, use_trt):
    from ultralytics import YOLO

    pt = LOCAL_PT if LOCAL_PT.exists() else REPO_PT
    engine = pt.with_suffix(".engine")

    if not use_trt:
        if not pt.exists():
            sys.exit(f"[오류] 가중치가 없습니다: {LOCAL_PT} 도 {REPO_PT} 도 없음")
        print(f"PyTorch 가중치로 실행: {pt}")
        return YOLO(str(pt))

    if not engine.exists():
        if not pt.exists():
            sys.exit(f"[오류] 엔진도 가중치도 없습니다: {LOCAL_PT} / {REPO_PT}")
        print("TensorRT 엔진이 없어 새로 빌드합니다. 보드 성능에 따라 수 분 이상 걸립니다.")
        built = YOLO(str(pt)).export(format="engine", half=True, imgsz=info["imgsz"], device=0)
        built = Path(built)
        if built.resolve() != engine.resolve():
            built.replace(engine)
        print(f"엔진 빌드 완료: {engine}")

    print(f"TensorRT 엔진으로 실행: {engine}")
    return YOLO(str(engine), task="detect")


IOU_SAME_BAND = 0.55


def _iou(a, b):
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    ua = ((a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter)
    return inter / (ua + 1e-9)


def _band_argmax(boxes, names, nid, fid):
    """같은 띠에 대한 경쟁 예측을 IoU 로 묶고 묶음별 최고 신뢰도 클래스를 채택한다.

    normal 과 defect_full 은 같은 객체에 붙은 배타적 라벨이라 모델이 둘을 동시에 낸다.
    'defect_full 박스가 있는가' 로 세면 과대집계된다(실측: 66.7% vs 실제 15%).
    """
    xyxy = boxes.xyxy.tolist() if hasattr(boxes.xyxy, "tolist") else boxes.xyxy
    bands = [(float(c), int(k), xyxy[i])
             for i, (c, k) in enumerate(zip(boxes.conf, boxes.cls))
             if int(k) in (nid, fid)]
    bands.sort(key=lambda t: -t[0])
    groups = []
    for f, k, b in bands:
        for g in groups:
            if _iou(b, g[0][2]) >= IOU_SAME_BAND:
                g.append((f, k, b))
                break
        else:
            groups.append([(f, k, b)])
    return groups


def decide(boxes, names, rule, defect_conf, min_defects, band_conf=0.0):
    """검출 결과 -> (불량 여부, 사유 문자열)

    band_conf: band_argmax 규칙의 운영점. 0 이면 순수 argmax(기본).
               올리면 defect_full 이 이 신뢰도를 넘어야 불량으로 판정해 오탐이 줄고
               미탐이 늘어난다. argmax 만으로는 조절 노브가 없어서 둔 값이다.
    """
    if rule == "argmax_class":
        if not len(boxes):
            return False, "검출 없음"
        i = int(boxes.conf.argmax())
        cls_name = names[int(boxes.cls[i])]
        return cls_name == "defect", f"{cls_name} {float(boxes.conf[i]):.2f}"

    if rule == "band_argmax":
        # 3클래스 구성(normal/defect_full/defect_partial). 띠 판정을 1차 근거로 쓴다.
        inv = {n: i for i, n in names.items()}
        nid, fid = inv.get("normal"), inv.get("defect_full")
        if nid is None or fid is None:
            return False, "normal/defect_full 클래스 없음"
        if not len(boxes):
            return False, "검출 없음"
        groups = _band_argmax(boxes, names, nid, fid)
        if not groups:
            return False, "띠 검출 없음"
        bad = [g[0] for g in groups if g[0][1] == fid and g[0][0] >= band_conf]
        pid = inv.get("defect_partial")
        parts = ([float(c) for c, k in zip(boxes.conf, boxes.cls)
                  if pid is not None and int(k) == pid and float(c) >= defect_conf])
        if bad:
            note = f" +파손 {len(parts)}곳" if parts else ""
            return True, (f"불량 띠 {len(bad)}/{len(groups)} "
                          f"(최대 {max(f for f, _, _ in bad):.2f}){note}")
        # 띠는 정상으로 봤지만 국소 파손이 여러 곳이면 승격시킨다
        if len(parts) >= max(2, min_defects):
            return True, f"국소 파손 {len(parts)}곳 (최대 {max(parts):.2f})"
        return False, f"정상 띠 {len(groups)}개" + (f", 파손 {len(parts)}곳" if parts else "")

    defect_id = next((i for i, n in names.items() if n == "defect"), None)
    if defect_id is None:
        return False, "defect 클래스 없음"
    confs = [float(c) for c, k in zip(boxes.conf, boxes.cls)
             if int(k) == defect_id and float(c) >= defect_conf]
    if len(confs) >= min_defects:
        return True, f"파손 {len(confs)}곳 (최대 {max(confs):.2f})"
    return False, f"파손 {len(confs)}곳"


def main():
    ap = argparse.ArgumentParser(description="점자블럭 이상탐지 (Jetson Orin Nano)")
    ap.add_argument("--source", type=str, default="0",
                    help="카메라 인덱스, 동영상 파일, 이미지 파일 또는 폴더")
    ap.add_argument("--conf", type=float, default=0.25, help="검출 신뢰도 하한")
    ap.add_argument("--iou", type=float, default=0.5)
    ap.add_argument("--defect-conf", type=float, default=None,
                    help="불량 판정 임계값. 미지정 시 model_info.json 값 사용")
    ap.add_argument("--min-defects", type=int, default=1,
                    help="불량으로 판정할 최소 파손 검출 개수. 오탐이 많으면 2로 올린다")
    ap.add_argument("--band-conf", type=float, default=0.0,
                    help="band_argmax 규칙의 운영점. 0이면 순수 argmax(기본). "
                         "올리면 오탐이 줄고 미탐이 늘어난다. any_defect 규칙에는 무영향")
    ap.add_argument("--no-trt", action="store_true", help="TensorRT 대신 best.pt 로 실행")
    ap.add_argument("--save", action="store_true", help="결과를 output/ 에 저장")
    ap.add_argument("--show", action="store_true", help="화면에 실시간 표시")
    args = ap.parse_args()

    info = load_info()
    rule = info["decision_rule"]
    defect_conf = args.defect_conf if args.defect_conf is not None else info["defect_conf"]
    model = get_model(info, use_trt=not args.no_trt)
    names = model.names

    source = int(args.source) if args.source.isdigit() else args.source
    out_dir = HERE / "output"
    if args.save:
        out_dir.mkdir(exist_ok=True)

    print(f"판정 규칙: {rule} (defect_conf={defect_conf}, min_defects={args.min_defects})")
    print("추론 시작. 종료하려면 Ctrl+C 또는 화면에서 q\n")

    n_total = n_defect = 0
    t_start = time.time()
    stream = model.predict(source=source, conf=args.conf, iou=args.iou,
                           imgsz=info["imgsz"], stream=True, verbose=False)

    for r in stream:
        n_total += 1
        is_defect, reason = decide(r.boxes, names, rule, defect_conf,
                                   args.min_defects, args.band_conf)
        n_defect += int(is_defect)

        label = "BULLYANG (DEFECT)" if is_defect else "JEONGSANG (OK)"
        color = RED if is_defect else GREEN
        frame = r.plot()
        cv2.rectangle(frame, (0, 0), (frame.shape[1], 44), color, -1)
        cv2.putText(frame, f"{label}  |  {reason}", (10, 32),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA)

        fps = n_total / max(1e-6, time.time() - t_start)
        print(f"[{n_total}] {'불량' if is_defect else '정상'} - {reason}  ({fps:.1f} FPS)")

        if args.save:
            stem = Path(getattr(r, "path", f"frame_{n_total:06d}")).stem
            cv2.imwrite(str(out_dir / f"{stem}_{'defect' if is_defect else 'ok'}.jpg"), frame)
        if args.show:
            cv2.imshow("tactile block", frame)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

    cv2.destroyAllWindows()
    elapsed = time.time() - t_start
    print(f"\n총 {n_total}프레임, 불량 {n_defect}건, 평균 {n_total/max(1e-6,elapsed):.1f} FPS")


if __name__ == "__main__":
    main()
