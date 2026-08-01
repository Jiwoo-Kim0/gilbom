# Day 7 Frontend 운영 이미지

기준일: 2026-08-01

## 1. 결정

Frontend image는 build와 serving 책임을 분리한 두 단계로 고정한다.

| 단계 | 기준 이미지 | 수행 작업 | Runtime 전달물 |
|---|---|---|---|
| `builder` | `node:24.18.0-alpine3.23` | `npm ci`, test, lint, TypeScript, Vite production build | `dist/` |
| `runtime` | `nginx:1.30.4-alpine` | 정적 파일과 reverse proxy 제공 | Nginx 설정, `dist/` |

builder는 package lock만으로 dependency를 설치하고 Frontend 단위 테스트 37건,
ESLint, `tsc`, Vite build를 순서대로 통과해야 한다. Vite sourcemap을 명시적으로
비활성화하고 build 뒤 `.map` 파일이 하나라도 생기면 image build를 실패시킨다.

runtime에는 `node`, `npm`, `npx`, TypeScript, Vite, `node_modules`, `src`, test,
E2E 파일을 복사하지 않는다. 정적 파일은 `root:nginx` 소유이며 디렉터리 `0555`,
파일 `0444`로 고정해 Nginx가 배포된 bundle을 수정할 수 없게 한다.

## 2. Non-root와 쓰기 경계

Nginx master와 worker를 모두 image의 `nginx` 사용자, UID/GID `101:101`로
실행한다. privileged port 대신 container HTTP 8080과 HTTPS 8443을 사용한다.

| 경로 | 접근 | 용도 |
|---|---|---|
| `/usr/share/nginx/html` | RO | 정적 bundle |
| `/etc/nginx` | RO | 고정 Nginx 설정 |
| `/etc/nginx/certs` | RO | Certbot 원본에서 동기화한 Nginx 인증서 |
| `/var/www/certbot` | RO | HTTP-01 challenge |
| `/tmp/nginx-conf.d` | RW | bootstrap·HTTP·HTTPS 중 선택된 runtime 설정 |
| `/tmp/nginx.pid` | RW | master PID |
| `/tmp/client_temp` | RW | request body 임시 파일 |
| `/tmp/proxy_temp` | RW | 일반 proxy 임시 파일 |
| `/tmp/*_temp` | RW | 비활성 FastCGI·uWSGI·SCGI 경로의 안전한 기본값 |
| 그 밖의 root filesystem | RO | 쓰기 금지 |

Compose는 `/tmp`만 `noexec,nosuid,nodev`, 32 MiB tmpfs로 제공하고
`read_only: true`, `cap_drop: ALL`, `no-new-privileges`를 적용한다. access log는
stdout, error log는 stderr로 보내므로 `/var/log/nginx` 쓰기는 필요 없다.
Nginx worker는 운영 기본 CPU quota와 SSE 동시 연결을 고려해 2개로 제한한다.

## 3. Health와 proxy

`GET /healthz`는 Backend와 무관한 Nginx 자체 readiness로 `200 ok`를 반환한다.
기존 `/health`는 로컬 호환성만 유지하며 Compose와 image healthcheck의 정본은
`/healthz`다.

운영 `auto` 모드는 인증서가 없으면 HTTP-01 challenge와 bootstrap health만
노출하고, 인증서가 있으면 8080의 애플리케이션 요청을 HTTPS로 308 redirect한다.
실제 정적 파일과 API·SSE proxy는 8443에서 제공한다. 로컬 Compose는 `off` 모드로
기존 HTTP 개발 경로를 유지한다. 운영 Compose는 `auto`를 상수로 주입하므로
`runtime.env`에서 `off`로 변경할 수 없다.

| 경로 | 정책 |
|---|---|
| `/api/v1/stream/` | SSE 전용, buffering·request buffering·proxy cache off, read/send timeout 1시간, `Cache-Control: no-cache` |
| `/api/v1/auth/csrf-token` | 기존 짧은 burst rate limit 유지 |
| `/api/` | 일반 Backend reverse proxy |
| 그 밖의 경로 | 정적 파일 또는 `index.html` SPA fallback |

Docker DNS `127.0.0.11`과 `resolve` upstream을 사용해 image build 시 Backend
hostname이 없어도 `nginx -t`를 실행할 수 있고, Backend container 재생성으로 IP가
바뀌어도 10초 안에 다시 해석한다.

SSE location은 일반 `/api/`보다 우선 매칭한다. `Connection` hop-by-hop header를
비우고 Backend의 cache header를 숨긴 뒤 edge에서 `no-cache`를 확정한다.

## 4. 정적 캐시

- `/assets/<name>-<content-hash>.*`: 1년 `max-age`
- `/index.html`: `no-cache` 재검증
- BrowserRouter deep link: `/index.html`로 내부 fallback

content hash asset은 내용이 바뀌면 URL도 바뀌므로 장기 캐시가 안전하다.
`index.html`은 새 배포의 hash 파일 목록을 담으므로 장기 캐시하지 않는다.

## 5. Secret과 bundle 경계

Frontend build argument는 브라우저 공개 식별자인
`VITE_NAVER_MAP_CLIENT_ID`만 허용한다. LLM·DB·MQTT·서명·암호화 Secret은 build
argument나 Vite 환경변수가 아니다.

runtime 검증기는 HTML·JS·CSS·JSON·SVG·TXT에서 대표 provider token, private key
본문, 서버 전용 Secret property 이름을 검사한다. image 환경변수도
password·secret·token·API key·signing key 이름이 있으면 실패한다.

이 검사는 알려진 패턴 유출을 차단하는 배포 gate다. 새로운 공개 build 변수를
추가할 때는 `VITE_*` 공개 가능성 검토와 bundle 검증 패턴을 함께 갱신한다.

## 6. 자동 검증과 결과

`scripts/verify-frontend-runtime-image.sh`는 전용 Docker network에 mock Backend와
Frontend를 띄우고 다음을 실제 요청으로 검사한다.

- UID/GID 101, capability 0, `NoNewPrivs=1`, read-only rootfs
- `/healthz`, BrowserRouter fallback
- 일반 `/api` upstream 응답
- SSE content type·본문·`no-cache`와 buffering 관련 설정
- index 재검증과 hash asset 장기 캐시
- 실제 `SIGQUIT` 전달과 10초 안의 정상 종료
- Node/npm/source/test/sourcemap/Secret 패턴 부재

`scripts/verify-frontend-tls.sh`는 자체서명 임시 인증서로 bootstrap 제한,
HTTP-01 파일 조회, HTTP 308 redirect, HTTPS API proxy, TLS 1.2·1.3와 Nginx
reload를 별도로 검증한다. 인증서와 ACME fixture는 기본적으로
`.artifacts/docker-bind-work` 아래에 생성한다. 이 경로는 Jenkins와 TLS DinD
daemon이 함께 보는 workspace라서 Jenkins container 전용 `/tmp`를 원격
daemon의 bind source로 잘못 넘기지 않는다.

| 검증 | 결과 |
|---|---|
| Frontend test | `37 tests, failures=0, skipped=0` |
| ESLint·TypeScript·Vite build | PASS, 2,500 modules transformed |
| Nginx config | `nginx -t` PASS |
| Non-root + read-only runtime | PASS |
| `/healthz`, `/api`, SSE, cache | PASS |
| Nginx graceful quit | PASS, SIGKILL 없음 |
| 최종 image 크기 | `65,220,614 bytes` |

주 진입 JavaScript는 약 539 kB로 Vite의 500 kB 경고 기준을 계속 넘는다. 이
작업에서는 source map과 불필요한 runtime 도구를 제거했지만 브라우저 초기 전송량
개선은 별도 code-splitting 작업으로 관리한다.
