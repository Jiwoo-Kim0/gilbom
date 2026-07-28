# 관리자 API 설계

## 접근 통제

`/api/v1/admin/**`는 인증이 완료된 서버 세션과
`ADMIN_SETTINGS_MANAGE` permission이 모두 필요하다. 상태 변경 요청에는 CSRF
검증을 추가한다. `ADMIN_SETTINGS_MANAGE`는 활성 시스템 `ADMIN` 역할에서만
계산되며 사용자 `ALLOW`나 사용자 정의 역할로 부여할 수 없다.

## 가입 승인

가입 요청은 역할 없이 `PENDING_APPROVAL`로 생성된다.

- 목록: `GET /api/v1/admin/registration-requests`
- 승인: `POST /api/v1/admin/registration-requests/{userId}/approve`
- 거절: `POST /api/v1/admin/registration-requests/{userId}/reject`

승인은 대상 행 잠금, 활성 역할 검증, 역할 부여, `ACTIVE` 전환, 필수 감사 INSERT를
하나의 트랜잭션에서 처리한다. 감사 INSERT가 실패하면 역할과 계정 상태가 모두
롤백된다. 거절은 운영상 비활성화인 `DISABLED` 및 소프트 삭제인 `DELETED`와
구분되는 `REJECTED` 상태로 남긴다.

## 사용자 권한과 객체 범위

역할, permission override, 로봇 배정 변경 API는 요청 목록을 현재 활성 목록 전체로
간주한다. 누락된 기존 행은 종료 시각·작업자·사유와 함께 비활성화하며 물리 삭제하지
않는다. 새 활성 관계를 적용한 뒤 필수 감사를 저장한다. DB trigger는 영향을 받은
사용자의 기존 인증 세션을 같은 트랜잭션에서 폐기한다.

override 만료 시각과 로봇 배정 만료 시각은 현재보다 미래여야 한다. 동일 permission과
effect 또는 동일 로봇은 한 요청에서 중복할 수 없다.

## 마지막 활성 ADMIN

관리자 감소 가능성이 있는 변경은 트랜잭션의 첫 DB 문장으로
`auth.lock_last_admin_guard()`를 호출한다. 이 transaction advisory lock 아래에서
현재 활성 시스템 ADMIN 수를 평가하고 다음 변경을 거부한다.

- 마지막 ADMIN 계정 비활성화
- 마지막 ADMIN의 시스템 ADMIN 역할 제거
- 마지막 ADMIN에게 유효한 `ADMIN_SETTINGS_MANAGE` DENY 부여
- 시스템 ADMIN 역할에서 `ADMIN_SETTINGS_MANAGE` 제거

애플리케이션 검사와 별도로 DB trigger도 같은 불변 조건을 강제한다.

## MFA 관리자 초기화

`POST /api/v1/admin/users/{userId}/mfa/reset`은 다음 조건을 모두 적용한다.

1. 작업자와 대상자가 달라야 한다.
2. 작업자의 현재 Argon2id 비밀번호를 재검증한다.
3. 작업자의 활성 TOTP secret을 복호화해 TOTP를 검증한다.
4. 성공한 TOTP time-step을 원자적으로 소비해 재사용을 막는다.
5. 대상의 현재 TOTP factor를 모두 `REVOKED`로 전환한다.
6. 대상의 인증·MFA 대기 세션을 모두 폐기한다.
7. 사유와 결과를 필수 감사 로그로 저장한다.

factor 폐기, 세션 폐기, 감사 INSERT는 하나의 트랜잭션이다. 감사 실패 시 전체가
롤백된다. 재인증 실패는 계정별 MFA 실패 제한에 누적하고 민감 입력 없이 별도
거부 감사 이벤트를 남긴다.
