# 변경 로그: 시간 기반 Mission 운영형 Browser E2E

- 작성 시각: 2026-07-30 13:42 (Asia/Seoul)
- 작업 유형: 테스트
- 상태: 완료

## 작업 요약

`S15P11D101-489`, `DAY6-B-00-6`의 마지막 완료 기준이었던 실제 로그인
세션·실행 Backend·영속 PostgreSQL 단일 Browser E2E를 구현하고 통과시켰다.
Docker Frontend 3000에서 비밀번호 로그인과 TOTP MFA를 거쳐 45분 Mission을
생성하고 API 응답의 `plannedDurationSec=2700`과 DB 저장값을 대조했다.

테스트는 고유 임시 계정·Robot·Current State·Area·Dummy Route를 사용한다.
Mission 생성 검증 후 이력·Mission·Scope·Robot 상태·Route·Area·Robot·Session·
Audit·계정을 정리하며 cleanup 실패도 전체 명령 실패로 처리한다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `web/frontend/gilbom/playwright.live.config.ts` | 추가 | Docker Frontend 3000을 대상으로 개발 서버나 API Fixture 없이 실행하는 Chromium 설정을 추가했다. |
| `web/frontend/gilbom/e2e/mission-time-live.spec.ts` | 추가 | 실제 로그인·TOTP MFA·Mission 화면 조작·45분 생성 요청·201 응답 시간 계약을 검증한다. |
| `web/scripts/mission-time-browser-e2e.py` | 추가 | 임시 인증·권한·Robot·Area Fixture 준비, 실제 MFA 등록, Playwright 실행, PostgreSQL 대조, Append-only 이력과 감사로그를 포함한 cleanup을 자동화했다. |
| `web/frontend/gilbom/package.json` | 수정 | 운영형 검증 명령 `test:e2e:mission:live`를 추가했다. |
| `web/frontend/gilbom/e2e/mission-time.spec.ts` | 수정 | 지도 상세 로딩 상태와 생성 알림이 같은 `role=status`를 사용할 때 Strict Mode가 충돌하지 않도록 생성 알림 문구로 Selector를 좁혔다. |
| `web/docs/time-based-mission-contract.md` | 수정 | 실제 로그인·MFA→Frontend 3000→Backend→PostgreSQL 검증 경계와 재현 명령을 기록하고 상태를 완료로 변경했다. |
| `TROUBLESHOOTING/10_TimeBasedMission.md` | 수정 | 기존 90%의 원인, 전체 운영형 E2E 구성, 네 차례 실행에서 발견한 원인·해결, 최종 검증과 cleanup 결과를 기록했다. |
| `DailyLog/2026-07-30_JIRA_Day6_작업.md` | 수정 | `DAY6-B-00-6`을 `DONE (100%)`로 전환하고 실제 운영형 E2E 증거를 기록했다. |
| `DailyLog/2026-07-30_Day6_상세_구현.md` | 수정 | Mission 화면 완료 상태, 실제 Browser 단일 체인 결과와 체크리스트를 갱신했다. |
| `CHANGLOGS/2026-07-30_1342_time-mission-live-browser-e2e.md` | 추가 | 이번 변경·실패 원인·검증·후속 범위를 인계 규칙에 맞춰 기록했다. |

## 상세 내용

### 운영형 검증 흐름

```text
임시 공개 가입
→ DB 승인·MANAGER Role·Robot OWNER Scope
→ 실제 TOTP 등록
→ Chromium 비밀번호 로그인
→ Chromium TOTP MFA
→ Docker Frontend :3000
→ Nginx /api Proxy
→ Spring Backend :8080
→ Mission POST 201
→ PostgreSQL 계획시간 대조
→ 임시 데이터 cleanup
```

### 실행 중 문제와 원인

1. 첫 Docker 재빌드 호출은 명령 제한을 1초로 잘못 지정해 timeout됐다. 충분한
   제한으로 다시 실행해 최신 Backend·Frontend 이미지를 재생성했다.
2. 첫 Browser 실행은 임시 Robot에 `robot_current_state`가 없어 Robot 목록
   본문의 Inner Join에서 제외됐다. 운영 Robot 등록 서비스와 같은 Current State
   생성 불변식을 Seed에 반영했다.
3. 두 번째 Browser 실행은 HTTP 200 Browser `Response`에 `APIResponse` 전용
   `toBeOK()`를 사용해 실패했다. `status() == 200` 검증으로 수정했다.
4. 세 번째 실행은 Browser·DB 대조가 통과했지만 Mission 이력 Append-only
   Trigger 때문에 cleanup이 실패했다. Mission 이력과 Audit Trigger를 정확한
   임시 사용자 Transaction 안에서만 비활성화·복구하고 cleanup 실패가
   종료코드에 반영되도록 수정했다.
5. 기존 Fixture 회귀는 지도 상세 로딩과 생성 알림의 동일 Status Role 때문에
   Strict Selector가 충돌했다. 생성 알림 문구로 범위를 좁혀 해결했다.

실패 실행의 임시 데이터 1세트는 별도로 제거했고 최종 실행 후 잔존 계정·Robot·
Area·Mission이 모두 0건임을 확인했다.

## 검증

- 실행한 명령:
  - `docker compose up -d --build backend frontend`
  - `python web/scripts/mission-time-browser-e2e.py`
  - `npm run lint`
  - `npm run build`
  - `npm run test:e2e:mission`
  - 임시 계정·Robot·Area·Mission 잔존 건수 조회 SQL
  - `git diff --check`
- 결과:
  - 최신 Docker Frontend·Backend 이미지 빌드와 Healthy 재기동 성공
  - Docker Backend 전체 테스트 `546`, 실패 `0`, Skip `99`
  - 운영형 Chromium E2E `1 passed`
  - 실제 Mission API와 PostgreSQL에서 계획시간 `2700`초 일치
  - Frontend ESLint와 TypeScript·Vite production build 통과
  - 시간·지도 Fixture Chromium E2E `2 passed`
  - 임시 계정·Robot·Area·Mission 잔존 건수 `0|0|0|0`
  - `git diff --check` 오류 없음
- 미실행 항목과 사유:
  - 실제 Jetson·EC2 환경 검증은 로컬 Docker Browser E2E 범위를 벗어나므로
    실행하지 않았다.
  - MQTT→Command→Telemetry→Mission 완료 Event 전체 흐름은 별도
    `DAY6-B-00-8` 범위이므로 실행하지 않았다.

## 영향 및 주의사항

- 운영형 E2E는 로컬 Docker 서비스 이름 기본값
  `gilbom-db-1`, DB `gilbom`, DB Owner `gilbom_db_admin`을 사용하며 CLI 옵션으로
  변경할 수 있다.
- Append-only Trigger는 테스트 임시 데이터 cleanup Transaction 안에서만
  비활성화하고 같은 Transaction에서 즉시 복구한다.
- TOTP 재사용 방지 정책 때문에 등록에 사용한 Time Step 다음 Step까지 최대
  30초 기다릴 수 있다.
- 이번 작업은 Mission REST·DB·Browser 검증만 추가했고 MQTT Payload를 바꾸지
  않았다. `web/docs/final-mqtt-topic-schema-v2.md`와 MQTT Schema 변경은 없다.
- 다른 작업자가 수정 중인 Mission 지도·Mock Robot·전체 Mission E2E 파일은
  변경하거나 되돌리지 않았다.

## 미완료 및 후속 작업

- `S15P11D101-489`, `DAY6-B-00-6`은 완료 기준 100%로 종료한다.
- Command·MQTT·Telemetry·완료 Event까지 잇는 전체 Mission 시나리오는
  `DAY6-B-00-8`에서 계속한다.
- 실제 Jetson·EC2 배포 환경의 Origin·Secret·네트워크 조합은 Day 7 통합에서
  재확인한다.
