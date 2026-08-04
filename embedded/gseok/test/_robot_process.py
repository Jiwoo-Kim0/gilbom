"""LWT 검증용 자식 프로세스 — 접속만 하고 대기한다.

부모(test_broker_e2e.py)가 강제 kill 하면 DISCONNECT 를 보내지 못하므로
브로커가 will(UNGRACEFUL_DISCONNECT)을 발행해야 한다.
"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from comms import MqttClient, MqttConfig  # noqa: E402


def main() -> int:
    host, port, robot_id, outbox_path = sys.argv[1:5]
    client = MqttClient(
        MqttConfig(
            robot_id=robot_id,
            host=host,
            port=int(port),
            keepalive=30,
            outbox_path=outbox_path,
        )
    )
    client.start()
    if not client.wait_until_connected(timeout=10):
        print("FAILED", flush=True)
        return 1
    print("READY", flush=True)
    time.sleep(300)  # 부모가 kill 할 때까지
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
