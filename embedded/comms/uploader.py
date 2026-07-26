"""증거 이미지 HTTP 업로드 — POST /api/v1/images.  [F-006]

⚠️ 구현 기준은 「MQTT 통신 스웨거 명세서」(신버전) §12. 구버전(image_url 방식) 참조 금지.
"""

# TODO upload(image_path, event_id, robot_id) -> image_id | None
#   - multipart/form-data: file(JPEG ≤500KB), event_id, robot_id
#   - 헤더: X-API-Key (세션 아님)
#   - timeout 10초, 재시도 3회 (간격 1→2→4초)
#   - 응답 201(신규) / 200(중복 event_id) 모두 body 의 image_id 사용
#   - 3회 실패 → None 반환 → incident 는 image_id: null 로 발행 (이벤트를 막지 말 것)
