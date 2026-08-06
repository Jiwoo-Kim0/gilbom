# Ingest Receipt 운영 정책

> 상태: Outbox 기반 설계 폐기
> 최종 결정일: 2026-08-06
> 정본: `docs/mqtt-ingest-receipt-reliability-plan.md`

## 1. 최종 결정

제품에서는 Event Outbox 기능을 제공하지 않는다.

- Robot SQLite Event Outbox를 운영하지 않는다.
- Robot은 단절 중 발생한 Event를 쌓지 않는다.
- 재연결 후 과거 Event 여러 건을 일괄 재전송하지 않는다.
- Backend도 Receipt Outbox나 Receipt 재시도 Worker를 만들지 않는다.
- `receipt_required`는 `false`로 고정하고 활성화 옵션을 제공하지 않는다.

이전 문서에서 설명했던 “Receipt를 받을 때까지 Outbox Event 재전송”과 “kill -9 후
Outbox 복구” 흐름은 폐기됐으며 구현 목표가 아니다.

## 2. Robot 동작

1. MQTT 연결 중 발생한 Event만 QoS 1로 즉시 발행한다.
2. 연결되지 않았거나 발행에 실패한 Event는 저장하지 않는다.
3. 유실 Event의 종류·ID·발생 시각·유실 원인을 로그와 Metric에 남긴다.
4. 재연결 후 `Presence -> State -> Telemetry` 최신 Snapshot만 발행한다.
5. 과거 Mission·Incident·Safety·Battery Event는 재생성하거나 재전송하지 않는다.

Mission 활성 상태와 처리한 `command_id` 영속화는 안전 복구와 중복 명령 방지 상태이므로
Event Outbox와 구분해 유지한다.

## 3. Receipt의 역할

Receipt 기능을 유지한다면 Backend DB 처리 결과를 관측하는 Best-effort 진단 정보로만
사용한다.

- Backend는 Event Transaction commit 후 Receipt를 한 번 발행할 수 있다.
- Receipt 발행 실패를 Queue에 넣거나 재시도하지 않는다.
- Robot은 Receipt Timeout을 이유로 Event를 재전송하지 않는다.
- Robot은 Receipt를 Outbox 삭제 조건으로 사용하지 않는다.
- 추가 필드는 무시하고 필수 필드만 검증한다.

필수 필드는 `schema_version`, `event_id`, `ingest_status`, `incident_id`,
`received_at`이다.

## 4. 알려진 제한

- PUBACK은 Broker 인수 확인이며 Backend DB commit 확인이 아니다.
- Robot과 Broker의 단절 중 발생한 Event는 복구되지 않는다.
- 완료 Event가 유실되면 Server Mission이 `RUNNING`에 남을 수 있다.
- 단절 중 결함 Event는 재연결 후 결함 Marker로 복구되지 않는다.
- 재연결 Snapshot만으로 완료·취소·실패·상태 손실을 구분할 수 없으면 Backend는
  자동 완료하지 않고 관리자 확인 상태로 유지한다.

## 5. 현재 코드와의 차이

현재 `embedded/jetson_main`에는 SQLite Outbox, `flush_outbox()`, 재연결 Drain,
Receipt 대기 분기가 구현돼 있다. 제품 정책을 코드에 반영할 때 다음을 제거한다.

- Event 발행 전 Outbox 저장
- 재연결과 주기 실행 시 Outbox Drain
- `receipt_required` 활성화 경로
- Outbox 적체 기반 Storage Health 판정
- Outbox DB Migration과 정리 작업

세부 역할, 데이터 유실 범위, 배포 순서와 시험 항목은 정본 문서를 따른다.
