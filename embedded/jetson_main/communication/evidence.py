"""증거 사진 — 저장(JPEG 규격 맞추기)과 백그라운드 업로드.

계약 §8 은 결함 1건마다 사진을 함께 요구한다. 사진은 MQTT 로 보내지 않는다:
HTTPS 로 올리고 참조값(image_id)만 event 에 싣는다 (§7.5).

업로드를 스레드로 뺀 이유는 하나다 — **로봇이 사진을 기다리며 서 있으면 안 된다.**
동기로 올리면 네트워크가 나쁠 때 재시도 때문에 최대 47초 동안 메인 루프가 멈추고,
그동안 주행·검사·명령 수신이 전부 정지한다. 검사는 정차가 맞지만 업로드는 아니다.

발행은 여기서 하지 않는다. 워커가 event 를 직접 내보내면 seq(모든 topic 이 공유하는
단조증가 번호)가 발행 순서와 어긋나 서버가 재생 공격으로 보고 조용히 버린다 (§3.5).
그래서 워커는 image_id 만 만들어 돌려주고, 발행은 메인 루프가 한다.
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from pathlib import Path

from comms.codec import utc_now
from comms.uploader import upload as upload_image

log = logging.getLogger("robot.evidence")

#: 워커를 세울 때 기다리는 시간. 진행 중인 업로드 1건(요청 타임아웃 10초)을
#: 넘기지 않는다 — 종료가 그보다 오래 걸리면 systemd 가 SIGKILL 로 끊는다.
SHUTDOWN_WAIT_SEC = 3.0

#: 증거 이미지 보존 기한. Outbox 의 SENT_RETENTION_DAYS 와 같은 값·같은 이유다.
#: 업로드가 끝난 사진은 서버(storage.robot_image)에 있고, 실패한 사진은 재시도
#: 경로가 없다 — uploader 는 4xx 에서 포기한다. 결함 하나당 최대 500KB 라 정리
#: 경로가 없으면 젯슨 eMMC 가 조용히 찬다.
EVIDENCE_RETENTION_DAYS = 7

#: 이 확장자만 지운다. 증거 디렉터리에 다른 것이 섞여도 건드리지 않는다.
_EVIDENCE_SUFFIXES = (".jpg", ".jpeg")


def prune_evidence(directory, retention_days: int = EVIDENCE_RETENTION_DAYS) -> int:
    """보존 기한이 지난 증거 JPEG 을 지운다. 지운 개수를 돌려준다.

    기동할 때 한 번 부른다 — Outbox.prune_sent 와 같은 자리·같은 주기다. 주행 중에
    돌리지 않는 이유는 디렉터리 순회가 검사 정차 시간을 잡아먹을 이유가 없어서다.

    파일 하나가 안 지워져도(권한·사용 중) 나머지는 계속 지운다. 정리 실패로 기동을
    막지 않는다 — 디스크가 찬 것보다 안 뜨는 로봇이 나쁘다.
    """
    root = Path(directory)
    if not root.is_dir():
        return 0
    cutoff = time.time() - retention_days * 86400.0
    removed = 0
    for path in root.iterdir():
        if path.suffix.lower() not in _EVIDENCE_SUFFIXES or not path.is_file():
            continue
        try:
            if path.stat().st_mtime >= cutoff:
                continue
            path.unlink()
            removed += 1
        except OSError:
            log.warning("증거 이미지를 지우지 못했다: %s", path, exc_info=True)
    if removed:
        log.info("증거 이미지 %d장 정리 (보존 %d일)", removed, retention_days)
    return removed


class EvidenceWriter:
    """캡처 프레임 → 계약 §8 규격의 JPEG 파일.

    장변 축소 + 품질 조절로 크기 상한을 지킨다. 상한을 못 맞추면 None 을 돌려주고,
    그러면 결함 event 는 image_id: null 로 그대로 나간다 — 사진 때문에 결함을
    삼키지 않는다 (§8.3).
    """

    def __init__(self, directory, *, long_edge_px: int, quality: int,
                 max_bytes: int, min_quality: int, now=utc_now):
        self._directory = Path(directory)
        self._long_edge_px = int(long_edge_px)
        self._quality = int(quality)
        self._max_bytes = int(max_bytes)
        self._min_quality = int(min_quality)
        self._now = now

    def write(self, image):
        """저장하고 경로를 돌려준다. 실패하면 None (예외를 올리지 않는다)."""
        try:
            return self._write(image)
        except Exception:
            log.warning("증거 이미지 저장 실패 — image_id 없이 결함만 보고한다",
                        exc_info=True)
            return None

    def _write(self, image):
        import cv2   # 보드 전용 — 개발 PC(mock 경로)는 이 클래스를 만들지 않는다

        blob = None
        for quality in range(self._quality, self._min_quality - 1, -10):
            ok, buffer = cv2.imencode(
                ".jpg", _fit(cv2, image, self._long_edge_px),
                [int(cv2.IMWRITE_JPEG_QUALITY), quality])
            if not ok:
                return None
            blob = buffer.tobytes()
            if len(blob) <= self._max_bytes:
                break
        else:
            log.warning("증거 이미지가 품질 %d 에서도 %d 바이트 상한을 넘는다 (%d) — 저장 생략",
                        self._min_quality, self._max_bytes, len(blob or b""))
            return None
        self._directory.mkdir(parents=True, exist_ok=True)
        # 파일명은 캡처 시각이다 — event_id 로 이름 짓지 않는 이유는 없지만, 사람이
        # 디렉터리를 훑을 때 시간순이 훨씬 쓸모 있다.
        path = self._directory / f"{self._now().replace(':', '').replace('.', '_')}.jpg"
        path.write_bytes(blob)
        return path


def _fit(cv2, image, long_edge_px: int):
    """장변이 상한을 넘으면 비율을 유지한 채 줄인다 (계약 §8: 장변 1280px)."""
    height, width = image.shape[0], image.shape[1]
    longest = max(height, width)
    if longest <= long_edge_px:
        return image
    scale = long_edge_px / longest
    return cv2.resize(image, (max(1, int(width * scale)), max(1, int(height * scale))),
                      interpolation=cv2.INTER_AREA)


class EvidenceUploader:
    """증거 사진을 워커 스레드 하나로 순서대로 올린다.

    submit() 은 즉시 돌아온다 — 그 순간부터 로봇은 다시 달리고 다음 블록을 찾는다.
    올리기가 끝난 건은 drain() 으로 메인 루프가 걷어가 그때 발행한다.

    워커는 하나다: 여러 개면 업로드가 뒤섞여 발행 순서(=seq)가 관측 순서와 달라진다.
    """

    def __init__(self, robot_uuid, *, upload=upload_image):
        #: 이미지 업로드 form 의 robot_id 에 실리는 DB 내부 UUID (robot.robot_id).
        #: mqtt_id 가 아니다 — 서버가 UUID 로 파싱하므로 mqtt_id 를 보내면 400 이다.
        self._robot_uuid = robot_uuid
        self._upload = upload
        self._pending = queue.Queue()
        self._done = queue.Queue()
        self._worker = threading.Thread(target=self._run, name="evidence-upload",
                                        daemon=True)
        self._worker.start()

    def submit(self, observation, image_path) -> None:
        """관측 1건의 업로드를 맡긴다. 기다리지 않는다."""
        self._pending.put((observation, image_path))

    def drain(self) -> list:
        """업로드가 끝난 [(observation, image_id), …]. 없으면 빈 목록."""
        finished = []
        while True:
            try:
                finished.append(self._done.get_nowait())
            except queue.Empty:
                return finished

    def close(self, timeout: float = SHUTDOWN_WAIT_SEC) -> list:
        """워커를 세우고 남은 결과를 돌려준다. 호출부가 그걸 발행해야 한다.

        종료 신호는 큐 맨 뒤에 붙으므로 밀려 있던 업로드는 그 전에 끝난다.
        다만 진행 중인 요청이 `timeout` 을 넘기면 그 뒤는 걷지 못하고 유실된다
        (사진 파일은 디스크에 남는다) — 종료를 무한정 붙잡는 것보다 낫다.
        """
        self._pending.put(None)          # 종료 신호
        self._worker.join(timeout=timeout)
        if self._worker.is_alive():
            log.warning("증거 업로드가 %.0f초 안에 끝나지 않았다 — 남은 건은 발행되지 않는다",
                        timeout)
        return self.drain()

    def _run(self) -> None:
        while True:
            item = self._pending.get()
            if item is None:
                return
            observation, image_path = item
            image_id = None
            try:
                if image_path is not None:
                    # 서버는 (event_id, robot_id) 로 사진을 event 에 묶는다 (§7.2) —
                    # 그래서 event_id 가 발행보다 먼저 정해져 있어야 한다.
                    image_id = self._upload(image_path, observation.event_id,
                                            self._robot_uuid,
                                            captured_at=observation.captured_at)
            except Exception:
                # upload() 는 이미 예외를 삼키지만, 여기서 스레드가 죽으면 이후 결함이
                # 전부 발행되지 않는다 — 워커는 어떤 이유로도 멈추지 않는다.
                log.exception("증거 업로드가 예외로 끝났다 (event_id=%s)",
                              observation.event_id)
            self._done.put((observation, image_id))
