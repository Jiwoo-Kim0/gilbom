# 비밀번호와 초기 관리자

## 1. 비밀번호 처리 경계

모든 신규 비밀번호는 `PasswordHashingService.hashNewPassword`를 거친다.

- 회원가입
- 초기 관리자 bootstrap
- 초기 비밀번호 변경
- 마이페이지 비밀번호 변경
- 관리자 비밀번호 재설정

서비스는 비밀번호를 10~24 Unicode code point로 검증하고 trim, 대소문자 변경,
Unicode 정규화, 잘라내기를 하지 않는다.

`PasswordHashingConfiguration`이 애플리케이션의 유일한 encoder bean을 생성한다.

| 파라미터 | 값 |
|---|---:|
| algorithm | Argon2id |
| `{id}` prefix | `{argon2}` |
| salt length | 16 byte |
| hash length | 32 byte |
| memory | 19 MiB (`19456` KiB) |
| iterations | 2 |
| parallelism | 1 |

허용 encoder map에는 Argon2id만 존재한다. BCrypt hash, `{bcrypt}` prefix와 자동
fallback은 제공하지 않는다.

## 2. 존재하지 않는 계정 검증

`PasswordHashingService`는 프로세스 시작 시 `SecureRandom`으로 24자 dummy
비밀번호를 한 번 만들고 실제 계정과 같은 encoder로 hash한다. 임시 문자 배열은 hash
생성 직후 지우고 encoded dummy hash만 메모리에 유지한다.

로그인 계정 조회 결과가 없으면 요청마다 dummy hash를 생성하지 않고 이 encoded hash를
재사용해 `matches`를 실행한다. 반환값은 항상 실패지만 실제 계정과 같은 memory,
iteration, parallelism 비용을 사용하므로 계정 존재 여부에 따른 큰 시간 차이를 줄인다.

로그인 서비스는 직접 `PasswordEncoder.matches`를 호출하지 않고 반드시
`verifyAgainstNullableHash(rawPassword, nullableStoredHash)`를 사용한다.

## 3. 초기 관리자 bootstrap

bootstrap은 기본적으로 비활성화돼 있다. 빈 DB 최초 구성 시에만 다음 값을 외부
Secret으로 주입한다.

```text
INITIAL_ADMIN_BOOTSTRAP_ENABLED=true
INITIAL_ADMIN_EMAIL=<initial administrator email>
INITIAL_ADMIN_PASSWORD=<10-24 character temporary password>
INITIAL_ADMIN_DISPLAY_NAME=<display name>
INITIAL_ADMIN_USER_CODE=<unique code, max 30>
```

실제 값은 코드, Git 추적 파일, Docker image, migration에 넣지 않는다. 로컬의 ignored
`.env` 또는 운영 Secret Manager가 런타임 환경변수로 전달한다.

최초 생성 트랜잭션은 다음을 함께 처리한다.

1. 이메일·비밀번호·표시 이름·사용자 코드 검증
2. Argon2id hash 생성
3. `ACTIVE`, `account_origin=BOOTSTRAP`,
   `password_change_required=true` 계정 INSERT
4. 시스템 `ADMIN` 역할을 primary role로 부여
5. 민감값이 없는 `AUTH_INITIAL_ADMIN_CREATED` 감사 로그 INSERT

역할 또는 감사 로그 저장이 실패하면 계정 INSERT도 rollback한다.

## 4. 멱등성과 선점 방어

- 동일한 normalized email의 `BOOTSTRAP` 계정이 이미 있으면 비밀번호·상태·역할을
  덮어쓰지 않고 `ALREADY_PRESENT`로 종료한다.
- 같은 이메일이 공개 회원가입 등 다른 `account_origin`으로 존재하면 자동 승격하지
  않고 애플리케이션 기동을 실패시킨다.
- 다른 이메일의 bootstrap 계정이 이미 있어도 두 번째 초기 관리자를 만들지 않는다.
- bootstrap 계정이 아니더라도 활성 `ADMIN`이 이미 있으면 초기화가 끝난 시스템으로
  판단하여 새 특권 계정을 만들지 않는다.
- DB partial UNIQUE index도 `account_origin=BOOTSTRAP` 계정을 하나로 제한한다.
- 동시 기동은 normalized email `ON CONFLICT DO NOTHING`과 생성 origin 재확인으로
  중복 INSERT를 방지한다.

최초 생성이 확인되면 bootstrap 환경변수를 비우고
`INITIAL_ADMIN_BOOTSTRAP_ENABLED=false`로 되돌린다.

## 5. 초기 로그인 상태

```text
초기 비밀번호 로그인
→ 202 PASSWORD_CHANGE_REQUIRED
→ 초기 비밀번호 변경
→ 202 MFA_ENROLLMENT_REQUIRED
→ TOTP 등록·확인
→ AUTHENTICATED
```

bootstrap은 계정을 만드는 작업일 뿐 인증 완료 세션을 만들지 않는다.

## 6. 부하 테스트

일반 단위 테스트에서는 부하 테스트를 제외하며 다음 전용 task로 실행한다.

```bash
./gradlew passwordHashLoadTest --no-daemon
```

기본 시나리오는 warm-up 후 4회 hash 생성과 동시성 2에서 12회 검증을 수행해 encode
p50/p95, verify p50/p95, wall time, process CPU time, 근사 peak heap을 출력한다.
운영 인스턴스와 같은 CPU·메모리 제한에서도 다시 측정하고, 로그인 IP/account
Rate Limit과 서버 동시성 제한을 함께 조정한다.

2026-07-27 개발용 Docker JDK 21 환경의 기준 측정은 다음과 같다.

| 항목 | 측정값 |
|---|---:|
| encode p50 / p95 | 30 ms / 31 ms |
| verify p50 / p95 | 21 ms / 31 ms |
| 동시성 / 검증 횟수 | 2 / 12 |
| wall time / process CPU time | 270 ms / 640 ms |
| 근사 peak heap | 199 MiB |

이 값은 짧은 개발 환경 기준선이며 운영 수용량을 보장하지 않는다. 배포 인스턴스의 CPU,
container memory limit, 예상 동시 로그인 수를 적용한 장시간 부하 테스트 결과를 기준으로
Rate Limit과 동시 요청 상한을 확정한다.
