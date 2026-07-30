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
- **S3를 기다리지 않는다.** A의 AWS·S3 작업은 주말(8/1~8/2) 예정이다.
  이미지 저장은 처음부터 `ObjectStorageClient` 포트 뒤에 두고 **로컬 디스크 어댑터로 D1에 완결**시킨 뒤,
  A의 버킷이 나오면 어댑터 한 개만 교체한다. S3는 선행 작업이 아니라 **교체 작업**이다. (§6 참조)
- **D2–D3은 프론트에 통으로 투입.** 백엔드가 이미 계약을 다 갖고 있으므로 프론트는 소비만 하면 된다.
- **D4(일)는 예비일로 비워 둔다.** 7일 계획에서 유일한 완충이며, 앞이 밀리면 여기서 흡수한다.
- **기존 코드 수정 범위 준수.** `robot/` 패키지와 A 소유 `SecurityConfig`는 건드리지 않는다. 이미지는 새 `com.gilbom.storage` 패키지와 자체 `SecurityFilterChain` 빈으로 흡수한다.

---

## 2. 일자별 계획

### D1 (7/30 목) — **이미지 API 완성** (쓰기 + 읽기, 로컬 디스크)

원래 이틀에 나눠 두었던 것을 하루로 당긴다. **단순 압축이 아니라 재배치다.**
S3 관련 작업(어댑터·SDK·단위테스트, 약 2시간)은 버킷이 없어 오늘 검증이 불가능하므로 주말로 밀고,
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
  - 네트워크 왕복 없이 `ObjectStorageClient`에서 직접 읽는다. S3로 전환돼도 A 쪽 코드는 그대로다

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
| AWS SDK + `S3ObjectStorageClient` + `S3PresignedDefectImageUrlIssuer` + 단위테스트 | 주말 전환 슬롯(§6.3) | 버킷이 없어 오늘 검증 불가. 오늘 만들면 검증 없는 2시간 |
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
> C의 백엔드는 S3 어댑터를 제외하면 이 시점에 완료 상태다.

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

> 주말은 A의 AWS 작업일이다. **본 작업은 프론트이고, S3 전환은 A의 인수인계가 도착한 시점에
> 끼워 넣는 1~2시간짜리 슬롯이다 (§6.3).** 도착하지 않으면 그대로 프론트를 계속한다.

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
- **결함 수정 모달**
  - 유형·심각도·위치·`zoneId`·`reviewRequired` + 수정 사유

**완료 기준**

- 지도 → 상세 → 상태 변경 → 목록·지도 즉시 반영 왕복
- 종결 상태 결함은 수정 버튼 비활성 (백엔드 `requireChangeable`와 일치)

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
- **수동 병합 UI**
  - 목록 다중 선택 → 대표 지정 → 병합(사유 필수) → 관측 합산 확인
- S3 전환이 아직이면 여기서 마무리 (§6.3)

**완료 기준**

- 앞선 D1~D3의 완료 기준이 모두 충족돼 있다 (이게 예비일의 1차 목적이다)

---

### D5 (8/3 월) — 실제 Jetson 통합 + S3 전환 마감

- **S3 전환 마감 (주말에 못 했다면 여기서 강제 종료)**
  - 월요일 오전까지 전환이 안 끝나면 **로컬 디스크로 확정하고 더 이상 손대지 않는다.**
    이후 S3 작업은 시연 리스크이므로 P2로 내린다 (§6.4)
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
  - `image_id` 없음(404) / S3 장애(503) / 권한 없음(403) 각각 다른 플레이스홀더
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

## 6. S3 전환 계획 (A의 AWS 작업이 주말인 경우)

### 6.1 왜 일정에 영향이 없는가

이미지 기능의 실제 의존 경로는 네 단계이고, **S3에 묶이는 것은 마지막 한 단계뿐이다.**

| 단계 | S3 필요 | 언제 |
| --- | --- | --- |
| 업로드 계약 확정 (임베디드 차단 해제) | 불필요 | D1 오전 |
| 업로드 엔드포인트 + API Key 인증 + `storage.robot_image` 적재 | 불필요 | D1 |
| 이미지 조회 URL 발급 + 브라우저 표시 | **로컬 디스크로 가능** | D1 |
| A의 LLM용 서버사이드 바이트 조회 | **로컬 디스크로 가능** | D1 |
| 발급기를 presigned로 교체 | 필요 | 주말 전환 슬롯 |

프론트는 `imageUrl` **문자열만** 소비하므로 저장소가 바뀌어도 프론트 코드는 변하지 않는다.
발급기가 `bucket_name`으로 어댑터를 고르기 때문에, 전환 이전에 올라간 이미지도 전환 후 그대로 열린다.

이미 확인된 사실 (A가 주말에 건드릴 필요 없음):

- `application.yml` — `multipart.max-file-size: 500KB`, `max-request-size: 1MB` (임베디드 계약 500KB와 일치)
- `frontend/nginx/default.conf` — `client_max_body_size 1m`
- `RequestBodySizeLimitFilter` — 전역 1MB
- **버킷 CORS 설정 불필요.** bbox 오버레이를 canvas가 아니라 DOM 절대배치로 그리므로 canvas taint가 없고,
  `<img src>`는 CORS 없이 로드된다

### 6.2 A에게 보낼 요청서 (그대로 복사)

```
[C → A] 주말 AWS 작업 시 결함 이미지용으로 아래 4개만 부탁드립니다.

1. S3 버킷 1개 (private, 퍼블릭 액세스 전면 차단)
2. 앱 IAM 권한: s3:PutObject, s3:GetObject — 해당 버킷의 robots/* 프리픽스로 한정
   presigned URL 서명에 쓰이므로 EC2 인스턴스 프로파일이면 충분합니다
3. env 2개: GILBOM_S3_BUCKET, GILBOM_S3_REGION
4. docker-compose에 이미지 임시 저장 볼륨 1개
   (S3 전환 전까지 로컬 디스크에 쓰므로, 볼륨이 없으면 재배포 때 시연 이미지가 사라집니다)

버킷 CORS·nginx client_max_body_size·multipart 설정은 이미 맞아서 변경 불필요합니다.
받는 즉시 C가 1~2시간 안에 붙입니다. 늦어져도 로컬 디스크로 동작하므로 일정에 영향은 없습니다.
```

### 6.3 전환 슬롯 체크리스트 (2~3시간)

A의 인수인계가 도착한 시점에 D3/D4 작업을 잠시 끊고 실행한다.
D1에서 S3 코드를 의도적으로 미뤘으므로, 이 슬롯은 **작성 + 전환**을 함께 한다.

0. 어댑터 작성 (약 2시간)
   - `build.gradle`: `software.amazon.awssdk:s3`, `:s3-presigner` (BOM으로 버전 고정)
   - `S3ObjectStorageClient` + `S3PresignedDefectImageUrlIssuer`
   - **발급기는 `bucket_name`으로 어댑터를 고른다.** `local-disk` 행은 로컬 스트리밍 URL,
     실제 버킷 행은 presigned URL. 이렇게 해야 전환 이전에 올라간 이미지가 전환 후에도 그대로 열린다
     (재업로드·데이터 마이그레이션 불필요)
1. env 주입 후 애플리케이션 기동 — S3 어댑터가 활성화되는지 로그 확인
2. 신규 업로드 1건 → `storage.robot_image.bucket_name`이 실제 버킷 이름으로 기록되는지
3. 해당 결함의 `GET /defects/{id}/image-url` → presigned URL 반환 → 브라우저에서 200
4. 만료 시간(5분) 경과 후 같은 URL → 403
5. **전환 이전에 올린 `local-disk` 이미지가 여전히 열리는지** (어댑터 분기 검증)
6. 감사 로그·에러 응답에 presigned URL이 찍히지 않는지
7. 버킷에 퍼블릭 액세스가 없는지 (직접 object URL 호출 → 403)

### 6.4 롤백과 마감

- 롤백: env 하나(`GILBOM_IMAGE_STORAGE=local`)로 로컬 디스크로 즉시 되돌린다. 코드 변경 없음
- **마감선: D5(8/3 월) 오전.** 그때까지 전환이 안 끝나면 로컬 디스크로 확정하고 S3는 P2로 내린다.
  시연 관점에서 로컬 디스크와 S3는 화면상 차이가 전혀 없다.
  단, 그 경우 6.2의 4번(볼륨 마운트)은 **반드시** 받아야 재배포 때 시연 이미지가 유실되지 않는다
