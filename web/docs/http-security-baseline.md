# HTTP 요청 제한과 기본 보안 헤더

## 1. 적용 범위

이 기준은 인증 여부와 관계없이 관리자 SPA의 정적 파일과 모든 Backend HTTP 응답에
적용한다. Nginx가 외부 경계에서 먼저 요청을 제한하고, Backend는 프록시 우회·설정
오류·`Content-Length`가 없는 요청에도 같은 기준을 독립적으로 적용한다.

## 2. 요청 크기 제한

| 계층 | 항목 | 기본 상한 |
|---|---|---:|
| Nginx | 전체 요청 본문 | `1m` |
| Nginx | 일반 요청 헤더 buffer | `1k` |
| Nginx | 큰 요청 헤더 buffer | `2 x 8k` |
| Spring/Tomcat | 전체 요청 헤더 | `16KB` |
| Spring/Tomcat | form 요청 본문 | `1MB` |
| Spring multipart | 파일 1개 | `500KB` |
| Spring multipart | multipart 요청 전체 | `1MB` |
| Backend filter | 모든 요청 본문 | `1MB` |

Backend filter는 신뢰할 수 있는 `Content-Length`가 상한을 넘으면 body를 읽기 전에
거부한다. 길이가 없거나 실제보다 작게 선언된 chunked/streaming 요청은
`ServletInputStream`에서 실제 읽은 byte를 계수해 거부한다. 애플리케이션까지 도달한
초과 요청은 `413 COMMON_PAYLOAD_TOO_LARGE` 공통 오류 계약을 사용한다.

전역 상한은 필드 검증을 대신하지 않는다. 로그인 DTO의 사용자명·비밀번호·TOTP 등은
Day 3 구현에서 더 작은 필드별 문자·byte 상한을 별도로 적용한다. 비밀번호는 상한을
넘었을 때 잘라서 해시하지 않고 요청을 거부한다.

## 3. 기본 응답 헤더

| 헤더 | 정책 | 목적 |
|---|---|---|
| `Content-Security-Policy` | same-origin 기본, object/frame 차단 | 허가하지 않은 script·연결·삽입 차단 |
| `X-Content-Type-Options` | `nosniff` | MIME 추측 차단 |
| `X-Frame-Options` | `DENY` | 구형 브라우저 클릭재킹 방어 |
| `Referrer-Policy` | `no-referrer` | 관리자 URL·식별 정보의 외부 전달 차단 |
| `Permissions-Policy` | camera 등 비사용 기능 차단 | 불필요한 브라우저 권한 축소 |
| `Strict-Transport-Security` | `max-age=31536000` | HTTPS 접속의 평문 downgrade 방지 |

CSP의 `frame-ancestors 'none'`가 현대 브라우저의 주 클릭재킹 방어이며
`X-Frame-Options: DENY`는 호환성 방어다. TOTP QR과 브라우저 생성 이미지를 위해
`img-src`에는 `data:`와 `blob:`만 추가 허용한다.

외부 S3, 지도, 모니터링 등 새 Origin이 필요해지면 기능 구현과 같은 변경에서 정확한
Origin만 CSP에 추가한다. wildcard 또는 CSP 전체 비활성화로 우회하지 않는다.

HSTS는 HTTPS 응답에서만 브라우저가 신뢰한다. Backend는 TLS 요청에만 추가하고,
Nginx의 헤더는 로컬 HTTP에서는 무시되며 TLS가 종료되는 운영 공개 경로에서 효력이
있다. `includeSubDomains`와 `preload`는 전체 도메인 운영 현황을 확인하기 전에는
활성화하지 않는다.

## 4. 운영 코멘트

- Nginx와 Backend 상한은 의도적으로 중복한다. 외부 트래픽은 저비용 edge 차단을 받고,
  내부 직접 접근과 chunked 요청은 Backend에서 다시 검증한다.
- Backend 환경변수로 상한을 낮출 수 있지만 Nginx의 `1m`은 공개 경로의 hard ceiling이다.
  이를 높여야 하는 기능은 Nginx와 Backend 값을 같은 변경에서 함께 검토한다.
- Nginx가 자체 생성한 `413`은 애플리케이션의 JSON 오류 body를 거치지 않을 수 있다.
  HTTP 상태는 동일하며 프론트엔드는 `413` 자체를 공통 처리해야 한다.
- 요청 헤더 상한에는 세션·CSRF Cookie도 포함된다. 인증 구현 후 실제 최댓값이
  기본 상한에 충분히 여유가 있는지 통합 테스트한다.
- CSP나 요청 상한을 완화할 때에는 필요한 endpoint·Origin·payload 근거를 함께
  기록하고 전역 무제한 설정은 허용하지 않는다.
