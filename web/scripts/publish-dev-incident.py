#!/usr/bin/env python3
"""Publish a contract-valid Incident Event to the local MQTT broker.

개발·시연용이다. Jetson 없이 결함 생성 경로를 돌려보기 위해 만들었고,
`tools/mock-robot`은 presence·state·telemetry·command만 발행해서 incident를 다루지
못한다. `test/통신테스트/mqtt_tls_test.py`도 incident를 보내지만 현재 MQTT v2 계약보다
오래되어(ttl_sec 누락, robot_id 사용) 서버가 MQTT_PAYLOAD_MALFORMED로 거부한다.

--image를 주면 Incident와 같은 event_id로 증거 이미지를 먼저 업로드해서,
업로드 → Incident → 결함 → 이미지 연결까지 한 번에 확인할 수 있다.

로컬에 paho-mqtt를 설치할 수 없는 환경에서는 컨테이너로 실행한다.

    docker run --rm --network gilbom_backend \
      -v "$PWD/scripts:/w:ro" \
      -v "$PWD/infra/mosquitto/local/runtime/certs:/certs:ro" \
      -e MQTT_PASSWORD=... \
      python:3.12-alpine sh -c \
      "pip install -q 'paho-mqtt>=2.1,<3' && python /w/publish-dev-incident.py \
       --host mosquitto --ca-certificate /certs/local-ca-cert.pem"

발행 대상 로봇·순찰구역·Mission이 DB에 있어야 한다. zone_id는 patrol_area.area_code와
같아야 하고, mission_id는 mission.mission_code와 같아야 한다. 어긋나면 서버가 409로
거부하거나 결함을 만들지 않고 넘어간다.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

#: 계약 2.0의 필수 범위. 서버가 @Min(60) @Max(300)으로 검증한다.
DEFAULT_TTL_SEC = 300
#: zone_id는 형식을 해석하지 않으며 patrol_area.area_code와 같은 값을 사용한다.
DEFAULT_ZONE_ID = "A-01"
DEFAULT_MISSION_ID = "MIS-TEST-001"
DEFAULT_MQTT_ID = "robot-test"


def utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


#: damage_ratio가 위험도를 가른다 (DefectRiskAssessment: 25/50/75 임계).
#: 시연 데이터가 한 등급에 몰리지 않도록 순환시킨다.
DAMAGE_RATIO_CYCLE = (0.12, 0.35, 0.62, 0.88)
CONFIDENCE_CYCLE = (0.61, 0.74, 0.88, 0.96)

#: 위도 1도는 약 111,320m다. 경도는 위도에 따라 줄어든다.
METERS_PER_LATITUDE_DEGREE = 111_320.0


def spread_position(arguments, index: int) -> tuple[float, float]:
    """index번째 결함을 격자로 흩어 놓는다.

    같은 지점에 계속 발행하면 서버가 2m 반경 안을 같은 결함으로 병합해
    관측만 쌓이고 결함은 하나가 된다. 목록·지도를 보려면 떨어뜨려야 한다.
    """
    if arguments.spread_m <= 0 or index == 0:
        return arguments.lat, arguments.lon
    columns = 6
    row, column = divmod(index, columns)
    latitude_offset = (row * arguments.spread_m) / METERS_PER_LATITUDE_DEGREE
    longitude_meters_per_degree = METERS_PER_LATITUDE_DEGREE * math.cos(
        math.radians(arguments.lat))
    longitude_offset = (
        (column * arguments.spread_m) / longitude_meters_per_degree
    )
    return (
        round(arguments.lat + latitude_offset, 7),
        round(arguments.lon + longitude_offset, 7),
    )


def incident_payload(arguments, event_id: str, image_id: str | None) -> dict:
    occurred_at = utc_now()
    return {
        "schema_version": "2.0",
        "event_id": event_id,
        "event_type": "BRAILLE_BLOCK_INCIDENT_DETECTED",
        # 계약 키는 robot_id가 아니라 mqtt_id다. DB의 UUID robot_id와 구분한다
        # (final-mqtt-topic-schema-v2.md 42~44행).
        "mqtt_id": arguments.mqtt_id,
        "boot_id": arguments.boot_id,
        "seq": arguments.seq,
        "source_component": "ai",
        "occurred_at": occurred_at,
        "sent_at": occurred_at,
        # 오래된 스크립트가 빠뜨려 MQTT_PAYLOAD_MALFORMED를 내던 필드다.
        "ttl_sec": arguments.ttl_sec,
        "mission_id": arguments.mission_id,
        "location": {
            "valid": True,
            "lat": arguments.current_lat,
            "lon": arguments.current_lon,
            "zone_id": arguments.zone_id,
        },
        "incident": {
            "asset_type": "BRAILLE_BLOCK",
            "block_type": arguments.block_type,
            # 계약상 MQTT로 올 수 있는 값은 DAMAGED 하나뿐이다.
            # 다른 유형은 관리자 PATCH로만 바뀐다.
            "defect_type": arguments.defect_type,
            "confidence": arguments.current_confidence,
            "attributes": {"damage_ratio": arguments.current_damage_ratio},
        },
        "evidence": {
            "image_id": image_id,
            "captured_at": occurred_at,
            "image_width": 320,
            "image_height": 240,
            "bbox_norm": [0.10, 0.20, 0.45, 0.70],
        },
        "model": {
            "name": "gilbom-defect-detector",
            "version": "dev-1.0.0",
        },
    }


def upload_image(arguments, event_id: str) -> tuple[int, str]:
    """POST /api/v1/images. Incident와 같은 event_id를 써야 서버가 연결한다."""
    content = Path(arguments.image).read_bytes()
    if not content.startswith(b"\xff\xd8") or not content.endswith(b"\xff\xd9"):
        print("image upload failed: 파일이 JPEG 형식이 아닙니다.", file=sys.stderr)
        raise SystemExit(2)
    if len(content) > 512_000:
        print(
            f"image upload failed: JPEG가 500KB 상한을 초과합니다 ({len(content)} bytes).",
            file=sys.stderr,
        )
        raise SystemExit(2)
    boundary = "----gilbom" + uuid.uuid4().hex
    parts: list[bytes] = []

    def field(name: str, value: str) -> None:
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"'
            f"\r\n\r\n{value}\r\n".encode()
        )

    field("event_id", event_id)
    field("robot_id", arguments.robot_id)
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="file";'
        f' filename="evidence.jpg"\r\nContent-Type: image/jpeg\r\n\r\n'.encode()
    )
    parts.append(content)
    parts.append(f"\r\n--{boundary}--\r\n".encode())

    request = urllib.request.Request(
        arguments.image_api,
        data=b"".join(parts),
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-API-Key": arguments.api_key,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = json.loads(response.read())
            status = response.status
    except urllib.error.HTTPError as error:
        print(
            f"image upload failed: HTTP {error.code} {error.read()[:300]!r}",
            file=sys.stderr,
        )
        raise SystemExit(1) from error
    image_id = body.get("imageId")
    if status not in (200, 201) or not image_id:
        print(
            f"image upload failed: unexpected response status={status}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    print(f"uploaded status={status} image_id={image_id}")
    return status, image_id


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=os.getenv("MQTT_HOST", "localhost"))
    parser.add_argument("--port", type=int, default=8883)
    parser.add_argument("--mqtt-id", default=DEFAULT_MQTT_ID,
                        help="MQTT username·topic·payload robot_id. robot.mqtt_id와 같아야 한다")
    parser.add_argument("--ca-certificate", type=Path, required=True)
    parser.add_argument("--boot-id", default=str(uuid.uuid4()))
    parser.add_argument("--seq", type=int, default=1)
    parser.add_argument("--ttl-sec", type=int, default=DEFAULT_TTL_SEC)
    parser.add_argument("--mission-id", default=DEFAULT_MISSION_ID)
    parser.add_argument("--zone-id", default=DEFAULT_ZONE_ID)
    # 시연 구역: 구미 진평동. 실제 순찰구역 좌표가 확정되면 이 기본값을 바꾼다.
    parser.add_argument("--lat", type=float, default=36.1100)
    parser.add_argument("--lon", type=float, default=128.4200)
    parser.add_argument("--defect-type", default="DAMAGED")
    parser.add_argument("--block-type", default="LINEAR")
    parser.add_argument("--confidence", type=float, default=None,
                        help="생략하면 여러 값을 순환해 신뢰도를 분산시킨다")
    parser.add_argument("--damage-ratio", type=float, default=None,
                        help="생략하면 LOW~CRITICAL이 고루 나오도록 순환시킨다")
    parser.add_argument("--count", type=int, default=1,
                        help="발행할 Incident 수. seq는 자동으로 증가한다")
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--spread-m", type=float, default=0.0,
                        help="결함을 이 간격(m)의 격자로 흩어 놓는다. "
                             "0이면 한 지점에 발행해 서버가 2m 병합을 적용한다")
    # 이미지 업로드까지 함께 확인할 때만 사용한다.
    parser.add_argument("--image", type=Path,
                        help="함께 업로드할 JPEG 경로")
    parser.add_argument("--image-api",
                        default="http://localhost:8080/api/v1/images")
    parser.add_argument("--api-key", default=os.getenv("GILBOM_ROBOT_API_KEY"))
    parser.add_argument(
        "--api-key-file",
        type=Path,
        help="이미지 X-API-Key 파일. 운영 점검에서는 환경변수·명령 인자보다 이 옵션을 사용한다",
    )
    parser.add_argument("--robot-id", default=os.getenv("GILBOM_ROBOT_ID"),
                        help="이미지 업로드용 robot.robot_id (UUID)")
    parser.add_argument(
        "--mqtt-password-file",
        type=Path,
        help="MQTT 비밀번호 파일. 운영 점검에서는 MQTT_PASSWORD 환경변수보다 이 옵션을 사용한다",
    )
    parser.add_argument(
        "--verify-image-idempotency",
        action="store_true",
        help="같은 event_id로 두 번 업로드해 최초 201, 재업로드 200과 같은 imageId를 검증한다",
    )
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    try:
        import paho.mqtt.client as mqtt
    except ModuleNotFoundError:
        print(
            "paho-mqtt가 필요합니다. runbook의 격리 Python 컨테이너 명령을 사용하세요.",
            file=sys.stderr,
        )
        return 2
    if arguments.api_key_file:
        arguments.api_key = arguments.api_key_file.read_text().strip()
    if arguments.image and not (arguments.api_key and arguments.robot_id):
        print("--image를 쓰려면 --api-key-file과 --robot-id가 필요합니다.",
              file=sys.stderr)
        return 2

    password = (
        arguments.mqtt_password_file.read_text().strip()
        if arguments.mqtt_password_file
        else os.getenv("MQTT_PASSWORD")
    )
    if not password:
        print("--mqtt-password-file 또는 MQTT_PASSWORD 환경변수가 필요합니다.",
              file=sys.stderr)
        return 2

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"dev-incident-{uuid.uuid4().hex[:8]}",
        protocol=mqtt.MQTTv5,
    )
    client.username_pw_set(arguments.mqtt_id, password)
    context = ssl.create_default_context(
        cafile=str(arguments.ca_certificate))
    context.check_hostname = True
    context.verify_mode = ssl.CERT_REQUIRED
    client.tls_set_context(context)

    client.connect(arguments.host, arguments.port, keepalive=30)
    client.loop_start()
    topic = f"tbs/v2/robots/{arguments.mqtt_id}/events/incident"

    try:
        for index in range(arguments.count):
            event_id = str(uuid.uuid4())
            image_id = None
            if arguments.image:
                first_status, image_id = upload_image(arguments, event_id)
                if arguments.verify_image_idempotency:
                    second_status, second_image_id = upload_image(
                        arguments, event_id
                    )
                    if (
                        first_status != 201
                        or second_status != 200
                        or second_image_id != image_id
                    ):
                        print(
                            "image idempotency failed: "
                            f"first={first_status} second={second_status} "
                            f"same_image={second_image_id == image_id}",
                            file=sys.stderr,
                        )
                        return 1
                    print("image-idempotency=PASS")
            arguments.current_lat, arguments.current_lon = spread_position(
                arguments, index)
            arguments.current_damage_ratio = (
                arguments.damage_ratio
                if arguments.damage_ratio is not None
                else DAMAGE_RATIO_CYCLE[index % len(DAMAGE_RATIO_CYCLE)]
            )
            arguments.current_confidence = (
                arguments.confidence
                if arguments.confidence is not None
                else CONFIDENCE_CYCLE[index % len(CONFIDENCE_CYCLE)]
            )
            payload = incident_payload(arguments, event_id, image_id)
            info = client.publish(topic, json.dumps(payload), qos=1)
            info.wait_for_publish(timeout=10)
            print(f"published seq={payload['seq']} event_id={event_id} "
                  f"image_id={image_id} zone={payload['location']['zone_id']}")
            if image_id:
                print(
                    "day8-result "
                    f"event_id={event_id} image_id={image_id}"
                )
            # seq는 boot_id 안에서 단조 증가해야 한다. 서버가 재전송·역행을 거른다.
            arguments.seq += 1
            if index + 1 < arguments.count:
                time.sleep(arguments.interval)
    finally:
        client.loop_stop()
        client.disconnect()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
