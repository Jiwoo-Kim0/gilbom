# 팀원 C 남은 7일 구현 계획 (2026-07-30 ~ 2026-08-05)

담당: 김지우 — 탐지 이벤트 · 결함 · 공간 데이터 · 이미지

이 문서는 초기 12일 계획을 실제 코드베이스 진척도에 맞춰 다시 세운 것이다.
초기 계획과 실제 구현 순서가 달라졌으므로, 아래 "현재 상태 진단"이 기준이다.

---

> **D1 진행 기록 (7/30)** — 이미지 API 백엔드 구현 완료. 아래 §0 진단 중 일부가 이미 해소됐다.
>
> - 완료: `V31` API Key 스키마, `com.gilbom.storage` 패키지(업로드·조회·서명 URL·LLM용 바이트 조회),
>   `UnavailableDefectImageUrlIssuer` 삭제, `scripts/issue-robot-image-api-key.py`
> - **위험도 산정은 이미 끝나 있었다.** `V28__harden_incident_ingestion` + `DefectRiskAssessment`
>   (커밋 `77085f7`)로 구현돼 있으므로 §3의 P1에서 제외한다.
> - **마이그레이션 번호가 밀렸다.** V28~V30을 다른 작업이 이미 사용해 이 작업은 **V31**을 쓴다.
>   문서 곳곳의 "V28"은 V31로 읽는다.
> - **팀원 A가 보고서를 빠르게 진행 중이다.** 커밋 `d7507e5`(계약·오류코드·openapi),
>   `46d2660`(보고서 기본 API·프론트 UI). §6의 보고서 이관 검토는 사실상 불필요해졌다.
> - `ErrorCode`에 `IMAGE_NOT_FOUND` / `IMAGE_TOO_LARGE` / `IMAGE_UNSUPPORTED_MEDIA_TYPE` /
>   `S3_UNAVAILABLE`이 이미 있어 **A 소유 파일을 하나도 고치지 않았다.**

## 0. 현재 상태 진단 (2026-07-30 실측)

### 0.1 이미 끝난 것

| 영역 | 상태 | 근거 |
| --- | --- | --- |
| 결함 스키마 | 완료 | `V12`(defect / defect_observation / defect_status_history / defect_merge_history), `V19`(export_job·robot FK), `V23`, `V27`(제약 VALIDATE) |
| 탐지 이벤트 수신 | 완료 | `IncidentEventIngestionService` — eventId 멱등, mission·zone 검증, 이미지 링크, 관측 INSERT |
| PostGIS 자동 병합 | 완료 | `findMergeCandidate()` — 동일 유형·블록·구역 + `ST_DWithin` 2m, `FOR UPDATE` |
| 결함 조회 API | 완료 | `DefectController` 11개 엔드포인트 (summary / map(GeoJSON) / list / detail / observations / history / image-url / merge / export / PATCH×2) |
| 상태 전이·감사 | 완료 | `DefectWorkflow` 전이표 + `defect_status_history` + `audit_log` 동일 트랜잭션 |
| 권한 범위 | 완료 | `DefectScopeService` / `DefectAccessService` (로봇 배정 기반 가시 범위) |
| 결함 SSE | 완료 | `DefectSseService`, `scripts/verify-defect-sse.py` 계약 자동 검증 |
| 결함 통계 공급 | 완료 | `statistics` 모듈이 defect 테이블 집계 (A 보고서용 공급 완료) |

### 0.2 아직 없는 것 — 남은 7일의 실체

1. **이미지 파이프라인 전무 (최대 리스크)**
   - `UnavailableDefectImageUrlIssuer`가 `Optional.empty()` 반환 → `GET /image-url`은 항상 503
   - `POST /api/v1/images` 엔드포인트 자체가 없음. `build.gradle`에 AWS SDK 없음. 로봇 `X-API-Key` 인증 수단 없음
   - **임베디드는 이미 클라이언트를 완성해 두었다.** `embedded/comms/uploader.py`가 `POST /api/v1/images`로 multipart를 보내고 응답 body에서 `image_id`를 꺼내려 하며, 코드 주석에 "openapi-v1.yaml에 없다 — 필드명은 추정치다. 서버팀 확인 후 이 세 줄만 고치면 된다"라고 적혀 있다.
   - 지금 상태로 Jetson을 붙이면 `image_id`가 영구히 `null`이고 결함 상세에 이미지가 뜨지 않는다.

2. **프론트엔드가 전부 목업**
   - `DefectList.tsx`(35줄)은 "API 연결 후 표시합니다" 빈 상태
   - `DefectMap.tsx`는 마커 0개짜리 네이버 지도
   - `DefectFilter.tsx`의 상태값이 `진행 중 / 접수됨 / 완료`로, 백엔드 enum(`NEW / ACCEPTED / IN_PROGRESS / RESOLVED / FALSE_POSITIVE / REJECTED`)과 어휘 자체가 다름
   - `defectApi.ts`·`defectTypes.ts` 없음, 상세 화면 없음, 상태 변경 UI 없음, bbox 뷰어 없음, SSE 구독 없음, `permissions.ts`에 `DEFECT_*` 없음

3. **계약 문서 공백**
   - `openapi-v1.yaml`에 `/api/v1/defects/*`, `/api/v1/images`, `/api/v1/stream/defects`가 한 줄도 없음 (robots·statistics·reports는 모두 있음)

4. **미구현 소품**
   - 위험도 산정 없음: `risk_level`은 항상 기본값 `MEDIUM`, `risk_score`는 NULL
   - Export worker 없음: 202 접수 응답만 하고 파일 생성 없음
   - 병합 취소(unmerge) 없음

**결론: 백엔드 결함 도메인은 사실상 완성. 남은 7일은 "이미지 파이프라인 + 프론트엔드 전부 + 실기기 통합"이다.**

---

## 1. 계획 원칙

- **이미지 API를 D1 하루에 끝낸다.** 두 팀이 동시에 막혀 있다.
  - 임베디드: `embedded/comms/uploader.py`가 이미 완성돼 서버만 기다린다
  - **팀원 A: LLM 보고서가 결함 이미지를 필요로 한다** → A는 브라우저가 아니라 **서버사이드 소비자**이므로
    별도의 내부 조회 경로가 필요하다 (D1 §서버사이드 소비자)
  - 여기가 팀 전체의 병목이므로, 쓰기·읽기 경로를 **같은 날 함께** 끝낸다
- **S3를 쓰지 않는다** (8/1 팀 결정). 저장소는 로컬 디스크로 확정이다.
  이미지 저장은 처음부터 `ObjectStorageClient` 포트 뒤에 두고 **로컬 디스크 어댑터로 D1에 완결**시킨 뒤,
  D1에 로컬 디스크로 완결시켜 둔 덕에 이 결정으로 새로 구현할 것은 없다. (§6 참조)
- **D2–D3은 프론트에 통으로 투입.** 백엔드가 이미 계약을 다 갖고 있으므로 프론트는 소비만 하면 된다.
- **D4(일)는 예비일로 비워 둔다.** 7일 계획에서 유일한 완충이며, 앞이 밀리면 여기서 흡수한다.
- **기존 코드 수정 범위 준수.** `robot/` 패키지와 A 소유 `SecurityConfig`는 건드리지 않는다. 이미지는 새 `com.gilbom.storage` 패키지와 자체 `SecurityFilterChain` 빈으로 흡수한다.

---

## 2. 일자별 계획

### D1 (7/30 목) — **이미지 API 완성** (쓰기 + 읽기, 로컬 디스크)

원래 이틀에 나눠 두었던 것을 하루로 당긴다. **단순 압축이 아니라 재배치다.**
S3 관련 작업(어댑터·SDK·단위테스트, 약 2시간)은 8/1 결정으로 **취소됐다**(§6.1). 당시에는 주말로 밀고,
대신 D2에 있던 **읽기 경로를 오늘로 당긴다.** 쓰기만 되고 읽기가 안 되면 A도 임베디드도 여전히 막혀 있다.

**오늘의 순서 (크리티컬 패스 — 1→2→4→5는 순서 의존이라 병렬 불가)**

| # | 작업 | 예상 |
| --- | --- | --- |
| 0 | 임베디드 계약 통보 + Jetson 샘플 이미지 1장 요청 (코드 0줄) | 10분 |
| 1 | `V28` — `storage.robot_image_api_key` + GRANT. 키 발급은 SQL INSERT로 시작 | 40분 |
| 2 | `RobotApiKeyAuthenticationFilter` + `ImageApiSecurityConfiguration` | 60분 |
| 3 | `ObjectStorageClient` 포트 + `LocalFileObjectStorageClient` | 30분 |
| 4 | `ImageUploadController` / `ImageUploadService` (쓰기 경로) | 90분 |
| 5 | `LocalObjectImageUrlIssuer` + `GET /images/{id}/content` (읽기 경로) | 60분 |
| 6 | LLM용 이미지 조회 (`DefectImageContentReader`, 0~3장) | 30분 |
| 7 | curl 8케이스 + `backend-test.sh` 회귀 | 45분 |

순수 작업 약 6시간.

**오전 30분 안에 처리 (다른 팀 차단 해제용)**

- 임베디드팀에 계약 확정 통보. `uploader.py`의 추정 3줄을 확정시킨다.

  ```
  POST /api/v1/images   Content-Type: multipart/form-data
  Header: X-API-Key: <robot key>
  form:   file(image/jpeg, <= 512000 bytes), event_id(uuid), robot_id(uuid)

  201 {"imageId":"<uuid>","eventId":"...","objectKey":"..."}   신규
  200 동일 body                                                 멱등 재업로드
  400 / 401 / 403 / 409 / 413 / 415 / 429
  ```

  → `FIELD_FILE="file"`, `FIELD_EVENT_ID="event_id"`, `FIELD_ROBOT_ID="robot_id"`,
  `IMAGE_ID_KEYS`에 `imageId` 확정. **이후 변경 금지.**

- **같은 메시지에서 Jetson 실제 촬영 샘플 JPEG 1장을 요청한다.** 오늘 일정을 깰 수 있는 1순위 리스크가
  `ImageIO`의 크기 추출 실패이고(§D1 리스크), 5분이면 확인된다.
- A에게 두 가지를 알린다.
  1. **오늘 저녁이면 이미지 API가 열린다** — LLM 보고서 작업을 오늘 밤부터 붙일 수 있다 (아래 서버사이드 소비자 절)
  2. 주말 AWS 작업 목록에 §6.2 요청서를 넣어달라고 요청.
     C는 기다리지 않고 로컬 디스크로 진행하므로 일정 영향은 0이다
- B에게 확인: 시연용 `patrol_area`가 ACTIVE로 등록돼 있는지.
  `IncidentEventIngestionService`는 `location.valid && zoneId != null`이 아니면 결함을 만들지 않고 넘어간다.
  **시연에서 결함이 0건 뜨는 사고의 1순위 원인이다.**

**쓰기 경로 (#1~#4)**

- `V28__create_robot_image_api_key.sql`
  - `storage.robot_image_api_key(robot_id, key_hash, key_prefix, status, expires_at, last_used_at)` + GRANT
  - 기존 `storage.robot_image`는 V22에 이미 존재하므로 그대로 사용
  - **키 해시는 SHA-256으로 충분하다.** API Key는 서버가 만드는 고엔트로피 난수라
    사용자 비밀번호용 Argon2가 필요 없고, 요청마다 도는 경로라 느리면 안 된다
  - **키 발급 관리 API는 오늘 만들지 않는다.** 운영자가 SQL로 INSERT한다 (P2)
- 신규 패키지 `com.gilbom.storage`
  - `RobotApiKeyAuthenticationFilter` — 해시 대조, 상수시간 비교, 실패 401
  - `ImageApiSecurityConfiguration` — `securityMatcher("/api/v1/images/**")`, 최우선 `@Order`, CSRF 비활성, STATELESS.
    **A의 `SecurityConfig`는 열지 않는다.**
  - `ObjectStorageClient` 포트 + `LocalFileObjectStorageClient`
  - `ImageUploadController` / `ImageUploadService`
    - JPEG 매직바이트 검증, 512KB 제한, SHA-256 계산
    - `ImageIO`로 width/height 추출
    - object key 규칙: `robots/{robotId}/{yyyy}/{MM}/{dd}/{imageId}.jpg`
    - API Key의 로봇과 body `robot_id` 일치 검증
    - `(event_id)` 부분 유니크로 멱등 처리
    - `bucket_name`에는 `local-disk`를 기록한다
      (V22의 `ck_robot_image_bucket`이 공백을 금지하므로 값이 반드시 필요하다)

**읽기 경로 (#5) — 원래 D2였으나 오늘로 당김**

- `LocalObjectImageUrlIssuer` — 만료 5분짜리 HMAC 서명 토큰이 붙은 URL 발급
- `GET /api/v1/images/{imageId}/content?token=...` — 서명 검증 후 JPEG 스트리밍
  (`<img src>`로 바로 물릴 수 있어야 한다. 만료·위변조 토큰은 403)
- **`UnavailableDefectImageUrlIssuer` 삭제** → 이 시점부터 `GET /defects/{id}/image-url`이 503을 멈추고 실제 URL을 반환

**LLM용 이미지 조회 (#6) — 팀원 A의 보고서 생성용**

이미지 소비자는 **딱 둘**이고, 어댑터는 하나이며 진입점만 둘이다.

| 소비자 | 필요한 것 | 경로 |
| --- | --- | --- |
| 프론트 결함 상세 `<img>` | **URL** | `GET /defects/{id}/image-url` → HMAC 토큰 URL → 브라우저가 `/images/{id}/content` 호출 (#5) |
| LLM 보고서 생성 | **바이트** | `DefectImageContentReader` → `ObjectStorageClient`에서 직접 읽어 base64 (#6) |

LLM 호출은 서버 안에서 일어난다(A 스펙의 `GILBOM_LLM_API_KEY`, outbound allowlist `gms.ssafy.io:443`).
따라서 #5의 HMAC URL을 A에게 주면 자기 서버가 URL을 다시 HTTP로 되받아오는 왕복이 되고,
로컬 디스크 단계에서는 외부 접근이 불가해 아예 동작하지 않는다. **바이트 직접 주입으로 확정한다.**

- `DefectImageContentReader` (Spring 빈)
  - `readTopDefectImages(period, maxImages)` → `List<ImageContent>` (**0~3개 가변 리스트**)
  - `ImageContent` = `{ defectId, incidentCode, bytes, contentType, width, height }`
    - **`defectId`·`incidentCode`를 함께 넣는다.** 여러 장을 보낼 때 어느 이미지가 어느 결함인지
      LLM이 알아야 프롬프트에서 헷갈리지 않는다
  - 네트워크 왕복 없이 `ObjectStorageClient`에서 직접 읽는다. 저장소가 바뀌어도 A 쪽 코드는 그대로다

**이미지 장수 계약 (오늘 확정, 나중에 바꾸면 비싸다)**

| 항목 | 결정 |
| --- | --- |
| 개수 | **0~3개 가변 리스트.** 고정 슬롯 + null 금지 |
| **합의값** | **A와 합의한 실제 사용량은 1장.** `maxImages` 기본값 = 1, 상한 = 3. 용량만 열어두고 동작은 합의대로 |
| 단위 | **보고서당** 최대 3장 (결함당 3장이 아니다 — 결함 20건이면 60장·40MB로 터진다) |
| 선정 | 대표 결함 상위 3건 × 각 대표 관측 이미지 1장 |
| 상한 강제 | **서버가 자른다.** 클라이언트 선의에 맡기지 않는다 |
| 이미지 없음 | 빈 리스트. A는 이미지 없이 텍스트만으로 생성 |

- 고정 슬롯 + null을 쓰지 않는 이유: 결함 대부분은 관측이 1건이라 **null이 예외가 아니라 기본값**이 되고,
  빈 image content block을 그대로 실어 보내면 LLM provider가 400을 낸다. 리스트면 그 실수가 불가능하다
- 실제 몇 장을 쓸지는 **A가 `maxImages`로 정한다.** 토큰 비용·프롬프트 복잡도는 A쪽 부담이므로
  1장으로 시작했다가 늘릴 수 있게 열어둔다. C 입장에서는 어느 쪽이든 같은 코드다
- 결함당 대표 이미지 선정은 새로 만들 것이 없다. `DefectQueryService`의 후보 쿼리
  (`ORDER BY o.is_representative DESC, o.confidence DESC, o.created_at DESC`)를 재사용한다

**비식별화 검증 (LLM이 이미지를 쓰면서 새로 생긴 관문)**

지금까지 이미지는 관리자 브라우저에만 보여줬지만, 이제 **외부 LLM 서비스로 나간다.**

- `image_status = 'AVAILABLE'` 확인에 더해, 임베디드가 비식별화를 마친 이미지인지 보장하는 지점이 필요하다
- **임베디드팀에 오늘 함께 확인한다** (계약 통보 메시지에 포함)
- 참고: 리포지터리의 Presigned URL 금지 규정(`api-contract.md:79`,
  `audit-and-sensitive-data-policy.md:33·150·175`)은 **로그·감사·응답 반사 금지**이지
  LLM 입력 금지가 아니다. LLM 입력에 이미지를 넣는 것을 막는 리포지터리 규칙은 없다

**오늘 빼는 것 (전부 비크리티컬 — 미루는 곳 명시)**

| 항목 | 언제 | 이유 |
| --- | --- | --- |
| AWS SDK + `S3ObjectStorageClient` + `S3PresignedDefectImageUrlIssuer` + 단위테스트 | ~~주말 전환 슬롯~~ **취소** | 8/1 팀 결정으로 S3를 쓰지 않는다 (§6.1) |
| `openapi-v1.yaml` 문서화 | D2 오전 | 임베디드에는 오전 메시지가 계약 |
| 감사 마스킹 테스트, `linkAvailableImage` 통합테스트, `IMAGE_NOT_FOUND` 확인 | D2 오전 | API 동작에 영향 없음 |
| 키 발급 관리 API·화면 | P2(버림) | SQL INSERT로 충분 |

**오늘 일정을 깰 수 있는 두 가지**

1. **`ImageIO`의 JPEG 크기 추출 실패.** `storage.robot_image.image_width/height`가 NOT NULL이라
   실패하면 업로드가 통째로 막힌다. → **오전에 Jetson 실제 샘플 1장으로 먼저 검증한다(5분).**
   실패하면 계약에 `image_width`/`image_height` 선택 필드를 추가해 로봇이 함께 보내게 한다
   (계약을 오늘 확정하므로 지금이 아니면 넣을 수 없다)
2. **새 `SecurityFilterChain`이 A의 기존 체인과 충돌.** `ApiPreAuthorizationContractTest`,
   `SecurityConfigTest`가 깨질 수 있다. → `securityMatcher`로 격리하고 #7 회귀 테스트에서 확인

**완료 기준 (프론트가 아직 없으므로 curl + DB로 판정)**

1. 신규 업로드 → 201 + `storage.robot_image` row 1건
2. 동일 요청 재전송 → 200 + **동일 imageId**
3. 501KB → 413 / PNG → 415 / 잘못된 키 → 401 / 다른 `robot_id` → 403
4. incident 이벤트 수신 → `defect_observation.image_id`가 연결됨
5. `GET /defects/{id}/image-url` → **503을 멈추고** 200 + URL + `expiresAt`
6. 그 URL을 브라우저에 붙여넣으면 이미지가 보이고, 5분 후 403
7. `DefectImageContentReader`로 바이트를 읽는 테스트 1건 통과 (A 인수인계용)
8. `scripts/backend-test.sh` 그린

---

### D2 (7/31 금) — D1 잔여 마감 (오전) + 프론트 데이터 레이어 · 목록/필터 (오후)

> D1에서 이미지 API가 끝났으므로 **여기부터는 전부 프론트**다.
> C의 백엔드는 이 시점에 완료 상태다. (S3 어댑터는 8/1 결정으로 취소)

**오전 — D1에서 미룬 것 마감 (2시간)**

- `openapi-v1.yaml`에 `/api/v1/images` + `/api/v1/defects/*` + `/api/v1/stream/defects` 경로 블록 append
  (A 작업과 충돌하지 않게 블록 추가만)
- `OperationalErrorSafetyTest`에 케이스 추가 — 발급 URL이 감사 로그·에러 body로 새지 않는지
  (`AuditSnapshot`이 이미 `presignedurl` 키를 마스킹하므로 회귀 방지용)
- `IncidentEventIngestionService.linkAvailableImage()` 통합 테스트
  — 실제 순서(이미지 선업로드 → incident 수신)로 `defect_observation.image_id`가 연결되는지
- 이미지 없는 결함(`image_id NULL`)의 `IMAGE_NOT_FOUND` 404 계약 확인

**오후 — 프론트 데이터 레이어 + 목록/필터**

- `features/defect/defectTypes.ts` — `DefectDtos.java`와 1:1
  (`workflowStatus`, `riskLevel`, `bboxNorm`, `observationCount`, `latestConfidence` … 필드명 그대로)
- `features/defect/defectApi.ts` — 기존 `shared/api/apiClient.ts`(CSRF·401 처리 내장) 위에 구현
  - `list / map / detail / observations / history / imageUrl / summary / updateStatus / updateDefect / merge`
- `permissions.ts`에 `DEFECT_STATUS_VIEW / DEFECT_STATUS_UPDATE / DEFECT_MERGE / DEFECT_EXPORT` 추가 → 버튼 노출 제어
- **`DefectFilter.tsx` 전면 재작성**
  - 상태 6종(백엔드 enum 그대로), 유형, 심각도, `minConfidence` 슬라이더, 기간, 구역·경로·로봇 셀렉트
  - 필터 상태를 URL query와 동기화 (공유 가능한 링크 = 시연에 유리)
- **`DefectList.tsx` 실 데이터화**
  - 페이지네이션(`page`/`size`), 정렬(`DefectFilter.java`의 허용 sort 키만 노출)
  - 반복 관측 횟수·최신 신뢰도 컬럼
  - 로딩 / 빈 / 에러 3종 상태
- 목록/지도의 `representativeImageUrl`은 서버에서 하드코딩 `null`이다.
  20건마다 발급 20번은 비싸므로 **상세에서만 발급하는 현재 구조를 유지**하고, 목록에는 썸네일을 넣지 않는다.
  꼭 필요해지면 D6 여유분에서 배치 발급으로 처리한다

**완료 기준**

- 로그인 후 `/defects`에서 실제 DB 결함이 필터·정렬·페이지 이동과 함께 표시된다
- 권한 없는 계정은 403이 아니라 빈 목록으로 보인다

---

### D3 (8/1 토) — 지도 + 상세 + 상태 변경 (C의 화면 핵심)

> 주말은 A의 AWS 작업일이었으나, **8/1에 팀이 S3를 쓰지 않기로 정해 끼워 넣을 전환 슬롯이
> 없어졌다 (§6.1).** 본 작업은 프론트이고, 중단 없이 하루를 다 쓴다.

- **지도**
  - `GET /defects/map` GeoJSON 소비
  - 지도 `idle` 이벤트 debounce → `bbox` 파라미터로 재조회
  - 유형별 아이콘 × 상태별 색, 마커 클러스터링
  - 마커 클릭 → 요약 팝업 → 상세 이동
  - 목록과 지도가 같은 필터를 공유 (D2의 URL query 재사용)
- **상세 화면 `/defects/:defectId`**
  - 이미지 + **bbox 오버레이** (`bboxNorm` 정규좌표 → 컨테이너 비율 기반 절대배치, `imageWidth`/`imageHeight`로 종횡비 보정)
  - AI 신뢰도, 모델명·버전, 유형·심각도·위치·정확도, 촬영 시각, 로봇·임무 정보
  - 관측 이력 탭(`/observations`), 변경 이력 탭(`/history`, before/after diff)
  - 병합 정보(`mergedDefectIds`, `mergedIntoDefectId`)
- **상태 변경 모달**
  - `DefectWorkflow` 전이표를 프론트에 미러링해 허용된 다음 상태만 노출
  - `RESOLVED`는 `resolution` 필수, `reason` 필수(1000자)
  - 409(`DEFECT_STATE_CONFLICT`) 메시지 처리
- ~~**결함 수정 모달**~~ — **완료(8/1)**
  - 유형·심각도·`zoneId`·위치 설명·`reviewRequired` + 수정 사유
  - 좌표는 뺐다. 숫자 입력은 지도가 있는 화면에서 쓸모가 없다

**완료 기준**

- [x] 지도 → 상세 → 상태 변경 → 목록·지도 즉시 반영 왕복
- [x] 종결 상태 결함은 수정 버튼 비활성 (백엔드 `requireChangeable`와 일치)
- [x] 병합된 결함도 같은 방식으로 잠근다 (서버가 함께 막는 조건이라 맞췄다)

> **D3는 닫혔다.** 남은 것은 D4로 넘긴 수동 병합 + 병합 정보 표시(§6.7)와
> 임무 정보(B의 실제 임무 대기)뿐이다. 브라우저 최종 확인만 남아 있다.

---

### D4 (8/2 일) — **예비일** + SSE · 위험도 · 병합 UI

이미지를 하루 앞당겨 확보한 완충일이다. **앞이 밀렸으면 여기서 흡수하고, 아래는 전부 건너뛴다.**
일정이 계획대로면 아래 P1 항목을 채운다.

- **SSE 구독**
  - `EventSource('/api/v1/stream/defects')`
  - `defect.created` → 지도 마커 추가 + 토스트, `defect.updated` → 해당 행 갱신
  - 재연결 지수 백오프, 탭 비활성 시 정지
  - 백엔드와 검증 스크립트는 이미 있으므로 프론트만 작업
- ~~위험도 산정 (P1)~~ — **이미 완료.** `DefectRiskAssessment`(`DAMAGE_RATIO_V1`)가
  `damage_ratio`를 LOW/MEDIUM/HIGH/CRITICAL로 매핑하고 `risk_rule_version`까지 기록한다.
  프론트에서 심각도 필터·요약 카드가 의미를 갖는지만 확인하면 된다
- ~~**수동 병합 UI + 병합 정보 표시**~~ — **완료(8/1)**
  - 목록 다중 선택 → 대표 지정 → 병합(사유 필수) → 관측 합산 토스트
  - 상세 카드에 흡수한 쪽·흡수된 쪽 양방향 표시, 대표 결함으로 이동
  - 예정대로 한 덩어리로 만들었다 (§6.7)
  - **브라우저 왕복 확인은 남아 있다.** 로그인 세션이 필요해 자동 검증을 못 했다
- ~~S3 전환 마무리~~ 취소 (§6.1)

**완료 기준**

- 앞선 D1~D3의 완료 기준이 모두 충족돼 있다 (이게 예비일의 1차 목적이다)

---

### D5 (8/3 월) — 실제 Jetson 통합

- ~~**S3 전환 마감**~~ — 8/1 결정으로 취소. **이 반나절은 회수됐다** (§6.1)
  - 월요일 오전까지 전환이 안 끝나면 **로컬 디스크로 확정하고 더 이상 손대지 않는다.**
    대신 §6.3 볼륨 백업 스냅샷을 한 번 떠 둔다
- **실제 Jetson E2E (임베디드팀 합동, 하루 전체 확보)**
  - 이미지 업로드 → incident 발행 → 결함 생성 → 지도 표시 → 이미지 열람까지 한 줄로
  - 실패 케이스 점검
    - `zone_id` 불일치(409)
    - GPS `valid=false`(결함 미생성)
    - 업로드 실패 후 `image_id=null` 발행
    - 동일 지점 반복 탐지 시 2m 자동 병합 실제 동작
- ~~이미지 저장 볼륨 반영 재확인~~ — **D1에 완료.** A가 볼륨·환경변수를 추가한 뒤
  (커밋 `d50b597`) 기본 compose 스택에서 업로드 201/재업로드 200, 컨테이너 재생성 후
  파일 유지, 쓰기 경고 없음까지 확인했다. 실기기 이미지가 실제로 화면에 뜨는지는
  아래 Jetson E2E에서 함께 본다
- **A의 LLM 보고서와 합동 점검**
  - 실제 결함 이미지가 A의 보고서 파이프라인에 들어가는지 (D1의 `DefectImageContentReader` 경로)
  - 이미지가 없는 결함에서 A 쪽이 죽지 않는지

**완료 기준**

- Jetson 1회 주행에서 나온 결함이 별도 조작 없이 지도에 실시간으로 뜨고 이미지가 보인다

---

### D6 (8/4 화) — 예외 처리 · 성능 · 테스트 마감

- 이미지 예외 UI
  - `image_id` 없음(404) / 저장소 장애(503 `S3_UNAVAILABLE`) / 권한 없음(403) 각각 다른 플레이스홀더
  - 깨진 `<img>` 노출 금지
- 대량 결함 지도
  - 300~500건 픽스처로 클러스터링·렌더 성능 확인
  - `EXPLAIN`으로 GiST 공간 인덱스 사용 확인
- 테스트 보강
  - 상태 전이 전수
  - 병합 경계(2m 안팎)
  - 반복 탐지 누적
  - 스코프 격리(다른 로봇 담당자에게 안 보임)
  - 이미지 업로드 멱등·거부 케이스
- 프론트 `tsc -b` + `eslint` 그린, 백엔드 `scripts/backend-test.sh` 그린
- 문서 마감
  - `defect-schema.md`에 이미지·위험도 절 추가
  - `openapi-v1.yaml` 최종
  - 결함 운영 가이드 1장 (상태 전이 규칙 — 시연 진행자·평가자용)

---

### D7 (8/5 수) — 시연 데이터 · 리허설 · 프리즈

- 시연 시나리오 3종 고정
  1. 신규 탐지 실시간 표시
  2. 반복 탐지 자동 병합
  3. 접수 → 처리 중 → 완료 + 변경 이력
- 백업 경로 준비
  - Jetson 없이 돌아가는 mock incident publisher
    (현재 `tools/mock-robot`은 presence / state / telemetry / command만 발행 — incident 발행 기능 추가 필요)
  - 미리 업로드해둔 이미지 세트
- DB 시연 데이터 정리 (테스트 결함 삭제, 대표 결함 5~8건을 상태별로 분산 배치)
- 리허설 3회, 발표 구간 대본, 기능 브랜치 병합 종료 → 태그

---

## 3. 우선순위와 컷 라인

**P0 — 여기까지 못 하면 시연 실패**

이미지 업로드 API → 이미지 조회 URL 발급 → 결함 목록·필터 → 지도 → 상세 + 이미지 + bbox → 상태 변경 → 실기기 E2E

> 앞의 두 개는 C만의 P0가 아니다. **임베디드의 업로드와 A의 LLM 보고서가 함께 걸려 있어
> D1에 못 끝내면 세 사람이 동시에 밀린다.** 그래서 D1은 다른 어떤 항목보다 우선한다.

**P1 — 시간 되면**

SSE 실시간 갱신 · 위험도 산정 · 수동 병합 UI · 대량 데이터 클러스터링

**P2 — 미련 없이 버린다**

Export worker(CSV 파일 생성, 202 접수 응답만 유지) · 병합 취소 UI · 다중 이미지 갤러리 · 결함 통계 차트(A의 보고서에서 이미 소비 중)

---

## 4. 즉시 처리 4건 (D1 오전, 코드 작성 전)

1. **임베디드팀에 이미지 업로드 계약 확정 통보 + Jetson 샘플 JPEG 1장 요청**
   그쪽 코드에 "서버팀 확인 후 고친다"는 TODO가 박혀 있어, 답을 주지 않으면 D5 통합에서 필드명 불일치로 400이 난다.
   샘플 이미지는 오늘 일정의 1순위 리스크(`ImageIO` 크기 추출)를 5분 만에 없앤다.
2. **A에게 LLM 이미지 계약 통보**
   바이트 직접 주입으로 확정. **사용량은 합의대로 1장(`maxImages` 기본값 1)이되, 시그니처는
   `List<ImageContent>`(길이 1)이고 상한 3까지 열어둔다** — 이 한 줄은 반드시 A에게 알린다.
   나중에 3장으로 늘릴 때 계약을 다시 바꾸지 않기 위한 여유일 뿐, 동작은 합의 그대로다.
   A는 Day 6 §6 스펙에서 "전송 금지 필드 — Presigned URL" 항목과 허용 필드 목록을 이에 맞게 고쳐야 한다.
   상세는 D1 §LLM용 이미지 조회.
3. **A에게 §6.2 요청서 전달 (주말 작업 목록에 포함시킬 것)**
   C는 A를 기다리지 않고 진행하지만, 주말에 무엇을 만들어야 하는지는 오늘 알려줘야 한다.
   요청은 4줄이면 끝나고, **nginx·multipart 설정은 이미 맞으므로 A가 손댈 것이 없다.**
4. **B에게 `patrol_area` ACTIVE 등록 확인**
   미등록이면 incident가 들어와도 결함이 생성되지 않는다.

---

## 5. 참고 — 관련 파일 위치

| 대상 | 경로 |
| --- | --- |
| 결함 백엔드 | `web/backend/src/main/java/com/gilbom/defect/` |
| 결함 SSE | `web/backend/src/main/java/com/gilbom/defect/realtime/` |
| 결함 스키마 | `web/backend/src/main/resources/db/migration/V12`, `V19`, `V23`, `V27` |
| 이미지 스키마 | `V22__create_patrol_mission_event_and_image_schema.sql` (`storage.robot_image`) |
| 결함 프론트 | `web/frontend/gilbom/src/features/defect/` |
| 임베디드 업로더 | `embedded/comms/uploader.py` |
| SSE 검증 스크립트 | `web/scripts/verify-defect-sse.py` |
| 결함 스키마 문서 | `web/docs/defect-schema.md` |
| 권한 매트릭스 | `web/docs/authorization-matrix.md` (§279–283에 이미지 API 권한 규정) |

---

## 6. 이미지 저장소 — 로컬 디스크로 확정 (2026-08-01 결정)

**팀 결정: S3를 쓰지 않는다.** 결함 이미지는 백엔드 컨테이너의 영속 볼륨에 그대로 둔다.
아래는 그 결정으로 무엇이 없어지고 무엇이 새로 생기는지다.

### 6.1 없어진 것

| 항목 | 원래 예상 | 상태 |
| --- | --- | --- |
| AWS SDK + `S3ObjectStorageClient` + `S3PresignedDefectImageUrlIssuer` | 주말 2~3시간 | **취소** |
| A의 버킷·IAM·`GILBOM_S3_BUCKET`·`GILBOM_S3_REGION` 인수인계 | A 대기 | **취소 — 차단 해제** |
| D5(8/3) "S3 전환 마감" 슬롯 | 반나절 | **회수** — D5는 Jetson 통합에만 쓴다 |

D1에 로컬 디스크로 완결시켜 둔 판단이 그대로 결과가 됐다. 업로드·조회·LLM 바이트 조회가
모두 이미 동작하므로 **이 결정으로 새로 구현할 것은 없다.**

### 6.2 그대로 두는 것

`ObjectStorageClient` 포트와 `bucket_name` 분기는 **지운다고 얻는 게 없어서 남긴다.**

- 읽기가 `bucketName`을 받는 구조는 테스트 이음매로 계속 쓰인다
  (`ImageUploadServiceTest`가 이 포트를 스텁한다)
- `storage.robot_image.bucket_name`에는 `local-disk`만 쌓인다. 컬럼은 V22 소유라 두고,
  값이 한 종류인 것은 문제가 되지 않는다
- `ErrorCode.S3_UNAVAILABLE`은 이제 이름과 의미가 어긋난다. **A 소유 파일이라 손대지 않았다.**
  실제 뜻은 "이미지 저장소를 읽거나 쓸 수 없음"이다. 이름을 바꾼다면 A의 판단이고,
  응답 코드가 계약이라 프론트·문서까지 함께 움직여야 한다

### 6.3 새로 생긴 책임 — 백업

S3를 쓰면 딸려왔을 내구성이 이제 없다. **이건 기능이 아니라 운영 리스크라 여기 적어둔다.**

| 사건 | 이미지 | 대응 |
| --- | --- | --- |
| `docker compose down` / 컨테이너 재생성 | **유지** | 이미 named volume(`robot_images`)이다 |
| `docker compose down -v` | **전부 소실** | 시연 전 이 명령을 쓰지 않는다 |
| EC2 인스턴스·EBS 손실 | **전부 소실** | 복제본이 없다 |

DB만 백업하고 볼륨을 빼먹으면 **메타데이터는 남고 파일만 사라져** 조회가 전부
`S3_UNAVAILABLE`이 된다. 이 상태가 제일 나쁘다 — 화면에는 결함이 보이는데 사진만 안 열린다.

`storage.robot_image`와 `robot_images` 볼륨은 **항상 같이** 백업·복원해야 한다.
시연 직전 스냅샷 한 번이면 충분하다.

**스크립트로 만들어 뒀다** (8/1 완료).

```bash
scripts/backup-defect-images.sh      # 볼륨 tar + DB 덤프 + manifest
```

복원은 `scripts/RESTORE.md`. 아카이브 해시 대조와 임시 DB 복원까지 검증했다.
**시연 직전 한 번 더 뜬다.**

### 6.4 볼륨은 이미 잡혀 있다

확인 완료 — A에게 요청할 것이 없다.

- `compose.yaml:194`, `compose.prod.yaml:223` — `robot_images:/app/data/robot-images`
- 두 파일 모두 `volumes:`에 named volume으로 선언돼 있다
- `GILBOM_STORAGE_LOCAL_ROOT_DIRECTORY`가 같은 경로를 가리킨다

## 6.5 보류 항목과 사유 (2026-07-31 갱신)

의도적으로 미루거나 진행할 수 없는 것들이다. 나중에 "왜 안 했지"를 다시 조사하지 않도록
사유와 재개 조건을 함께 적는다.

### 내가 막혀 있는 것

| 항목 | 사유 | 재개 조건 | 소유 |
| --- | --- | --- | --- |
| 발급 URL 마스킹 회귀 테스트 | 대상인 `OperationalErrorSafetyTest`가 `common/web`의 **A 소유 기존 파일**이라 손대지 않았다. 대신 URL을 로그·감사에 싣는 경로가 없음을 grep으로 확인만 했다 | A와 수정 범위를 합의하거나, 감사에 URL을 싣는 코드가 실제로 생길 때 | C |
| **실환경 Incident E2E** | 통합 테스트와 개발 스택으로는 검증했지만 **실제 로봇으로는 아직**이다 | B의 실제 로봇 등록 → D5 Jetson 통합 | B → C |
| 결함 필터의 구역·로봇 선택 | 타입(`DefectFilters`)과 `defectApi`는 이미 지원하나 **UI가 없다.** 셀렉트를 채우려면 순찰구역·로봇 목록 API 호출이 필요하고, 지금은 구역 1개·로봇 1대뿐이라 화면상 의미가 없다 | 구역·로봇이 여러 개가 되는 D5 이후 (P1) | C |
| 로컬 `.env` 서명 키 | `.env`가 root 소유라 작업 환경에서 수정 불가. 기동마다 임시 키가 생성되어 재시작 시 기존 이미지 URL이 무효가 된다 | 운영은 A가 `compose.prod.yaml`에서 Docker secret으로 주입하도록 이미 반영했다(`d52cfcb`). 로컬은 각자 `.env`에 한 줄 | A |
| ~~S3 어댑터~~ | **해소(8/1)** — 팀이 S3를 쓰지 않기로 했다. 로컬 디스크로 확정이라 대기하던 A 인수인계도 없어졌다 (§6) | — | — |
| mock-robot의 incident 발행 | **대체 수단을 만들어 급한 불은 껐다** — `scripts/publish-dev-incident.py`(§6.6). mock-robot 자체 확장은 D7 백업 시나리오에서 판단 | D7 | C |

### 계획에 있었는데 빠져 있던 것 (8/1 전수 대조에서 발견)

D1~D4를 계획서와 코드로 하나씩 대조해 찾았다. **사유 없이 그냥 빠져 있던 것들**이라
여기에 남긴다.

| 항목 | 계획 | 판단 | 소유 |
| --- | --- | --- | --- |
| **재발 결함의 이전 이력 연결** | 8/1 발견 | **8/3(월) 논의.** 수리 완료(`RESOLVED`) 뒤 같은 자리에서 다시 탐지되면 이제 새 결함이 생긴다(자동 병합 제외). 그런데 새 결함이 이전 결함을 가리키지 않아 **"이 자리에서 전에도 났었다"가 화면에서 끊긴다.** 필요하면 `recurred_from_defect_id` 연결 — 마이그레이션 + 화면이 따라와야 한다 | C + 팀 |
| **유형별 마커 아이콘** | D3 | **8/3(월) 팀 논의로 미룸.** 지금은 색(상태)만 있고 모양(유형)이 없어 지도에서 유형 7종이 구분되지 않는다. 만든다면 색은 상태 그대로 두고 모양만 유형에 배정한다(SVG `path` — 축척·bbox와 같이 CSP 안전). **논의할 것: 지도에서 유형 구분이 실제로 필요한가.** 마커를 누르면 카드에, 목록에는 열로 이미 나오고, 7종을 모양으로 구분하는 건 잘 안 읽히기도 한다. 비용은 30~40분 | C + 팀 |
| ~~**마커 클러스터링**~~ | D3 | **완료(8/1).** 네이버 MarkerClustering은 별도 스크립트라 `script-src`를 열어야 해서 화면 픽셀 격자로 직접 만들었다 | — |
| SSE 지수 백오프·탭 비활성 시 정지 | D4 | **안 한다.** `EventSource`가 기본 재연결을 해주고 동작에 문제가 없다. 탭 정지는 시연에서 탭 하나만 쓰므로 의미가 없다. 다만 서버가 사용자당 3연결로 제한하므로 탭을 여러 개 열면 걸릴 수 있다 | C(P2) |
| 상세 화면 `/defects/:defectId` | D3 | **바꿨다.** 지도 위 팝업 카드로 대체했고 시연 흐름에는 그쪽이 낫다. 대신 **결함 하나를 링크로 공유할 수 없다** — 필터는 URL에 실어뒀는데 결함은 아니라 일관성이 어긋난다. 필요해지면 카드 열림 상태를 query에 넣는 것으로 충분하다 | C(P2) |

> **왜 놓쳤나.** 매일 그날 항목만 보고 넘어갔고, 완료 기준에 없는 항목은 점검에서
> 빠졌다. 완료 기준이 그날 작업을 전부 덮지 않는다는 뜻이다. **D5 이후로는 하루를
> 닫을 때 그날 절 전체를 다시 읽는다.**

### 다른 사람의 결정이 필요한 것

| 항목 | 내용 | 소유 |
| --- | --- | --- |
| ~~**Naver Maps CSP 정책**~~ | **8/1 해소 — A를 기다리지 않고 우리 쪽에서 끝냈다.** `style-src 'self'`가 인라인 속성뿐 아니라 **네이버 스타일시트 자체를 막아** 컨트롤이 여백 없이 잘렸다. 줌은 SMALL + 오른쪽 위로 줄이고, 축척은 SDK 것을 끄고 `getBounds()`로 직접 그렸다(SVG 기하 속성이라 CSP와 무관). `'unsafe-inline'`은 건드리지 않았다 | — |
| **로봇 지도 마커도 같은 문제** | `RobotMap.tsx`의 `createMarkerIcon`이 인라인 `style`을 쓴다. 로봇 마커를 아직 띄워본 적이 없어 드러나지 않았을 뿐, 지금 상태로는 **로봇 마커도 안 보인다** | **B** |
| **통신테스트 MQTT 스크립트가 계약과 어긋남** | `test/통신테스트/mqtt_tls_test.py`가 `ttl_sec` 누락, `robot_id`(→`mqtt_id`), `zone_id` 패턴 위반으로 전부 `MQTT_PAYLOAD_MALFORMED`가 된다. **임베디드에도 배포된 자산**이라 방치하면 그쪽이 헤맨다 | **B** |
| ~~단건 결함 보고서~~ | **8/1 해소.** 보고서 생성 수정 권한을 받아 C가 직접 마무리했다 (아래 별도 절) | — |
| **"반려"가 종결 상태인 것이 맞는가** | `DefectWorkflow`의 `TERMINAL_STATUSES`에 `REJECTED`가 들어 있어 반려하면 **되살릴 수 없다**(상태 변경·내용 수정 모두 차단). 시연 중 잘못 누르면 그 결함은 버려진다. 일반적으로 "반려"는 *돌려보냄 → 재검토*로 읽히는데 지금 계약은 *기각 = 종결*이다. ① 의도된 설계라면 UI 문구를 "반려(기각)"로 바꾸고 확인 모달을 붙인다(30분) ② 재작업 의도였다면 `REJECTED → NEW/ACCEPTED` 전이를 추가한다(전이표·테스트·`defect-schema.md` 함께, 1시간). **A의 보고서 상태별 통계 해석에도 영향** | 팀 합의 → C |

### 단건 결함 보고서 — 지금 막혀 있는 지점

보고서를 **결함 한 건 기준**으로 만들기로 정했다(7/31). 그런데 현재 구현은 기간 기준이라,
결함 카드에서 "리포트 생성"을 눌러도 그 결함의 정보로 만들어지지 않는다.

**증상: 이미지가 조용히 사라진다.**
`ReportLlmRequestFactory`가 `findReportImages(userId, from, to, 1)`로 *기간 내 위험도 최상위*
결함의 이미지를 가져온 뒤, `matchesEvidence()`로 근거 결함과 대조해 불일치면 제거한다.
단건 보고서에서는 근거가 그 결함 하나뿐이므로 **대개 불일치 → 이미지 없는 보고서**가 되고,
에러가 나지 않아 눈치채기 어렵다.

**8/1 해소.** "보안은 건드리지 말고 생성 자체는 수정해도 된다"는 결정이 나와 C가
직접 마무리했다. 작업 기록은
[단건-결함-보고서_작업내용.md](작업내용/단건-결함-보고서_작업내용.md).

| 필요한 일 | 소유 | 상태 |
| --- | --- | --- |
| 단건용 이미지 조회 `findReportImagesForDefect(userId, defectId)` | C | **완료** |
| `ReportEditorPage`가 `?defectId=`를 읽어 대상 결함을 잡기 | C | **완료** (기간 프리필·안내 문구 포함) |
| 근거 데이터를 그 결함 하나로 구성 | C | **완료** (`top_defects` CTE에만 필터) |
| `ReportLlmRequestFactory`에서 이미지 선택 방식 교체 | C | **완료** (근거 결함에서 직접 선택) |

**통계는 좁히지 않기로 했다.** 통계는 필터링을 돕는 값이라 단건으로 줄이면 의미가 없다.
집계는 기간 전체를 유지하고 근거 결함과 이미지만 고정한다.

**남은 확인 하나** — `GILBOM_LLM_ENABLED`와 정책 승인 플래그가 켜진 환경에서 실제로
이미지가 실려 가는지. 그 설정은 **A의 보안 영역**이라 건드리지 않았다.

**참고: LLM에 실제로 들어가는 결함 정보** (`ReportLlmInput.DefectEvidence`)
`defectId`, `incidentCode`, 결함 유형, 위험도, 처리 상태, `lastSeenAt`, `locationLabel`,
`minimalDescription` + 이미지 1장. **좌표·정확도·AI 신뢰도·모델 버전·bbox·반복 관측 횟수는
들어가지 않는다.** 단건 보고서에서 이것들이 필요하면 A의 allowlist 확장이 필요하다.

### 검증만 남은 것

| 항목 | 상태 |
| --- | --- |
| ~~결함 목록·필터·정렬·페이지 이동 브라우저 확인~~ | **7/31 확인 완료.** 지도 마커·상세 카드 포함 |
| 권한 없는 계정에 빈 목록으로 보이는지 | 로컬 계정이 둘 다 ADMIN이라 **확인 불가**. 계정이 늘어나는 D5에 확인 |

### 기록해 둘 잔여 위험

`AuditSnapshot`은 `presignedurl`로 **끝나는** 키만 마스킹한다. 지금은 발급 URL을 로그나 감사에
싣는 코드 경로가 없어서(확인 완료) 문제가 없지만, 앞으로 누군가 `imageUrl` 같은 이름으로
감사 스냅샷에 담으면 마스킹되지 않는다.

### 해소된 항목 (기록용)

- ~~`/api/v1/defects/*` 11개 openapi 경로~~ — 7/31 완료. 경로 10개·오퍼레이션 11개, 스키마 20개
- ~~compose 이미지 볼륨~~ — A가 `d50b597`에서 반영, 재검증 완료
- ~~로봇·순찰구역이 DB에 없어 아무것도 못 돌림~~ — 개발 시드로 자체 해결(§6.6)

## 6.6 개발·시연 데이터를 만드는 법

B의 로봇·순찰구역 등록을 기다리다 화면 작업이 멈추는 것을 막으려고 자체 수단을 만들었다.
볼륨을 초기화해도 **3분 안에 복구**된다. D7 시연 데이터 준비도 이 절차를 그대로 쓴다.

1. **DB 시드** — 로봇(`mqtt_id=robot-test`), 순찰구역(`A-01`, 구미 진평동),
   경로, Mission(`MIS-TEST-001`), `robot_current_state`
2. **이미지 API Key 발급** — `scripts/issue-robot-image-api-key.py --robot-id <uuid>`
3. **Incident 발행** — `scripts/publish-dev-incident.py`
   (일반 30건 + 이미지 6건 + 같은 지점 3건으로 2m 자동 병합 확인)
4. **유형·상태·위치명 분산** — SQL UPDATE

주의할 점 셋:

- `--spread-m`을 주지 않으면 **서버가 2m 반경을 같은 결함으로 병합**해 30건을 쏴도 결함이 1건이 된다
- `seq`는 `boot_id`마다 1부터다. 실행마다 새 boot_id가 생기므로 `--seq`를 올리면 `MQTT_REPLAY_REJECTED`
- 유형·상태는 SQL로 바꾸므로 **`defect_status_history`와 감사 로그가 남지 않는다.**
  상세 화면의 변경 이력이 비어 보이는 것은 정상이다

**좌표는 근사값이다.** B의 실제 순찰구역이 등록되면 `--lat/--lon`을 바꿔 다시 심어야 한다.

---

## 6.7 자동 병합은 이미 화면에 보인다 (2026-08-01 확인)

"자동 병합 결과가 화면에 안 보인다"고 판단해 작업 항목을 세웠다가, DB를 보고 **틀렸다는
것을 확인했다.** 같은 착각을 반복하지 않도록 적어둔다.

`IncidentEventIngestionService.findMergeCandidate()`는 반경 2m 안에 같은 유형·같은 블록
타입의 결함이 있으면 **새 결함 행을 만들지 않고 기존 결함에 관측을 붙인다.**

```sql
WHERE d.merged_into_defect_id IS NULL
  AND d.defect_type = ?  AND d.block_type = ?
  AND ST_DWithin(d.location::geography, ..., 2)
```

따라서 자동 병합의 결과는 **`observation_count` 증가**로 나타난다. 상세 카드의
"누적 N회"와 "관측 N" 탭이 이미 그것이다. **표시가 없는 게 아니라 형태가 다를 뿐이다.**

`merged_into_defect_id`를 세팅하는 곳은 `DefectRepository:217` 하나뿐이고 **수동 병합
API 전용**이다. 현재 DB에 이 값이 있는 행은 0건이다(관측 2회 이상 결함은 1건, 최대 3회).

**결론:** 병합 정보 표시는 수동 병합 UI 없이는 빈 화면이다. D4에서 함께 한다.
→ **8/1에 함께 완료했다.**

### 임무 정보도 같은 이유로 보류한다

`defect_observation`에 **`mission_id`가 없다.** 임무를 붙이려면 `robot_id` + 촬영 시각이
`mission.started_at ~ ended_at`에 드는지로 추론해야 하는데, 실제로 세어보니 **43건 중 0건**
이 매칭됐다. DB의 미션 1건이 `MIS-TEST-001` / `CREATED` / `started_at`이 null이기 때문이다.

재개 조건은 **실제 순찰 임무가 도는 것**이다(B 담당, D5 Jetson 통합). 그전까지는 만들어도
빈칸만 나온다. 로봇 이름은 43/43이 매칭돼 먼저 붙였다.

> 표시 기능을 세우기 전에 **그 값을 만들어내는 경로가 실제로 도는지 `count(*)`로 먼저
> 센다.** 이번에 두 건 연속으로 걸렀다.

---

