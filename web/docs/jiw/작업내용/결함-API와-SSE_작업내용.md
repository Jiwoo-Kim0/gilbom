# 결함(defect) REST API와 실시간 SSE — 작업 내용

> 담당: 지우 — 점자블록 결함 관제 백엔드
> 작업일: 2026-07-28 ~ 07-30
> 근거: `길봄_MQTT 통신_SWAGGER REST SSE_API 명세서.pdf` 21.10 Defects, 21.16 SSE,
> 22.5·22.6 예시, 23.7·23.8 DTO, 24장 SSE 이벤트 계약, 25장 권한 코드
> 최종 상태: 백엔드 테스트 **507개 통과 (실패 0, skip 0)**

---

## 0. 3분 요약

Swagger 21.10의 결함 API **11개**와 21.16의 **SSE 스트림 1개**를 구현했다.
결함 데이터는 관리자가 만들지 않고 **로봇이 MQTT로 보낸 관측에서만** 생기며, 이 API는
그것을 조회하고 행정 처리 상태를 바꾸는 역할만 한다.

마이그레이션 두 개를 추가했다.

| 버전 | 내용 | 왜 |
|---|---|---|
| **V19** | `defect_observation.robot_id` + `defect_export_job` | 결함을 로봇까지 추적해야 객체 범위(배정 로봇)를 SQL로 적용할 수 있다 |
| **V27** | V23의 `NOT VALID` 제약 7개 `VALIDATE` | 기존 행까지 무결성을 보장하고 플래너가 제약을 신뢰하게 |

**아직 안 된 것**은 7절에 정리했다. 요약하면 내보내기 worker와 이미지 URL 발급이
S3 미연동으로 막혀 있고, 세 가지는 robot 담당자(minho)에게 넘겼다.

---

## 1. 시작점 — 명세서에서 무엇을 뽑았나

PDF에 `pdftotext`가 없어서 순수 Python으로 파서를 만들어 추출했다. CID 폰트 +
`bfrange` 배열형 ToUnicode CMap을 디코딩하고, y좌표로 줄을 재구성해야 표가 읽혔다.
그렇게 뽑은 근거는 다음과 같다.

- **21.10 Defects** — 엔드포인트 11개, 각 권한 코드, query 파라미터, 주요 응답
- **21.16 SSE** — `GET /api/v1/stream/defects`, 이벤트명 `defect.created` / `defect.updated`
- **22.5** — 지도 응답이 GeoJSON `FeatureCollection`이고 좌표는 `[longitude, latitude]`
- **22.6** — 상태 변경의 효과: defect 갱신 → status_history → audit_log → **SSE 발행**
- **23.7 / 23.8** — `DefectListItem`, `DefectStatusUpdateRequest`의 필드와 enum 값
- **24장** — SSE 이벤트별 발생 조건과 payload 핵심
- **25장** — `DEFECT_STATUS_VIEW` / `_UPDATE` / `DEFECT_MERGE` / `DEFECT_EXPORT`

> **주의**: 내가 참고한 PDF는 MQTT 계약 **v1.0** 기준이다. 코드는 **v2.0**을 강제한다
> (`IncidentEventPayload`의 `@Pattern(regexp = "2\\.0")`). defect 부분은 v1/v2 필드가
> 같아 영향이 없었지만, 앞으로는 [final-mqtt-topic-schema-v2.md](../../final-mqtt-topic-schema-v2.md)를
> 정본으로 봐야 한다.

---

## 2. 구현한 엔드포인트

전부 `DefectController`(`/api/v1/defects`)에 있고, SSE만 별도 컨트롤러다.

| Method | Path | 권한 |
|---|---|---|
| GET | `/summary` | `DEFECT_STATUS_VIEW` |
| GET | `/map` | `DEFECT_STATUS_VIEW` |
| GET | `` (목록) | `DEFECT_STATUS_VIEW` |
| GET | `/{defectId}` | `DEFECT_STATUS_VIEW` |
| GET | `/{defectId}/observations` | `DEFECT_STATUS_VIEW` |
| GET | `/{defectId}/history` | `DEFECT_STATUS_VIEW` |
| GET | `/{defectId}/image-url` | `DEFECT_STATUS_VIEW` |
| PATCH | `/{defectId}` | `DEFECT_STATUS_UPDATE` |
| PATCH | `/{defectId}/status` | `DEFECT_STATUS_UPDATE` |
| POST | `/merge` | `DEFECT_MERGE` |
| POST | `/export` | `DEFECT_EXPORT` |
| GET | `/api/v1/stream/defects` | `DEFECT_STATUS_VIEW` |

변경 계열(PATCH·POST)은 `SecurityConfig`의 전역 규칙으로 `X-CSRF-TOKEN`이 필요하다.
따로 설정한 것이 아니라 기존 정책을 그대로 받는다.

### 파일 구조

```
com/gilbom/defect/
  DefectController.java              엔드포인트 11개
  DefectDtos.java                    요청·응답 record 모음 (AdminDtos 관례 따름)
  DefectFilter.java                  query 필터 정규화 + SQL 조건절 생성
  DefectQueryService.java            조회 7개
  DefectCommandService.java          변경 4개 (patch ×2, merge, export)
  DefectRepository.java              변경 경로 SQL
  DefectAccessService.java           canView / canOperate (단건 boolean 판정)
  DefectScopeService.java            범위 조건절 상수 (빈 아님)
  DefectWorkflow.java                행정 상태 전이 규칙
  DefectImageUrlIssuer.java          이미지 URL 발급 port
  UnavailableDefectImageUrlIssuer    S3 미연동 기본 구현 (항상 빈 값)
  IncidentEventIngestionService.java MQTT 수집 (minho 작성, 내가 SSE 발행만 추가)
  realtime/
    DefectSseController.java         GET /api/v1/stream/defects
    DefectSseService.java            구독 관리·발행·heartbeat
    DefectRealtimeEvent.java         이벤트 계약
    DefectSseEventResponse.java      SSE payload
    DefectRealtimeEventListener.java AFTER_COMMIT 브릿지
```

---

## 3. 설계 판단과 그 이유

**여기가 이 문서의 핵심이다.** 코드만 봐서는 왜 그렇게 했는지 알기 어려운 것들이다.

### 3.1 `robot_id`를 결함이 아니라 관측에 뒀다 (V19)

[authorization-matrix.md](../../authorization-matrix.md) 11장은 결함 API의 객체 범위를
"배정 로봇에서 파생된 결함"으로 정의한다. 그런데 **V12의 defect 스키마에는 로봇을 가리키는
칼럼이 하나도 없었다.** `telemetry.robot_event`도 당시엔 없어서 `event_id`로 역추적도
불가능했다. 즉 문서상 범위 모델을 구현할 수단이 아예 없었다.

관측(`defect_observation`)이 "어떤 로봇이 보냈는가"를 갖는 사실 단위이므로 거기에 뒀다.
결함 하나가 여러 로봇의 관측을 병합할 수 있고, 그 경우에도 관측 단위로 로봇을 추적할 수 있다.

**나중에 알게 된 것**: minho의 V23이 이 칼럼을 복합 FK의 구성 요소로 쓴다.

```sql
FOREIGN KEY (event_id, robot_id) REFERENCES telemetry.robot_event (event_id, robot_id)
FOREIGN KEY (image_id, robot_id) REFERENCES storage.robot_image (image_id, robot_id)
```

그래서 **V19는 V23보다 먼저 실행돼야 하고, 번호를 V23 뒤로 옮기면 V23이
`column robot_id does not exist`로 실패한다.** 이 파일을 한 번 V17 → V19로 옮긴 이력이
있으니 다음 사람이 또 옮기지 않도록 [defect-schema.md](../../defect-schema.md) 5.1절에 근거와
함께 적어뒀다.

덕분에 "같은 로봇이 발행한 이벤트·업로드한 이미지만 참조 가능"이라는 제약을 DB가 보장한다.
`telemetry.robot_event`가 생긴 뒤에도 `robot_id`는 지우면 안 된다.

### 3.2 범위 판정을 boolean 메서드가 아니라 SQL 조건절로 했다

리뷰에서 "SQL 문자열을 공개하지 말고 `canView()` 같은 메서드를 쓰라"는 지적을 받았다.
원칙은 맞지만 **컬렉션 조회에는 적용할 수 없다.**

1. `canView(userId, robotId)`는 `robotId`를 요구하는데 **결함에는 넘길 robotId가 없다.**
   병합되면 여러 로봇의 관측이 한 결함에 붙는다.
2. 행마다 `canView`를 부르면 범위 없이 읽고 Java에서 걸러야 하는데, 그러면
   `COUNT(*)`와 `LIMIT/OFFSET`이 전부 틀어진다. 20건 요청 → 필터 후 7건,
   `totalElements`도 부정확. 게다가 N+1이다.

그리고 이건 팀이 이미 문서로 정한 규칙이다 — `authorization-matrix.md:101`:

> 목록의 count와 page 조회, 지도, 요약 집계, 단건 조회 모두 `auth.user_robot_assignment`를
> SQL `EXISTS` 조건으로 적용한다. **전체 행을 읽은 후 Java에서 제거하지 않는다.**

**결론**: 두 방식을 용도에 따라 나눠 쓴다.

| 용도 | 방식 |
|---|---|
| 컬렉션·집계·단건 조회 질의 | `DefectScopeService`의 조건절을 SQL에 인라인 |
| SSE에서 "이 이벤트를 보내도 되나" | `DefectAccessService.canView()` boolean |

SSE는 건별 판정이라 boolean이 맞다. 그래서 리뷰어 원칙이 **여기서는 정확히 적용된다.**

이 때문에 `RobotScopeService`의 조건절 상수 3개를 `public`으로 바꿨다. 대안은 defect 쪽에
SQL을 복붙하는 것이었는데, 보안 규칙이 두 곳에 복제되어 한쪽만 고쳐지면 권한 구멍이 생긴다.
"복제보다 공개가 덜 위험하다"고 판단했다. 더 나은 해법은 7.3절에 적었다.

### 3.3 `SYSTEM_ADMIN_SCOPE`를 관측 EXISTS 밖으로 꺼냈다

`VISIBLE_ROBOT_PREDICATE`는 `(ADMIN OR 배정)`을 한 덩어리로 묶고 내부에서 alias `r`을
참조한다. defect에서 `r`은 관측 JOIN 안에서만 존재하므로, 그 상수를 그대로 쓰면 ADMIN 판정도
JOIN 안에 갇힌다. 그러면 **관측이 아직 없는 결함이 ADMIN에게도 보이지 않는다.**

`authorization-matrix.md`는 시스템 ADMIN이 전체 객체 범위를 갖는다고 정하므로, ADMIN 조건만
따로 꺼내 EXISTS 밖에서 먼저 단락 평가한다. 통합 테스트
`systemAdminSeesEveryDefectIncludingOnesWithoutObservations`가 이 판단을 직접 검증한다.

### 3.4 Create·Delete API를 만들지 않았다 (의도)

결함 도메인은 **의도적으로 full CRUD가 아니다.**

- **Create 없음** — 결함은 로봇 관측에서만 생긴다. MQTT 계약 8장: "로봇이 보내는 것은
  관리자 처리 문서가 아니라 **이상 징후를 관측했다는 불변 사실**이다." 관리자 생성 API를
  열면 근거 없는 결함을 손으로 만들 수 있고, V23의 복합 FK가 이미 DB에서 그걸 막는다.
- **Delete 없음** — runtime role에 `DELETE` 권한이 아예 없다. `defect_observation`은
  `SELECT, INSERT`만, `defect_status_history`는 append-only trigger로 이중 차단.
  종결은 삭제가 아니라 `workflow_status = FALSE_POSITIVE / REJECTED`로 처리한다.
  오탐이어도 기록은 남는다 — 감사·재학습·오탐 분석에 필요하다.

즉 이 API는 `R` + 제약된 `U`가 정답이다. 삭제 API를 만들려면 GRANT부터 바꿔야 하고,
그건 감사 정책을 뒤집는 일이다.

### 3.5 행정 상태 전이 규칙 (`DefectWorkflow`)

DB CHECK는 값의 목록만 검사하고 순서는 검사하지 않는다. 그래서 코드에서 강제한다.

```
NEW → ACCEPTED → IN_PROGRESS → RESOLVED
어느 단계에서든 → FALSE_POSITIVE / REJECTED
종결 상태(RESOLVED, FALSE_POSITIVE, REJECTED)에서는 나가는 전이 없음
```

위반은 `409 DEFECT_STATE_CONFLICT`. 그리고 `resolution`은 종결 상태에만 지정할 수 있고
`RESOLVED`에는 필수다 — 무엇으로 처리했는지 없으면 보고서를 만들 수 없다. 계약(23.8)은
`resolution`을 선택으로 적었지만 이 해석을 택했다.

> **2026-08-05 변경:** 위 전이표는 더 이상 유효하지 않다. 단계 강제를 걷어내고 `NEW`를 뺀
> 다섯 상태 사이를 자유롭게 바꿀 수 있게 했다(종결 결함 되살리기 포함). `resolution` 규칙과
> 동시성 처리는 그대로다. 배경은
> [결함-상태전이-자유화_작업내용.md](결함-상태전이-자유화_작업내용.md) 참고.

동시성은 `UPDATE ... WHERE workflow_status = :expectedStatus`로 막는다. 같은 결함에 대한
동시 전이가 하나만 통과한다.

### 3.6 SSE는 커밋 후에만 발행한다

`DefectCommandService`가 트랜잭션 안에서 `publishEvent`를 호출하지만
`DefectRealtimeEventListener`가 `@TransactionalEventListener(AFTER_COMMIT)`로 받는다.
**롤백되면 이벤트가 스트림으로 나가지 않는다.** 존재하지 않는 상태가 화면에 뜨는 일이 없다.

그리고 **이벤트마다 수신자의 객체 범위를 다시 판정한다.** 구독 시점에 한 번만 확인하면
배정이 회수된 뒤에도 결함이 계속 흘러간다. `RobotSseService`와 같은 규칙이다.

발행 지점은 다섯 곳이다.

| 이벤트 | 어디서 |
|---|---|
| `defect.created` | `IncidentEventIngestionService` — 신규 결함 생성 시 |
| `defect.updated` | 같은 곳 — 기존 결함에 관측 합산 시 (`observationCount` 등 변경) |
| `defect.updated` | `PATCH /{id}/status` (계약 22.6) |
| `defect.updated` | `PATCH /{id}` (계약 24장 유형·위치) |
| `defect.updated` | `POST /merge` — 흡수 결함마다 + 대표 결함 |

병합 이벤트는 계약에 명시돼 있지 않지만 넣었다. 흡수된 결함은 목록·지도에서 사라지는데,
이벤트가 없으면 **다른 관리자 화면이 사라진 결함을 계속 표시한다.** 그래서 payload에
`mergedIntoDefectId`를 실어 클라이언트가 화면에서 제거하고 대표를 다시 조회할 수 있게 했다.

### 3.7 이벤트명은 계약을 따랐다 — robot 스트림과 규칙이 다르다

| | 계약(24장) | 실제 코드 |
|---|---|---|
| robot | `robot.telemetry.updated` | `ROBOT_TELEMETRY_UPDATED` |
| defect | `defect.updated` | `defect.updated` (계약 준수) |

기존 robot 코드가 계약과 다른 이름을 쓰고 있다. 프론트가
`EventSource.addEventListener(name)`으로 구독하는 값이라 계약이 정본이어야 한다고 판단해
계약을 따랐다. 결과적으로 두 스트림의 명명 규칙이 갈렸다. **robot 쪽을 계약에 맞추거나
계약을 코드에 맞춰 개정하거나, 둘 중 하나로 정리해야 한다.** robot 쪽을 내가 바꾸면
프론트가 깨질 수 있어 손대지 않았다.

### 3.8 `ApplicationEventPublisherAware`를 쓴 이유

`IncidentEventIngestionService`(minho 작성)에 `defect.created` 발행을 넣으려면
`ApplicationEventPublisher`가 필요했다. 처음엔 생성자에 인자를 추가했는데,
`robot/integration/RobotEventMissionDefectIntegrationTest`가 이 서비스를 **직접 생성**하고
있어서 컴파일이 깨졌다. 그 파일은 내 수정 범위 밖이다(4절 참고).

그래서 생성자를 건드리지 않고 `ApplicationEventPublisherAware`로 주입받고, 필드 기본값을
no-op 람다로 뒀다. Spring이 빈 초기화 때 덮어쓰고, 직접 생성한 경우엔 발행만 생략된다.
**robot 쪽 파일을 한 줄도 수정하지 않고 해결했다.**

### 3.9 발행 값은 payload가 아니라 저장된 행에서 읽는다

`IncidentEventIngestionService`의 발행부는 `SELECT`를 한 번 더 한다. 이유:

- payload에는 `risk_level`이 없다. 서버가 계산해서 저장하는 값이다.
- payload로 이벤트를 만들면 실제 상태와 어긋난다. **작성 시점에는 이 칼럼이 DB 기본값
  `MEDIUM`으로 고정돼 있었는데, 그때 payload를 근거로 만들었다면 위험도 계산이 붙은 뒤
  조용히 틀린 값을 내보냈을 것이다.** (실제로 07-30에 계산 로직이 들어왔다 — 7.2절)

사건은 트랙 단위 + 30초 쿨다운이라 빈도가 낮아 SELECT 한 번의 비용은 무시할 수준이다.

### 3.10 이미지 URL은 일부러 503을 반환한다

`GET /{defectId}/image-url`은 `DefectImageUrlIssuer` port를 통해 발급하는데, 구현체가
`UnavailableDefectImageUrlIssuer` 하나뿐이고 빈 값을 돌려준다 → `503 S3_UNAVAILABLE`.

`storage` 스키마와 이미지 행은 V22로 있지만 **S3 adapter가 없어 presigned URL을 만들 수 없다.**
가짜 URL로 200을 주면 프론트가 깨진 이미지를 정상 응답으로 취급하므로 일부러 막았다.
S3 클라이언트가 준비되면 인터페이스 구현만 교체하면 된다.

---

### 3.11 자동 병합에서 `RESOLVED`만 제외했다 (2026-08-01 추가)

`IncidentEventIngestionService.findMergeCandidate()`는 반경 2m·같은 유형·같은 블록
타입·같은 순찰구역이면 새 결함을 만들지 않고 기존 결함에 관측을 붙인다. 여기에
**시간 제한도 상태 조건도 없었다.** 그래서 수리를 끝낸 자리에서 다시 탐지되면 종결된
결함의 관측이 하나 늘 뿐이었다.

```sql
AND d.workflow_status <> 'RESOLVED'
```

**종결 3종을 다 빼지 않았다.** 기준은 "현장이 실제로 바뀌었는가"다.

| 상태 | 다시 탐지되면 | 이유 |
| --- | --- | --- |
| `RESOLVED` | **새 결함** | 수리로 물리적 상태가 바뀌었다. 옛 결함에 붙이면 "고쳤는데 또 났다"가 관측 횟수 뒤로 숨고, 종결된 결함이 되살아난다 |
| `FALSE_POSITIVE` | 기존에 흡수 | 오탐으로 판정한 대상은 **순찰마다 다시 잡힌다.** 빼면 같은 오탐이 매번 새 결함으로 쌓여 목록이 잠긴다 |
| `REJECTED` | 기존에 흡수 | 위와 같다 |

수리는 현장이 바뀐 것이고, 오탐·반려는 **판단만** 바뀐 것이다. 같은 것이 계속 거기 있다.

테스트 두 건을 나란히 뒀다. 그래야 "왜 `RESOLVED`만 뺐는지"가 코드에 남는다.

- `startsNewDefectWhenTheResolvedOneIsDetectedAgain` — 2m 안(상태만 아니면 흡수됐을
  거리)인데 다른 결함이 생기고, 종결 결함의 `observation_count`는 그대로 1
- `keepsAbsorbingDetectionsThatWereDismissedAsFalsePositive` — 오탐은 계속 흡수

#### 남은 판단

**새로 생긴 결함이 이전 결함을 가리키지 않는다.** "이 자리에서 전에도 났었다"는 이력이
화면에서 끊긴다. 재발 이력이 필요하면 `recurred_from_defect_id` 같은 연결이 필요한데
마이그레이션과 화면이 따라와야 한다. 8/3 논의 항목으로 넘겼다.

#### 참고 — 병합 전에 걸러지는 두 관문

병합 판정에 가기도 전에 결함이 아예 만들어지지 않는 경우가 있다. **시연에서 결함이
0건 뜨는 사고의 1순위 원인**이라 여기 함께 적어둔다.

| 조건 | 실패 시 |
| --- | --- |
| `location.valid == true` | 결함 미생성, `incident_ingestion_issue`에 `INVALID_GPS` |
| `patrolAreaId != null` | 결함 미생성, `PATROL_AREA_UNRESOLVED` |

### 3.12 결함 유형을 3종으로 좁혔다 (2026-08-01, V36)

제품에서 쓰는 유형이 **파손·설치 이상·기타 이상** 셋으로 정해졌다. 나머지 넷
(`CONTAMINATED`, `UPLIFTED`, `BLOCKED`, `MISSING_SUSPECTED`)을 뺐다.

#### 임베디드 계약은 바뀌지 않았다

확인해보니 MQTT 페이로드의 `IncidentEventPayload.DefectType`은 **원래 `DAMAGED`
하나만** 받고 있었다. 로봇이 보내는 값은 그대로다.

바꿔 말하면 `INSTALLATION_INVALID`·`UNKNOWN_ANOMALY`는 **사람이 결함 수정에서
지정하는 값**이다. 로봇은 파손만 올리고 관리자가 보고 재분류하는 구조다.

> **내가 한 번 잘못 짚었다.** MQTT 문서가 7종을 나열하는 걸 보고 "코드와 어긋나 있다"고
> 판단했는데, 열어보니 `defect_type: ["DAMAGED"]` / `reserved_defect_type: [나머지]`로
> **정확히 구분돼 있었다.** B의 문서는 처음부터 맞았다. 목록에 값이 보인다고 계약이
> 그렇다는 뜻이 아니다 — 그 값이 **어느 필드 아래** 있는지를 봐야 했다.
> 예약 목록만 2종으로 좁혔다.

#### 기존 행은 지우지 않고 옮겼다

없어지는 유형이 들어 있던 22건을 `UNKNOWN_ANOMALY`로 UPDATE한 뒤 CHECK를 바꿨다.
지우지 않은 이유는 관측·이미지·이력이 딸려 있어서다. 순서가 중요하다 — 제약을
먼저 바꾸면 기존 행이 위반이라 마이그레이션이 실패한다.

제약은 세 곳이었다(`pg_constraint`로 확인): `defect.defect`,
`defect.defect_observation`, `defect.incident_ingestion_issue`.

`defect_status_history`의 before/after JSONB와 감사 로그 스냅샷은 **그대로 뒀다.**
그 시점에 실제로 그 값이었다는 기록이고, 되짚을 때 필요하다.

#### 통계까지 번졌다 — A 소유 파일 5개

유형은 통계 계약이기도 해서 defect 밖으로 번졌다. 안 고치면 **통계에 존재하지 않는
유형 4개가 항상 0으로** 뜬다.

| 파일 | 이유 |
| --- | --- |
| `statistics/StatisticsDtos.java` | enum 7 → 3 |
| `StatisticsControllerTest`, `StatisticsQueryServiceTest` | 기대값 |
| `fixtures/patrol-mission-statistics.sql` | **불가피.** 픽스처가 `BLOCKED` 등을 INSERT해서 V35 이후 제약 위반이 된다 |
| `PatrolMissionSchemaIntegrationTest` | 위 픽스처의 기대 집계 |

의미는 바꾸지 않았다. 없어지는 유형을 전부 `UNKNOWN_ANOMALY`로 매핑하고 기대값만
그에 맞췄다. **A에게 알려야 한다.**

#### LLM 검증은 이미 새 계약을 따른다

`docs/report-llm-result.schema.json`은 **읽는 코드가 없는 문서**다. 실제 검증은
`ReportLlmResultValidator`가 하는데, 유형을 문자열 목록으로 보지 않고
`StatisticsDtos.DefectType`으로 파싱한 뒤 근거 결함의 값과 대조한다.

그 enum을 줄였으므로 검증은 자동으로 좁아졌고, **오히려 더 엄격해졌다** — 전에는
없어진 값이 파싱은 통과하고 근거 대조에서 걸렸는데 이제 파싱에서 막힌다.
스키마 문서는 읽는 사람이 오해하지 않도록 함께 좁혔다.

### 3.13 마이그레이션 번호는 파일이 아니라 **DB에도** 박힌다 (2026-08-02)

유형 축소를 V35로 만들어 로컬에 적용해 뒀는데, 그 사이 master에 **다른 V35**
(`allow_created_mission_deletion`)가 들어왔다. 브랜치를 새 master 위로 재적용하면서
파일을 V36으로 바꾸고 문서도 고쳤는데, **DB의 `flyway_schema_history`는 그대로였다.**

```
DB 기록:  35 | narrow defect types        <- 어제 적용한 것
현재 파일: V35 = allow_created_mission_deletion
          V36 = narrow_defect_types
```

다음 기동에서 백엔드가 **아예 뜨지 않았다.**

```
Migration checksum mismatch for migration version 35
Validate failed: Migrations have failed validation
```

#### `flyway repair`를 쓰지 않은 이유

repair는 **이력을 파일에 맞춰 덮어쓰기만 한다.** 그러면 실행된 적 없는 master의 V35가
"적용됨"으로 기록된다. 트리거와 권한이 없는데 있다고 믿는 DB가 되고, 그 거짓말은
한참 뒤 엉뚱한 곳에서 드러난다.

대신 **어긋난 이력 한 줄만 지웠다.**

```sql
DELETE FROM flyway_history.flyway_schema_history
WHERE version = '35' AND description = 'narrow defect types';
```

그러면 Flyway가 V35·V36을 순서대로 실행한다.

- **V35**: 이 DB에 처음 적용 → 미시작 Mission 삭제 트리거·권한이 실제로 생긴다
- **V36**: 내용은 이미 반영돼 있어 UPDATE 0건, 제약은 같은 값으로 재생성 → 무해

결함 41건과 이미지 6장을 그대로 두고 이력만 바로잡았다. 손대기 전에 백업을 떴다
(`scripts/backup-defect-images.sh`). **DB 이력 테이블을 직접 수정하는 작업**이라
되돌릴 지점이 없으면 안 된다.

#### 교훈

> 마이그레이션 번호를 바꾸면 **파일 · 문서 · 이미 적용된 DB** 셋을 함께 봐야 한다.
> 나는 앞의 둘만 고치고 DB를 잊었다.

브랜치를 재적용하는 사람이 또 나오면(master force push 여파로 minho·chaeun도 같은
상황이다) 똑같이 걸린다. 재적용 후 **첫 기동을 반드시 해보고 넘어간다.**

같은 맥락에서 `docs/database-migrations.md` 등록도 빠뜨렸다. 그 문서가 번호의
기준이라 빠지면 다음 사람이 번호를 또 충돌시킨다. **마이그레이션을 만들면 파일 ·
목록 문서 · DDL 참고 문서를 한 묶음으로 본다.**

## 4. 작업 범위 제약 (중요)

**내가 수정할 수 있는 기존 파일은 `com/gilbom/defect/` 밑으로 한정된다.**
`com/gilbom/robot/**`은 minho가 활발히 작업 중이라 건드리면 머지 충돌이 생기고 그의 작업을
덮어쓴다. defect 스키마 migration과 `docs/`는 수정해도 문제되지 않았다.
**새 파일 생성은 위치 제약이 없다.**

경계를 넘는 문제가 생기면 다른 파일을 고치지 말고 **defect 쪽 설계로 흡수한다.**
실제 사례가 3.8절이다. 변경 후 `git status --porcelain`으로 defect 밖 변경이 없는지 확인한다.

---

## 5. 마이그레이션

### V19 `link_defect_to_robot_and_add_export_job`

- `defect_observation.robot_id` (`NOT NULL`) + `robot.robot` FK + 범위 판정용 index
- `defect.defect_export_job` 테이블

`NOT NULL`로 넣을 수 있었던 이유는 그 시점에 `defect_observation`에 쓰는 코드가 없어
테이블이 비어 있었기 때문이다. 실행 순서 의존성은 3.1절 참고.

### V27 `validate_defect_and_command_constraints`

V23이 `NOT VALID`로 걸어둔 FK 6개 + CHECK 1개를 `VALIDATE`로 승격한다.
`NOT VALID`는 신규 쓰기만 막고 기존 행은 검사하지 않으며, 플래너도 제약을 신뢰하지 않는다.

안전성 근거 3단(파일 주석에도 적어뒀다):

1. V23 **이후**에 쓰인 행은 이미 FK가 적용된 상태로 들어갔으므로 orphan일 수 없다.
2. 따라서 위험은 V23 적용 시점에 이미 있던 행뿐인데, **참조 대상 테이블이 V22에서 처음
   생겼다.** 그 전에는 이 칼럼들을 채울 방법이 없었다.
3. 적용 대상 DB에서 `defect.defect`, `defect.defect_observation`,
   `robot.robot_command`가 모두 0행임을 확인했다.

적용 결과 `SELECT ... FROM pg_constraint WHERE NOT convalidated`가 **0행**이 됐다.
DB 전체에 미검증 제약이 남지 않았다.

> V23은 minho가 "기존 데이터 정리 후 별도 배포에서 VALIDATE한다"고 미뤄둔 것이다.
> 위 근거로 안전이 증명돼 만들었지만, 배포 계획은 그의 소유이므로 확인을 받는 것이 맞다.

---

## 6. 검증 방법

### 6.1 상시 검증 — 테스트

```bash
cd /home/ssafy/S15P11D101/web && sh scripts/backend-test.sh
```

이 스크립트가 정본이다. 호스트에 JDK가 없어서 `sh gradlew`를 직접 돌리면
`JAVA_HOME` 에러가 난다. 스크립트가 JDK 컨테이너 + docker.sock 권한 +
Testcontainers 호스트 오버라이드를 전부 처리한다. **`--rerun-tasks`가 붙어 있어
UP-TO-DATE로 건너뛰지 않는다.**

`build.gradle`에 **skip을 금지하는 게이트**가 있다. skip이 하나라도 있으면 빌드가 실패하며,
Testcontainers가 Docker를 못 쓰면 통합 테스트가 skip되어 이 게이트에 걸린다.

defect 관련 테스트 (전체 507개 중):

| 파일 | 무엇을 |
|---|---|
| `DefectWorkflowTest` | 상태 전이 허용·거부, resolution 일관성 |
| `DefectFilterTest` | 필터 정규화, bbox 파싱, **정렬 allow-list**(SQL 주입 방어) |
| `DefectControllerTest` | 엔드포인트 권한·검증·오류 매핑 |
| `DefectCommandServiceMergeEventTest` | 병합 시 이벤트 발행 개수·내용 |
| `DefectSseServiceTest` | 범위 판정·구역 필터·세션 폐기 |
| `DefectSseControllerTest` | **HTTP 계약** — 헤더, `event:defect.updated` 이름, JSON payload |
| `DefectQueryScopeIntegrationTest` | **실제 PostGIS**에서 범위 판정의 *의미* |

**`DefectQueryScopeIntegrationTest`가 가장 중요하다.** 손으로 조립한 범위 조건절은 문법만
맞아도 의미가 틀릴 수 있다. "배정된 로봇의 결함만 보이는지", "ADMIN은 전체가 보이는지",
"만료된 배정으로는 안 보이는지"는 실제 DB에서만 확인된다.

### 6.2 SSE를 테스트로 검증하게 만든 이유

처음엔 curl로 수동 확인하려 했는데 계속 막혔다. 이 프로젝트는 로그인이 **202 + challenge**를
반환하고 MFA를 통과해야 세션이 `AUTHENTICATED`가 된다. challenge는 5분 만료, CSRF 토큰은
세션에 묶여 있고 인증 후 재발급된다. 트러블슈팅하는 사이 계속 만료됐다.

그래서 `DefectSseControllerTest`로 옮겼다. MockMvc가 인증을 세워주므로 **MFA 없이** 같은
경로를 재현 가능하게 검사한다. [jenkins-pipeline.md](../../jenkins-pipeline.md)대로 Jenkins가
이미지 빌드마다 `test`를 돌리므로 회귀도 자동으로 잡힌다.

`RobotSseControllerTest`와 다르게 **SSE 서비스를 목킹하지 않았다.** 목으로 대체하면 정작
검증 대상인 이벤트명과 직렬화가 빠진다. 대신 `DefectSseService`가 싱글턴이라 구독이 테스트
간 누적되는 문제가 생겨 `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`를 붙였다.

### 6.3 수동 확인 보조 도구

```bash
export GILBOM_EMAIL='...' GILBOM_PASSWORD='...' GILBOM_TOTP_SECRET='...'
python3 scripts/verify-defect-sse.py
```

CSRF → 로그인 → MFA → 상태 확인 → SSE 구독을 한 번에 처리한다. 자격증명은 환경변수로만
받고 출력하지 않으며, 읽기 전용이다.

**상시 검증용이 아니다.** 실배포 환경에서 눈으로 한 번 보거나, 아래 함정을 잡을 때 쓴다.

### 6.4 자동화로 덮지 못하는 것

- **브라우저 `EventSource` 재연결** — MockMvc는 실제 소켓이 아니라 끊김·재연결을 재현하지
  못한다. 계약 24장이 "재연결 중 누락 가능성이 있는 화면은 최신 상태 REST API를 다시 조회"로
  정해뒀으니 복구는 프론트 몫이다.
- **Nginx SSE 버퍼링** — 프록시가 버퍼링하면 이벤트가 지연된다. `proxy_buffering off`가
  필요하다. 실제로 자주 겪는 함정이라 프론트/인프라 담당자에게 공유해야 한다.

---

## 7. 미완 항목

### 7.1 내 쪽 — 둘 다 S3에 막혀 있다

| 항목 | 상태 |
|---|---|
| `POST /defects/export` worker | job을 **INSERT만 하고 읽는 코드가 없다.** 202를 받아도 job은 영원히 `QUEUED`이고 파일이 생기지 않는다. worker + 파일 저장소(S3) + `job_status`·`row_count`·`file_object_key` 갱신이 필요하다 (V19에 컬럼은 있다) |
| `GET /{id}/image-url` | 항상 503. 3.10절 참고 |

S3 연동 계획이 없다면 지금이 최선이고, 프론트에 "내보내기·근거 이미지는 후속"으로
공유해야 한다. 붙일 계획이 있다면 두 개를 한 묶음으로 하는 게 효율적이다.

### 7.2 minho에게 넘겼던 것 — **3개 모두 해소됨 (2026-07-30 확인)**

`IncidentEventIngestionService`와 V22·V23이 그의 작업이다. 07-30에 확인했을 때 내가
넘긴 세 항목이 모두 처리돼 있었다. 무엇이 문제였고 어떻게 해결됐는지 기록으로 남긴다.
현재 스키마 기준 설명은 [defect-schema.md](../../defect-schema.md) 2.1·2.5·2.6절에 있다.

| 넘겼던 문제 | 해결 |
|---|---|
| `risk_level`이 항상 `MEDIUM`이라 `severities` 필터와 지도 마커 색이 무의미했다 | `risk_level`·`risk_score`·`risk_rule_version`을 저장한다. V1 규칙은 `damage_ratio × 100`이고 25/50/75를 등급 경계로 쓰며 `DAMAGE_RATIO_V1`을 남긴다. `damage_ratio`가 없으면 **AI `confidence`로 대체하지 않고** 점수 NULL·등급 `MEDIUM`·`review_required=true`로 둔다 |
| `incident_code`가 `"INC-" + eventId`(UUID)라 계약 형식과 달랐고 `VARCHAR(40)` 한계에 붙어 있었다 | `INC-YYYYMMDD-NNNNNN`. 날짜는 `occurred_at`의 Asia/Seoul 일자이고, `defect.incident_code_counter`(V28)가 `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`으로 일자별 순번을 원자적으로 발급한다. 결함 저장이 실패하면 Counter 증가도 함께 롤백된다 |
| GPS 무효·구역 미확정 탐지가 조용히 버려져 집계 어디에도 남지 않았다 | `defect.incident_ingestion_issue`(V28) 검토 대기열에 남긴다. `issue_type`은 `INVALID_GPS` / `PATROL_AREA_UNRESOLVED`, `issue_status`는 `OPEN` / `RESOLVED` / `DISMISSED`. 가짜 좌표로 결함을 만들지 않으면서 `telemetry.robot_event` 원본과 Image 연결은 같은 트랜잭션에 보존한다 |

**내 코드에 미치는 영향**: `severities` 필터와 지도 마커 색이 이제 의미를 갖는다.
다만 `damage_ratio`가 없는 관측은 여전히 `MEDIUM`으로 들어오므로, 등급만으로 판단하지 말고
`review_required`를 함께 봐야 한다.

### 7.3 구조 개선 후속

3.2절의 `public` SQL 상수를 없애려면 범위 판정을 DB 함수로 내리는 것이 정답이다.

```sql
CREATE FUNCTION robot.visible_robot_ids(p_user_id UUID) RETURNS TABLE (robot_id UUID) STABLE ...
```

그러면 `RobotScopeService`의 상수를 전부 `private`으로 되돌릴 수 있고, 범위 규칙이 Java
문자열과 defect 복사본으로 이원화되는 것도 사라진다. 이 코드베이스에 이미 DB 함수·trigger가
많아 이질적이지 않다. 다만 robot 도메인 변경이 필요해 내 범위 밖이다.

---

## 8. 작업 중 겪은 함정

다음에 같은 곳에서 시간 낭비하지 않도록 남긴다.

**PostgreSQL null 파라미터** — `COALESCE(:param, col)`이나 `:param IS NULL`에 null을
바인딩하면 `could not determine data type of parameter`가 난다. 모두 명시적으로 `CAST`했다.
좌표는 두 값이 함께 와야 의미가 있어 `locationChanged` boolean 플래그를 따로 넘긴다.

**`Map.of("a","b")`는 `Map<String,Object>`가 아니다** — `Map<String,String>`으로 추론되어
할당이 안 된다. 이력 snapshot 파라미터를 `Map<String, ?>`로 넓혔다.

**`Map.copyOf`는 null 값을 거부한다** — JSON snapshot에 null이 있을 수 있어
`Collections.unmodifiableMap(new LinkedHashMap<>(...))`로 바꿨다.

**`location_accuracy_m`은 `REAL`** — `getObject`가 `Float`를 돌려줘 `(Double)` 캐스트가
`ClassCastException`이 된다. `getDouble` + `wasNull`로 읽는다.

**좌표 한쪽만 바뀌면 위치 갱신이 조용히 누락됐다** — `changedOrNull`을 좌표에 개별 적용하면
한쪽이 null이 되어 SQL이 위치를 통째로 건너뛴다. 좌표는 한 쌍으로 판정한다.

**V23 적용 후 `defect_observation` 삽입에 선행 데이터가 필요하다** — 복합 FK가 `NOT VALID`
여도 신규 삽입에는 적용된다. 그래서 테스트 fixture가
`robot → boot_session → robot_event → defect → observation` 체인을 다 만들어야 한다.
**앞으로 defect 관련 테스트나 시드 데이터를 만드는 사람 모두에게 해당된다.**

**Flyway checksum** — 이미 적용된 `V*.sql`을 주석만 고쳐도 checksum이 달라져
**적용한 모든 DB의 기동이 막힌다.** V19에 설명 주석을 넣으려다 되돌리고 문서로 옮겼다.

**마이그레이션은 JAR 안에 있다** — `docker compose up -d backend`는 실행 중 컨테이너를
up-to-date로 보고 아무것도 하지 않는다. 소스만 pull해도 이미지는 안 갱신된다.
`--build`가 필요하다.

**`@WebMvcTest`의 싱글턴 상태** — `DefectSseService`가 구독을 내부 Map에 보관하는데 컨텍스트를
재사용하면 앞선 테스트의 구독이 살아남아 `subscriptionCount`와 `publish` 대상이 오염된다.

---

## 9. 커밋 이력

| 커밋 | 내용 |
|---|---|
| `0ade319` | defect 스키마 (V12) |
| `0e72452` | REST API 11개 + V19 |
| `0631805` | FK 현황 문서 갱신 + 통합 테스트 |
| `867ad99` | SSE 스트림 + `DefectAccessService` + V27 |
| `392e37f` | `defect.created`·병합 이벤트 발행 |

관련 문서: [defect-schema.md](../../defect-schema.md)(스키마·FK·범위 판정),
[database-migrations.md](../../database-migrations.md)(migration 표),
[authorization-matrix.md](../../authorization-matrix.md) 11장(권한).
