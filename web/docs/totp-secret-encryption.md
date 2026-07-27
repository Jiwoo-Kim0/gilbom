# TOTP Secret 암호화 저장

## 목적

TOTP 검증은 동일한 Secret 원문이 다시 필요하므로 비밀번호처럼 단방향 hash로
저장할 수 없다. 대신 애플리케이션 외부에서 주입한 AES-256 key로 인증된 암호화를
수행하고 DB에는 다음 envelope만 저장한다.

MFA 등록 여부는 `auth.admin_user`에 별도 boolean으로 중복 저장하지 않는다.
`auth.mfa_factor`에 `factor_status=ACTIVE`인 현재 TOTP factor가 존재하는지가 단일 기준이다.

| DB 컬럼 | 애플리케이션 값 | 역할 |
|---|---|---|
| `secret_ciphertext` | `EncryptedTotpSecret.ciphertext` | 암호문과 128-bit GCM tag |
| `secret_nonce` | `EncryptedTotpSecret.nonce` | 암호화마다 새로 생성하는 96-bit nonce |
| `secret_key_version` | `EncryptedTotpSecret.keyVersion` | 복호화 key 선택과 rotation |
| `encryption_algorithm` | `AES-256-GCM` | 저장 알고리즘 식별 |

## 암호화 경계

- `AES/GCM/NoPadding`, 256-bit key, 96-bit random nonce, 128-bit tag를 사용한다.
- 같은 Secret을 암호화해도 nonce와 ciphertext가 매번 달라야 한다.
- `userId`, `factorId`, `keyVersion`, 도메인 문자열을 AAD로 인증한다.
- 다른 사용자나 factor로 ciphertext를 옮기거나 ciphertext·nonce를 변조하면
  GCM tag 검증이 실패한다.
- 오류 메시지는 tag, key version 존재 여부, ciphertext, Secret을 노출하지 않는다.
- 호출자는 암호화 직후 plaintext 배열과 TOTP 검증 직후 복호화 배열을 `Arrays.fill`
  등으로 지워야 한다.

## 외부 keyring

키는 Git, Docker image, migration, DB에 넣지 않는다. 실행 환경의 Secret 저장소가
다음 환경 변수를 주입한다.

```text
MFA_TOTP_SECRET_ENCRYPTION_ENABLED=true
MFA_TOTP_SECRET_ACTIVE_KEY_VERSION=2
MFA_TOTP_SECRET_KEYRING=1=<base64-32-byte-key>;2=<base64-32-byte-key>
```

`.env.example`에는 형식만 있고 실제 key는 없다. 로컬은 기능 사용 전까지
`enabled=false`를 허용하지만 `ec2` profile은 `required=true`라서 disabled 또는
유효한 active key가 없는 상태로 기동하지 않는다.

## key rotation

1. 기존 key version을 제거하지 않은 채 새 version key를 Secret 저장소에 추가한다.
2. `MFA_TOTP_SECRET_ACTIVE_KEY_VERSION`을 새 version으로 변경한다.
3. 신규·재등록 factor는 새 version으로 암호화한다.
4. 기존 행은 저장된 `secret_key_version`으로 복호화한 후 새 key로 재암호화한다.
5. DB에 이전 version 행이 0개이고 rollback 기간이 지난 후에만 이전 key를 제거한다.

key version 누락은 데이터 손실 위험이 있는 운영 설정 오류이므로 복호화를 우회하지
않고 fail-closed 처리한다.

## 코드 위치

- `TotpSecretEncryptionService`: MFA 기능이 의존하는 암호화 경계
- `AesGcmTotpSecretEncryptionService`: JCA AES-GCM 구현
- `TotpSecretEncryptionProperties`: 외부 keyring 설정
- `EncryptedTotpSecret`: DB 세 컬럼과 대응하는 방어적 값 객체
