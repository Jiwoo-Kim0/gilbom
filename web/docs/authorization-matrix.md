# 역할·권한·API 접근 매트릭스

- 문서 상태: 확정
- 구현 상태: V13 RBAC 저장 구조·DB 보호, V14 변경 세션 폐기, 로그인 유효 권한 계산, 로봇 객체 범위, 공통 감사 기록과 감사 조회 API 완료. 관리자 API·다른 도메인 객체 범위 적용 예정
- DB 기준: `auth.role`, `auth.permission`, `auth.role_permission`, `auth.user_permission_override`, `auth.user_robot_assignment`

## 1. 인가 원칙

- 인증과 인가는 서버에서 매 요청마다 검사한다.
- 역할은 권한 묶음이고 API는 `ROLE_ADMIN` 같은 역할명보다 세부 권한 코드를 검사한다.
- 명시적으로 공개하지 않은 모든 API는 기본 거부한다.
- URL 계층과 서비스 메서드 계층을 함께 보호한다.
- 프론트엔드 메뉴·버튼 숨김은 사용성 기능일 뿐 보안 통제가 아니다.
- 목록은 접근 가능한 객체만 필터링하고, 단건 리소스가 객체 범위 밖이면 존재 여부를 숨기기 위해 `404`로 처리한다.
- 권한 코드 자체가 없으면 `403 AUTH_FORBIDDEN`, 인증이 없으면 `401 AUTH_UNAUTHENTICATED`를 반환한다.
- 상태 변경 요청은 권한 검사와 별개로 CSRF를 통과해야 한다.

## 2. 유효 권한 계산

`ADMIN_SETTINGS_MANAGE`는 시스템 `ADMIN` 역할에만 부여한다. 사용자별 `ALLOW`나 사용자 정의 역할로 우회 부여할 수 없다. 마지막 활성 `ADMIN`의 역할 제거, 권한 차단, 계정 비활성화는 거부한다.

역할·권한·예외·로봇 배정이 변경되면 대상 사용자의 활성 세션을 즉시 폐기하여 다음 로그인부터 다시 권한을 계산한다.

로그인 완료 시 권한은 다음 하나의 DB statement에서 계산한다.

```text
effectivePermissions
  = (activeRolePermissions ∪ activeUnexpiredAllows)
    - activeUnexpiredDenies
```

- `ALLOW`와 `DENY`는 `is_active=TRUE`이면서 `expires_at`이 없거나 현재보다 미래인 행만 사용한다.
- 같은 permission의 `ALLOW`와 `DENY`가 함께 있으면 활성 `DENY` anti-join이 최종 후보에서 제거한다.
- role, role-permission, permission 중 하나라도 비활성이면 역할 권한 후보에서 제외한다.
- 사용자 `ALLOW` 경로에서는 `ADMIN_SETTINGS_MANAGE`를 항상 제외하고, 역할 경로도 활성 시스템 `ADMIN`에서만 허용한다.
- 권한 코드 통과 후 `user_robot_assignment`를 사용한 객체 범위 검사를 별도로 수행한다.
- 계산 결과는 인증 session의 authority snapshot이므로 V14 DB trigger가 역할·권한·override·로봇 배정·계정 상태 변경과 같은 transaction에서 관련 session을 폐기한다.

### 2.1 마지막 활성 ADMIN 동시성 기준

마지막 활성 `ADMIN` 수를 감소시킬 수 있는 계정·역할·권한 변경은 다음 순서를
한 DB transaction에서 지킨다.

1. 첫 DB statement로 `SELECT auth.lock_last_admin_guard()`를 호출한다.
2. lock을 얻은 뒤 활성 시스템 `ADMIN` 수와 대상 사용자의 유효 권한을 다시 읽는다.
3. 마지막 `ADMIN`이면 변경을 거부하고, 아니면 보안 변경과 감사 로그를 저장한다.
4. commit 또는 rollback까지 transaction advisory lock을 유지한다.

DB trigger도 같은 lock과 판정 함수를 사용해 직접 DML을 방어한다. 애플리케이션의
사전 `count()` 결과만 믿거나 lock 획득 전에 읽은 값을 재사용하지 않는다. 동시 변경
통합 테스트는 서로 다른 두 transaction이 각기 다른 `ADMIN`을 동시에 해제해도
최소 한 명이 남고, 한 요청은 거부되는지 검증한다.

## 3. 시스템 역할별 권한

| 권한 | 설명 | ADMIN | MANAGER | OPERATOR |
|---|---|:---:|:---:|:---:|
| `ROBOT_STATUS_VIEW` | 로봇·텔레메트리·이벤트 조회 | O | O | O |
| `ROBOT_REGISTER` | 로봇·기기 인증정보 관리 | O |  |  |
| `ROBOT_COMMAND_VIEW` | 명령·결과 조회 | O | O | O |
| `ROBOT_COMMAND_EXECUTE` | 임무·긴급정지 명령 발행 | O | O | O |
| `ROBOT_EVENT_ACKNOWLEDGE` | 경고 확인 처리 | O | O | O |
| `MISSION_VIEW` | 구역·경로·임무 조회 | O | O | O |
| `MISSION_MANAGE` | 구역·경로·임무 생성·수정 | O | O |  |
| `DEFECT_STATUS_VIEW` | 결함·이미지 조회 | O | O | O |
| `DEFECT_STATUS_UPDATE` | 결함 정보·처리 상태 수정 | O | O | O |
| `DEFECT_MERGE` | 결함 병합·취소 | O | O |  |
| `DEFECT_EXPORT` | 결함 데이터 내보내기 | O | O |  |
| `REPORT_VIEW` | 보고서 조회·다운로드 | O | O | O |
| `REPORT_CREATE` | 보고서 생성 | O | O | O |
| `REPORT_UPDATE` | 보고서 수정·검토 요청 | O | O | O |
| `REPORT_APPROVE` | 보고서 승인·반려·일정 관리 | O | O |  |
| `AUDIT_LOG_VIEW` | 감사 로그 조회 | O | O |  |
| `ADMIN_SETTINGS_MANAGE` | 사용자·역할·권한·운영 설정 | O |  |  |
| `SYSTEM_LOG_VIEW` | MQTT 수신·기술 로그 조회 | O |  |  |

사용자 정의 역할은 위 권한을 조합할 수 있지만 `ADMIN_SETTINGS_MANAGE`는 포함할 수 없다.

## 4. 로봇 객체 범위

`auth.user_robot_assignment`는 역할이 아니라 특정 로봇과 그 로봇에서 파생된 리소스의 범위다. 권한 코드와 배정 유형을 모두 만족해야 한다.

| 배정 | 조회 | 명령·경고 확인 | 결함 상태 수정 | 임무 관리 |
|---|:---:|:---:|:---:|:---:|
| `VIEWER` | O |  |  |  |
| `OPERATOR` | O | O | O |  |
| `OWNER` | O | O | O | O |

세부 규칙은 다음과 같다.

- 시스템 `ADMIN`은 전체 객체 범위를 가지지만 해당 권한 코드도 보유해야 한다.
- `MANAGER`, `OPERATOR`, 사용자 정의 역할은 활성 로봇 배정을 적용한다.
- 로봇 ID가 직접 있거나 임무·명령·이벤트·결함을 통해 로봇을 추적할 수 있으면 같은 배정을 적용한다.
- 컬렉션, 지도, 요약, SSE는 접근 가능한 로봇에서 파생된 데이터만 반환한다.
- 명령 실행·경고 확인·결함 수정은 `OPERATOR` 또는 `OWNER` 배정이 필요하다.
- 임무 생성·수정은 `MISSION_MANAGE`와 대상 로봇의 `OWNER` 배정이 모두 필요하다.
- 순찰 구역·계획 경로와 보고서는 현재 사용자-구역 배정 모델이 없으므로 MVP에서는 권한 코드만 검사한다.
- 보고서가 특정 로봇·임무의 근거 파일이나 이미지에 접근하게 하면 해당 원본 리소스 범위를 다시 검사한다.
- 범용 파일 URL 발급은 파일의 원본 리소스 권한과 객체 범위를 상속한다.

현재 로봇 구현은 목록의 count와 page 조회, 지도, 요약 집계, 단건 조회 모두
`auth.user_robot_assignment`를 SQL `EXISTS` 조건으로 적용한다. 전체 행을 읽은
후 Java에서 제거하지 않는다. 시스템 `ADMIN`의 전체 범위도 SQL에서 판정하지만
컨트롤러의 기능 permission 검사를 생략하지 않는다.

로봇 대상 상태 변경은 대상 행을 조회하는 SQL에서 `OPERATOR` 또는 `OWNER` 배정과
만료 시각을 함께 검사한다. 미존재와 범위 밖, 부족한 배정 등급은 모두
`ROBOT_NOT_FOUND`로 처리하여 객체 존재 여부를 숨긴다.

## 5. 공개·인증 상태 API

`CSRF` 열의 O는 `X-CSRF-TOKEN` 필수라는 뜻이다.

| Method | Path | 접근 조건 | CSRF | 감사 |
|---|---|---|:---:|---|
| GET | `/api/v1/auth/csrf-token` | 공개 |  |  |
| GET | `/api/v1/auth/session-state` | 공개·쿠키 결합 상태만 반환 |  |  |
| POST | `/api/v1/auth/email-availability` | 익명 세션 | O |  |
| POST | `/api/v1/auth/registrations` | 익명 세션 | O | 가입 성공 |
| POST | `/api/v1/auth/login` | 익명 세션 | O | 성공·실패·제한 |
| POST | `/api/v1/auth/password/initial-change` | `PASSWORD_CHANGE_REQUIRED` | O | 성공·실패 |
| POST | `/api/v1/auth/mfa/totp/enroll` | `MFA_ENROLLMENT_REQUIRED` | O | 등록 시작 실패 |
| POST | `/api/v1/auth/mfa/totp/confirm` | `MFA_ENROLLMENT_REQUIRED` | O | 등록 성공·실패 |
| POST | `/api/v1/auth/mfa/totp/verify` | `MFA_REQUIRED` | O | 성공·실패·제한 |
| POST | `/api/v1/auth/mfa/recovery` | `MFA_REQUIRED` | O | 사용 성공·실패 |
| POST | `/api/v1/auth/logout` | 세션 존재 | O | O |
| GET | `/api/v1/auth/me` | 인증 완료 |  |  |
| GET | `/api/v1/navigation` | 인증 완료 |  |  |
| GET | `/api/v1/system/health` | 공개 |  |  |
| GET | `/api/v1/system/info` | 공개 |  |  |

## 6. 내 계정·알림 API

| Method | Path | 접근 조건·범위 | CSRF | 감사 |
|---|---|---|:---:|---|
| GET | `/api/v1/my/profile` | 인증 완료·본인 |  |  |
| PATCH | `/api/v1/my/profile` | 인증 완료·본인 | O | 중요 프로필 변경 |
| GET | `/api/v1/my/statistics` | 인증 완료·본인 |  |  |
| PATCH | `/api/v1/my/password` | 인증 완료·본인·현재 비밀번호 재검증 | O | O |
| POST | `/api/v1/my/mfa/totp/reset` | 인증 완료·본인·비밀번호와 MFA 재검증 | O | O |
| DELETE | `/api/v1/my/account` | 인증 완료·본인·비밀번호 재검증 | O | O |
| POST | `/api/v1/my/profile-image/presign` | 인증 완료·본인 | O |  |
| GET | `/api/v1/notifications` | 인증 완료·본인 |  |  |
| GET | `/api/v1/notifications/unread-count` | 인증 완료·본인 |  |  |
| PATCH | `/api/v1/notifications/{notificationId}/read` | 인증 완료·본인 알림 | O |  |
| POST | `/api/v1/notifications/read-all` | 인증 완료·본인 알림 | O |  |
| DELETE | `/api/v1/notifications/{notificationId}` | 인증 완료·본인 알림 | O |  |

## 7. 관리자·RBAC API

아래 API는 모두 `ADMIN_SETTINGS_MANAGE`가 필요하며 시스템 `ADMIN`만 접근한다.

| Method | Path | CSRF | 감사 |
|---|---|:---:|---|
| GET | `/api/v1/admin/registration-requests` |  |  |
| POST | `/api/v1/admin/registration-requests/{userId}/approve` | O | 승인·역할 부여 |
| POST | `/api/v1/admin/registration-requests/{userId}/reject` | O | 거절 |
| GET, POST | `/api/v1/admin/roles` | POST O | 생성 |
| GET, PATCH, DELETE | `/api/v1/admin/roles/{roleId}` | PATCH·DELETE O | 변경·비활성화 |
| GET | `/api/v1/admin/permissions` |  |  |
| GET, PUT | `/api/v1/admin/roles/{roleId}/permissions` | PUT O | O |
| PUT | `/api/v1/admin/users/{userId}/roles` | O | O |
| GET, PUT | `/api/v1/admin/users/{userId}/permission-overrides` | PUT O | O |
| GET, PUT | `/api/v1/admin/users/{userId}/robot-assignments` | PUT O | O |
| GET | `/api/v1/admin/users` |  |  |
| GET | `/api/v1/admin/users/{userId}` |  |  |
| PATCH | `/api/v1/admin/users/{userId}/status` | O | 활성화·비활성화 |
| POST | `/api/v1/admin/users/{userId}/mfa/reset` | O | MFA 초기화·세션 폐기 |

역할·권한·배정 변경은 변경자와 대상자를 분리해 감사하고 대상 사용자의 모든 세션을 폐기한다.
가입 거절은 `REJECTED`, 운영 비활성화는 `DISABLED`로 구분한다. 목록 교체형
`PUT`은 누락된 활성 관계를 종료하고 요청 목록을 새 활성 상태로 적용하되 과거 행은
물리 삭제하지 않는다.

## 8. 로봇·텔레메트리·이벤트 API

| Method | Path | 권한 | 객체 범위 | CSRF | 감사 |
|---|---|---|---|:---:|---|
| GET | `/api/v1/robots/summary` | `ROBOT_STATUS_VIEW` | 배정 로봇 집계 |  |  |
| GET | `/api/v1/robots/map` | `ROBOT_STATUS_VIEW` | 배정 로봇 |  |  |
| GET | `/api/v1/robots` | `ROBOT_STATUS_VIEW` | 배정 로봇 |  |  |
| POST | `/api/v1/robots` | `ROBOT_REGISTER` | 전역 | O | O |
| GET | `/api/v1/robots/{robotId}` | `ROBOT_STATUS_VIEW` | `VIEWER+` |  |  |
| PATCH, DELETE | `/api/v1/robots/{robotId}` | `ROBOT_REGISTER` | 전역 | O | O |
| GET | `/api/v1/robots/{robotId}/state` | `ROBOT_STATUS_VIEW` | `VIEWER+` |  |  |
| GET | `/api/v1/robots/{robotId}/telemetry` | `ROBOT_STATUS_VIEW` | `VIEWER+` |  |  |
| GET | `/api/v1/robots/{robotId}/boot-sessions` | `ROBOT_STATUS_VIEW` | `VIEWER+` |  |  |
| GET | `/api/v1/boot-sessions/{bootId}` | `ROBOT_STATUS_VIEW` | 연결 로봇 `VIEWER+` |  |  |
| GET | `/api/v1/robots/{robotId}/credentials` | `ROBOT_REGISTER` | 전역·secret 미반환 |  | O |
| POST | `/api/v1/robots/{robotId}/credentials` | `ROBOT_REGISTER` | `OPERATOR+`, ADMIN 전체 범위 | O | O |
| POST | `/api/v1/robots/{robotId}/credentials/{credentialId}/revoke` | `ROBOT_REGISTER` | 전역 | O | O |
| GET | `/api/v1/robot-events` | `ROBOT_STATUS_VIEW` | 배정 로봇 이벤트 |  |  |
| PATCH | `/api/v1/robot-events/{eventId}/acknowledge` | `ROBOT_EVENT_ACKNOWLEDGE` | `OPERATOR+` | O | O |

`VIEWER+`는 `VIEWER`, `OPERATOR`, `OWNER`를 뜻하고 `OPERATOR+`는 `OPERATOR`, `OWNER`를 뜻한다.

## 9. 순찰 구역·경로·임무 API

| Method | Path | 권한 | 객체 범위 | CSRF | 감사 |
|---|---|---|---|:---:|---|
| GET, POST | `/api/v1/patrol-areas` | GET `MISSION_VIEW`, POST `MISSION_MANAGE` | MVP 권한 단위 | POST O | 생성 |
| GET, PATCH, DELETE | `/api/v1/patrol-areas/{patrolAreaId}` | GET `MISSION_VIEW`, 변경 `MISSION_MANAGE` | MVP 권한 단위 | 변경 O | 변경 |
| GET, POST | `/api/v1/patrol-areas/{patrolAreaId}/routes` | GET `MISSION_VIEW`, POST `MISSION_MANAGE` | MVP 권한 단위 | POST O | 생성 |
| GET | `/api/v1/patrol-areas/{patrolAreaId}/routes/{patrolRouteId}` | `MISSION_VIEW` | MVP 권한 단위 |  |  |
| PATCH, DELETE | `/api/v1/patrol-routes/{patrolRouteId}` | `MISSION_MANAGE` | MVP 권한 단위 | O | O |
| GET, POST | `/api/v1/missions` | GET `MISSION_VIEW`, POST `MISSION_MANAGE` | GET 배정 로봇, POST 대상 로봇 `OWNER` | POST O | 생성 |
| GET, PATCH | `/api/v1/missions/{missionId}` | GET `MISSION_VIEW`, PATCH `MISSION_MANAGE` | GET `VIEWER+`, PATCH `OWNER` | PATCH O | 변경 |
| GET | `/api/v1/missions/{missionId}/trajectory` | `MISSION_VIEW` | 연결 로봇 `VIEWER+` |  |  |
| GET | `/api/v1/missions/{missionId}/history` | `MISSION_VIEW` | 연결 로봇 `VIEWER+` |  |  |

### 9.1 통계 API

| Method | Path | 권한 | 객체 범위 | CSRF | 감사 |
|---|---|---|---|:---:|---|
| GET | `/api/v1/statistics/by-status` | `DEFECT_STATUS_VIEW` | 배정 로봇에서 파생된 현재 결함 집계 |  |  |
| GET | `/api/v1/statistics/by-defect-type` | `DEFECT_STATUS_VIEW` | 배정 로봇에서 파생된 현재 결함 집계 |  |  |
| GET | `/api/v1/statistics/by-area` | `MISSION_VIEW` **AND** `DEFECT_STATUS_VIEW` | 배정 로봇의 Mission·Incident·결함 집계 |  |  |
| GET | `/api/v1/statistics/by-period` | `MISSION_VIEW` **AND** `DEFECT_STATUS_VIEW` | 배정 로봇의 Mission·Incident·결함 집계 |  |  |

`by-area`와 `by-period`의 두 권한은 OR가 아니라 모두 보유해야 하는 AND
조건이다. 각 원천 행은 SQL 집계 전에 현재 사용자의 유효한 로봇 배정 범위로
제한하며, 서로 다른 원천의 전체 집계를 만든 뒤 Java에서 권한 없는 값을
제거하지 않는다. 시스템 `ADMIN`은 전체 객체 범위를 갖지만 두 기능 권한 검사는
동일하게 적용한다.

## 10. 명령 API

| Method | Path | 권한 | 객체 범위 | CSRF | 감사 |
|---|---|---|---|:---:|---|
| GET | `/api/v1/robots/{robotId}/commands` | `ROBOT_COMMAND_VIEW` | `VIEWER+` |  |  |
| POST | `/api/v1/robots/{robotId}/commands` | `ROBOT_COMMAND_EXECUTE` | `OPERATOR+` | O | O |
| GET | `/api/v1/commands/{commandId}` | `ROBOT_COMMAND_VIEW` | 연결 로봇 `VIEWER+` |  |  |
| GET | `/api/v1/commands/{commandId}/results` | `ROBOT_COMMAND_VIEW` | 연결 로봇 `VIEWER+` |  |  |

명령 발행은 `Idempotency-Key`도 필수이며 프론트엔드는 MQTT Broker에 직접 접속하지 않는다.

## 11. 결함 API

| Method | Path | 권한 | 객체 범위 | CSRF | 감사 |
|---|---|---|---|:---:|---|
| GET | `/api/v1/defects/summary` | `DEFECT_STATUS_VIEW` | 배정 로봇 파생 집계 |  |  |
| GET | `/api/v1/defects/map` | `DEFECT_STATUS_VIEW` | 배정 로봇 파생 결함 |  |  |
| GET | `/api/v1/defects` | `DEFECT_STATUS_VIEW` | 배정 로봇 파생 결함 |  |  |
| GET | `/api/v1/defects/{defectId}` | `DEFECT_STATUS_VIEW` | 연결 로봇 `VIEWER+` |  |  |
| PATCH | `/api/v1/defects/{defectId}` | `DEFECT_STATUS_UPDATE` | 연결 로봇 `OPERATOR+` | O | O |
| PATCH | `/api/v1/defects/{defectId}/status` | `DEFECT_STATUS_UPDATE` | 연결 로봇 `OPERATOR+` | O | O |
| GET | `/api/v1/defects/{defectId}/observations` | `DEFECT_STATUS_VIEW` | 연결 로봇 `VIEWER+` |  |  |
| GET | `/api/v1/defects/{defectId}/history` | `DEFECT_STATUS_VIEW` | 연결 로봇 `VIEWER+` |  |  |
| GET | `/api/v1/defects/{defectId}/image-url` | `DEFECT_STATUS_VIEW` | 연결 로봇 `VIEWER+` |  | URL 미기록 |
| POST | `/api/v1/defects/merge` | `DEFECT_MERGE` | 모든 대상 로봇 `OPERATOR+` | O | O |
| POST | `/api/v1/defects/export` | `DEFECT_EXPORT` | 배정 로봇 데이터만 | O | O |

## 12. 보고서·일정 API

상태 전이 조건은 [`report-status-contract.md`](./report-status-contract.md)를 함께 적용한다.

| Method | Path | 권한 | 객체 범위 | CSRF | 감사 |
|---|---|---|---|:---:|---|
| GET | `/api/v1/reports/summary` | `REPORT_VIEW` | MVP 권한 단위 |  |  |
| GET | `/api/v1/reports` | `REPORT_VIEW` | MVP 권한 단위 |  |  |
| POST | `/api/v1/reports` | `REPORT_CREATE` | 근거 리소스 재검사 | O | O |
| GET | `/api/v1/reports/{reportId}` | `REPORT_VIEW` | MVP 권한 단위 |  |  |
| PATCH, DELETE | `/api/v1/reports/{reportId}` | `REPORT_UPDATE` | MVP 권한 단위 | O | O |
| POST | `/api/v1/reports/{reportId}/submit` | `REPORT_UPDATE` | MVP 권한 단위 | O | O |
| POST | `/api/v1/reports/{reportId}/approve` | `REPORT_APPROVE` | 자기 승인 금지 | O | O |
| POST | `/api/v1/reports/{reportId}/reject` | `REPORT_APPROVE` | 자기 버전 반려는 허용하되 사유 필수 | O | O |
| GET | `/api/v1/reports/{reportId}/versions` | `REPORT_VIEW` | MVP 권한 단위 |  |  |
| GET | `/api/v1/reports/{reportId}/download-url` | `REPORT_VIEW` | MVP 권한 단위 |  | URL 미기록 |
| GET, POST | `/api/v1/report-schedules` | GET `REPORT_VIEW`, POST `REPORT_APPROVE` | MVP 권한 단위 | POST O | 생성 |
| PATCH, DELETE | `/api/v1/report-schedules/{scheduleId}` | `REPORT_APPROVE` | MVP 권한 단위 | O | O |

## 13. 감사·파일·기기 업로드 API

| Method | Path | 접근 조건 | 객체 범위 | CSRF | 감사 |
|---|---|---|---|:---:|---|
| GET | `/api/v1/audit-logs` | `AUDIT_LOG_VIEW` | 전역 |  | `AUDIT_LOG_VIEWED` |
| GET | `/api/v1/files/{fileId}/download-url` | 원본 리소스 조회 권한 | 원본 범위 상속 |  | URL 미기록 |
| POST | `/api/v1/files/{fileId}/complete` | 인증 완료·업로드 소유자 | 본인 업로드 | O |  |
| POST | `/api/v1/images` | 유효한 로봇 `X-API-Key` | API Key의 로봇과 body `robot_id` 일치 | 해당 없음 | 업로드 결과 |
| POST | `/api/v1/images/{imageId}/link-incident` | 유효한 로봇 `X-API-Key` | API Key의 로봇·event 일치 | 해당 없음 | 연결 결과 |
| GET | `/api/v1/integration/mqtt-ingest-logs` | `SYSTEM_LOG_VIEW` | 전역 |  |  |

로봇 API Key 요청은 브라우저 세션과 CSRF 대상이 아니지만 Key의 로봇 범위, 요청 크기, Content-Type, rate limit을 별도로 검사한다.

## 14. SSE

| Method | Path | 접근 조건 | 이벤트 범위 |
|---|---|---|---|
| GET | `/api/v1/stream/robots` | `ROBOT_STATUS_VIEW` | 배정 로봇 |
| GET | `/api/v1/stream/defects` | `DEFECT_STATUS_VIEW` | 배정 로봇에서 파생된 결함 |
| GET | `/api/v1/stream/notifications` | 인증 완료 | 본인 알림 |
| GET | `/api/v1/stream/reports` | `REPORT_VIEW` | MVP 보고서 권한 범위 |

SSE 연결 시 한 번만 검사하지 않고, 세션 만료·폐기 시 연결을 종료한다. 이벤트를 발행할 때도 객체 범위를 적용하여 다른 사용자의 로봇 ID나 payload가 섞이지 않게 한다.

## 15. 인가 구현·테스트 기준

- URL 규칙은 공개·인증 상태 엔드포인트를 명시하고 마지막 규칙은 `denyAll`로 둔다.
- 업무 서비스 메서드는 세부 권한을 다시 검사한다.
- 객체 ID가 있는 요청은 repository 조회 후 현재 사용자의 객체 범위를 검사한다.
- 권한 변경 API는 자기 자신과 마지막 `ADMIN` 보호 규칙을 가진다.
- 각 API별 `ADMIN`, `MANAGER`, `OPERATOR`, 무권한, 미인증 조합을 매개변수 테스트한다.
- 다른 사용자에게 배정된 UUID를 넣는 IDOR 부정 테스트를 작성한다.
- 목록·요약·SSE에 다른 사용자의 객체가 섞이지 않는지 테스트한다.
- 모든 상태 변경 API의 CSRF 누락·불일치 테스트를 작성한다.
- 새 API가 이 문서에 등록되지 않으면 CI 권한 계약 테스트가 실패하게 한다.

공개 health·CSRF와 로그인·가입·MFA 상태 전이 API는 명시적 공개 예외다. 그 외
현재 구현된 컨트롤러 메서드는 클래스 또는 메서드의 `@PreAuthorize` 선언을 계약
테스트로 검사한다. 로컬 MQTT 명령 발행 API도 `ROBOT_COMMAND_EXECUTE`와 대상
로봇의 `OPERATOR+` 범위를 모두 요구한다.
