# 공개 회원가입과 이메일 중복 확인

## 1. API 계약

두 API는 인증 완료를 요구하지 않지만 익명 세션의 `X-CSRF-TOKEN`을 요구한다.

| Method | Path | 성공 | 제한 |
|---|---|---:|---|
| POST | `/api/v1/auth/email-availability` | 200 | IP당 1분 20회 |
| POST | `/api/v1/auth/registrations` | 201 | IP당 1시간 5회 |

이메일을 URL query string에 넣지 않아 브라우저 history와 기본 proxy access log 노출을
줄인다. 정상·오류·제한 응답은 모두 `Cache-Control: no-store`를 사용하고 제한 응답은
초 단위 `Retry-After`를 함께 반환한다.

## 2. 입력 계약

- 이메일: ASCII mailbox와 ASCII/Punycode domain 형식, 최대 254자
- 이메일 앞뒤 공백: 제거하지 않고 거부
- 중복 기준: `Locale.ROOT` 소문자와 같은 DB generated `normalized_email`
- 비밀번호: 원문 Unicode code point 기준 10~24자
- 비밀번호 변환: trim, 대소문자 변경, Unicode 정규화, 잘라내기 없음
- 표시 이름: 1~100 Unicode code point, 앞뒤 공백과 제어문자 거부

DNS·MX 조회나 메일 사업자별 `+tag`·점 별칭 병합은 가입 동기 조건으로 사용하지 않는다.

## 3. 계정 생성

가입 계정은 다음 상태로 한 트랜잭션에서 생성한다.

```text
account_status=PENDING_APPROVAL
is_active=false
account_origin=PUBLIC_REGISTRATION
password_change_required=false
password_changed_at=<가입 시각>
role=<없음>
```

사용자가 직접 정한 비밀번호이므로 초기 관리자처럼 비밀번호 변경을 강제하지 않는다.
대신 관리자가 승인하기 전에는 로그인할 수 없고 어떤 업무 역할도 자동 부여하지 않는다.
성공 응답은 `accountStatus=PENDING_APPROVAL`, `nextAction=LOGIN`만 반환하며 인증 세션,
세션 ID, CSRF 토큰 또는 권한을 반환하지 않는다.

비밀번호는 초기 관리자와 동일한 `PasswordHashingService`를 사용해
`{argon2}$argon2id$...` 전체 encoded hash로만 저장한다.

## 4. 중복과 경쟁 조건

중복 확인 API는 화면 UX 보조 정보일 뿐 예약 기능이 아니다.

```text
중복 확인 available=true
→ 다른 요청이 같은 이메일로 먼저 INSERT 가능
→ 가입 INSERT의 UNIQUE(normalized_email)가 최종 거부
→ 외부에는 409 AUTH_EMAIL_ALREADY_REGISTERED
```

가입 저장은 `ON CONFLICT DO NOTHING RETURNING` 후 normalized email을 다시 조회한다.
따라서 DB 제약 이름, SQL 또는 내부 예외를 노출하지 않으면서 동시 요청이 같은 409로
수렴한다.

## 5. Rate Limit과 IP 처리

Rate Limit bucket은 PostgreSQL에서 원자적으로 upsert하여 재시작·다중 인스턴스에서도
공유한다. IP 원문은 저장하지 않고 다음 key만 저장한다.

```text
HMAC-SHA-256(externalKey, "ip:" + clientIp)
```

운영 `ec2` profile은 최소 32-byte Base64 외부 key가 없으면 시작을 거부한다. 로컬은
설정이 없을 때 프로세스용 임시 key를 생성할 수 있지만 재시작 전후 bucket이 이어지지
않으므로 통합 검증 시에는 고정된 로컬 Secret을 주입한다.

Docker에서는 Tomcat이 기본 internal proxy 대역의 Nginx가 전달한
`X-Forwarded-For`만 해석한다. 애플리케이션은 임의 요청 헤더를 직접 신뢰하지 않고
`request.getRemoteAddr()`에 확정된 주소만 사용한다.

만료 bucket은 인덱스를 이용해 10분 간격, 최대 1,000건씩 삭제한다.

## 6. 감사와 민감정보

가입 성공은 계정 생성과 같은 트랜잭션에
`AUTH_PUBLIC_REGISTRATION_CREATED`를 한 번 기록한다. 감사 snapshot은 승인 대기 상태,
생성 경로, 역할 미부여 여부만 포함한다.

이메일, 비밀번호 원문·hash·salt·파라미터, Rate Limit HMAC key와 IP 원문은 일반 로그나
감사 snapshot에 기록하지 않는다.
