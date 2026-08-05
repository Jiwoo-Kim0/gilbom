# 변경 로그: 임무 취소 요청 Payload 예시

- 작성 시각: 2026-08-04 17:55 (Asia/Seoul)
- 작업 유형: 문서
- 상태: 완료

## 작업 요약

온라인 로봇 임무 취소 계약을 바로 구현·확인할 수 있도록 MQTT v2 명세에 `CANCEL_MISSION` 요청 JSON 전체 예시를 추가했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/docs/final-mqtt-topic-schema-v2.md` | 수정 | `command_id`, 만료 시각, 요청 관리자, 대상 `mission_id`, 취소 `reason`을 포함한 `CANCEL_MISSION` 요청 예시를 추가하고 뒤의 절 번호를 조정했다. |
| `CHANGLOGS/2026-08-04_1755_cancel-payload-example.md` | 추가 | 추가 문서 변경과 검증 결과를 기록했다. |

## 상세 내용

- 취소 명령은 `tbs/v2/robots/{mqtt_id}/commands/request`로 발행한다.
- `parameters.mission_id`는 DB UUID가 아니라 `mission.mission_code`를 사용한다.
- `parameters.reason`은 공백이 아닌 관리자 취소 사유다.

## 검증

- 실행한 명령:
  - `python -m json.tool web/docs/mqtt-v2/schemas/command-request.schema.json`
  - Python `jsonschema`로 정상 취소 payload 승인 및 `mission_id` 누락 payload 거절 확인
  - `git diff --check`
- 결과:
  - JSON Schema 문법 및 취소 필수 필드 검증 통과
  - 문서 diff 공백 오류 없음
- 미실행 항목과 사유:
  - 문서 예시만 추가한 후속 작업이므로 코드 테스트는 앞선 `2026-08-04_1751_cancel-mission-reconciliation.md`의 전체 회귀 결과를 사용했다.

## 영향 및 주의사항

- 예시의 UUID, 관리자 ID, 임무 코드는 형식 설명용이며 실제 발행 시 Backend 생성·조회 값으로 교체된다.

## 미완료 및 후속 작업

- 없음.
