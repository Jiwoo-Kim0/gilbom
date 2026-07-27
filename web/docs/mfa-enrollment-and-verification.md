# MFA 등록·검증 API 설계와 구현

## 1. 외부 계약

비밀번호 성공은 인증 완료가 아니다. 서버 세션이 다음 상태 중 하나일 때만 대응 API를
호출할 수 있고, `AUTHENTICATED` 전이는 TOTP 또는 복구 코드 성공 시에만 발생한다.

| 현재 상태 | 허용 API | 성공 결과 |
|---|---|---|
| `MFA_ENROLLMENT_REQUIRED` | `POST /api/v1/auth/mfa/totp/enroll` | pending factor와 등록 정보 |
| `MFA_ENROLLMENT_REQUIRED` | `POST /api/v1/auth/mfa/totp/confirm` | factor 활성화와 인증 완료 |
| `MFA_REQUIRED` | `POST /api/v1/auth/mfa/totp/verify` | 인증 완료 |
| `MFA_REQUIRED` | `POST /api/v1/auth/mfa/recovery` | 복구 코드 소비와 인증 완료 |

모든 API는 CSRF와 IP Rate Limit을 적용하고 `Cache-Control: no-store`를 반환한다.
TOTP·복구 코드 불일치, 이미 사용한 값, factor 상태 불일치는 모두
`401 AUTH_UNAUTHENTICATED`와 동일한 메시지로 축약한다. 5번째 실패에는 challenge를
폐기하고 `429`와 `Retry-After`를 반환한다.

## 2. TOTP 정책

- RFC 6238 호환 `HMAC-SHA1`
- 160-bit `SecureRandom` secret
- 숫자 6자리
- 30초 time-step
- 서버 현재 step과 앞뒤 1개 step 허용
- factor별 `last_successful_totp_time_step` 이하 값 거부

성공 step은 조건부 UPDATE와 DB 단조 증가 trigger로 기록한다. 같은 코드의 동시 요청도
한 요청만 UPDATE에 성공하므로 애플리케이션 인스턴스가 여러 개여도 재사용할 수 없다.

등록 API는 QR 렌더링에 필요한 표준 `otpauth://` URI와 수동 입력 키를 반환한다. QR
비트맵 생성은 프론트 표시 책임이며 URI·키를 브라우저 영속 저장소에 넣지 않는다.
등록을 다시 시작하면 기존 pending factor를 폐기하고 새 secret과 nonce를 발급한다.

## 3. Secret 저장

TOTP 검증에는 secret 원문이 다시 필요하므로 hash가 아닌 AES-256-GCM 인증 암호화를
사용한다. DB에는 ciphertext, 96-bit nonce, key version만 저장하며 외부 keyring은 환경의
Secret 저장소가 주입한다. 사용자 UUID, factor UUID, key version을 AAD로 묶어 다른
행으로 암호문을 이동하거나 변조하면 복호화가 실패한다.

생성·복호화한 `byte[]`는 사용 직후 0으로 덮는다. keyring 비활성화, key version 누락,
GCM tag 오류는 secret이나 원인을 노출하지 않는 `503 AUTH_MFA_UNAVAILABLE`로
fail-closed 처리한다.

## 4. 복구 코드

최초 등록 확인 성공 시 다음 형식의 코드 10개를 한 번만 반환한다.

```text
01-XXXX-XXXX-XXXX-XXXX
...
10-XXXX-XXXX-XXXX-XXXX
```

각 코드는 80-bit 난수와 1~10 위치를 포함한다. 위치로 검증할 hash 한 개를 선택해
공격자가 한 요청으로 Argon2id 검증 10회를 유발하지 못하게 한다. DB에는 중앙 비밀번호
설정과 같은 Argon2id encoded hash만 저장하며 원문은 저장하지 않는다.

사용 시 `used_at IS NULL` 조건부 UPDATE를 실행한다. 이미 사용한 코드도 hash 검증을 먼저
수행하고 외부에는 같은 401을 반환하므로 사용 여부를 응답 시간·메시지로 구별하기 어렵다.

## 5. 성공 트랜잭션

MFA 성공은 다음 순서로 처리한다.

1. 현재 5분 challenge와 계정 상태를 다시 확인한다.
2. TOTP 성공 step을 전진시키거나 복구 코드 `used_at`을 원자적으로 기록한다.
3. 등록 확인이면 factor 활성화와 복구 코드 hash 10개 저장을 완료한다.
4. 필수 MFA 감사 로그와 로그인 성공 감사 로그를 저장한다.
5. 계정 로그인 실패 상태와 MFA 실패 bucket을 초기화한다.
6. 현재 세션 외 해당 사용자의 모든 인증·pre-auth 세션을 폐기한다.
7. 현재 세션을 `AUTHENTICATED`로 승격하고 절대 만료를 8시간으로 설정한다.
8. Servlet 세션 ID와 CSRF 토큰을 모두 교체하고 권한 snapshot을 저장한다.

DB 작업이나 필수 감사 저장이 실패하면 factor·코드 소비와 세션 승격을 함께 rollback한다.
세션 또는 CSRF 교체가 실패하면 현재 세션도 폐기해 부분 인증 상태를 남기지 않는다.

## 6. 민감정보 규칙

- TOTP secret, `otpauthUri`, 제출 코드, 복구 코드, hash, 세션 ID, CSRF는 로그·감사
  metadata에 기록하지 않는다.
- 검증 오류의 `rejectedValue`에는 `code`와 `recoveryCode`를 반사하지 않는다.
- 복구 코드는 등록 확인 성공 응답에서만 원문으로 노출한다.
- 프론트는 CSRF만 메모리에 보관하고 인증정보를 `localStorage`나 `sessionStorage`에
  저장하지 않는다.

## 7. 검증 범위

단위·MVC 테스트는 RFC 6238 벡터, ±1 step, 동일 step 재사용, 복구 코드 10개 형식,
사용된 복구 코드 hash 검증, 5회 challenge 폐기, CSRF 필수, `no-store`, 민감 입력
비반사, 성공 전 소비 순서와 인증 응답을 검증한다.
