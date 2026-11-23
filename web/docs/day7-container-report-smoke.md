# Day 7 컨테이너 보고서 전체 흐름 Smoke

기준일: 2026-07-31

## 목표와 격리 원칙

이 smoke는 개발 서버나 host에서 실행 중인 Backend에 연결하지 않는다.
`compose.prod.yaml`을 정본으로 사용하고 `compose.smoke.yaml`은 자동 재시작을
끄고 검증 목적 label과 최초 관리자 bootstrap만 더한다. 실행마다 timestamp와
PID가 포함된 별도 Compose project를 만들기 때문에 PostgreSQL, PDF, 이미지,
Mosquitto volume은 모두 빈 상태에서 시작한다.

종료·실패·SIGTERM 때 `down --volumes --remove-orphans`를 실행하고 임시
Secret·인증 session 상태·복사한 PDF도 삭제한다. 운영 volume에는 이 명령을
사용하지 않는다.

## 산출물

- `compose.smoke.yaml`: 운영 topology/image를 유지하는 일회성 override
- `scripts/container-report-smoke.sh`: build, 기동, fixture, API, PDF, 재기동,
  log 경계를 묶은 실행기
- `scripts/container-report-smoke-client.py`: 외부 package가 없는 Python
  표준 라이브러리 HTTP/SSE client
- `scripts/fixtures/container-report-smoke.sql`: 고정 순찰·Mission·결함 fixture

테스트 client는 Nginx가 임의로 publish한 Frontend port만 호출한다. Backend와
PostgreSQL host port는 만들지 않는다. `ec2` profile의 Secure
`__Host-GILBOM_SESSION` cookie를 HTTP smoke에서도 브라우저 보안 설정을
약화하지 않고 시험하기 위해, client가 `Set-Cookie`를 수집해 다음 요청에
명시적으로 전달한다.

## 필수 fallback 흐름

스크립트가 다음 경계를 순서대로 확인한다.

1. Backend·Frontend 운영 image를 빌드하며 image 내부 test와 production build를
   통과한다.
2. 새 PostgreSQL volume에서 41개 Flyway migration이 모두 성공했고 보고서가
   0건인지 확인한다.
3. 완료 Mission 1건, 결함·관측 1건과 연결 evidence를 transaction으로 넣는다.
4. Bootstrap 작성자가 최초 비밀번호를 바꾸고 TOTP를 등록한다.
5. 별도 MANAGER 검토자도 로그인과 TOTP 등록을 거친다.
6. LLM 비활성 상태에서 `generationMethod=LLM` 생성 요청이
   `TEMPLATE`/`POLICY_DISABLED` DRAFT v1로 fallback되는지 확인한다.
7. DRAFT 수정으로 v2가 되고 `report.updated` SSE를 받은 뒤 REST 상세를 다시
   조회해 동일한 v2인지 확인한다.
8. 제출, 검토자 반려, 작성자 보완 v3, 재제출, 작성자와 다른 검토자 승인을
   수행한다.
9. APPROVED와 승인 version 3, PDF version 3과 SHA-256을 확인한다.
10. 승인 보고서 수정·삭제가 `REPORT_STATE_CONFLICT`로 거부되는지 확인한다.
11. 별도 DRAFT를 soft-delete하고 기본 목록에서 제외되는지 확인한다.
12. PDF를 실제 `/app/data/report-pdf` named volume에서 복사해 `%PDF` magic과
    파일 SHA-256을 DB metadata와 대조한다.
13. Backend를 재기동한 뒤 JDBC session으로 상세/version을 다시 조회하고 PDF
    byte가 그대로인지 확인한다.
14. 생성한 Secret, Authorization header, prompt·LLM response canary가
    Backend/Nginx log에 없는지 재검사한다.

수정·반려·승인은 모두 API transaction을 사용한다. 다음 단계는 직전 응답의
상태와 version을 확인한 뒤에만 실행하므로 중간 transaction 상태를 성공으로
간주하지 않는다.

## 실행

필수 smoke는 실제 LLM Secret 없이 실행할 수 있다.

```sh
sh scripts/container-report-smoke.sh
```

이미 같은 Git revision의 운영 image를 빌드한 CI에서는 중복 build만 생략할 수
있다.

```sh
REPORT_SMOKE_SKIP_BUILD=true sh scripts/container-report-smoke.sh
```

임시 Secret과 인증서의 bind source는 기본적으로
`.artifacts/docker-bind-work` 아래에 생성한다. Jenkins의 Docker client와 DinD
daemon이 공유하는 workspace를 사용하므로 container 전용 `/tmp` 경로 차이로
bind mount가 실패하지 않는다.

Smoke 환경은 운영 Compose의 필수 공개 URL 계약을 검증하기 위해
`GILBOM_STORAGE_UPLOAD_URL=https://report-smoke.example.invalid/api/v1/images`를
주입한다. `.invalid` 주소는 외부 업로드에 사용하지 않고 Compose 렌더링과
Backend 설정 계약을 위한 고정 fixture다.

성공 출력에는 Secret, password, CSRF/TOTP, session cookie, 보고서 본문을
포함하지 않는다. 실패 시에도 고정 오류 코드와 제한된 container log만 사용한다.

## 조건부 GMS canary

실제 provider 호출은 기본 `SKIPPED`다. 아래 조건을 모두 명시한 승인된
환경에서만 필수 fallback smoke 이후 최소 fixture로 실행한다.

```sh
REPORT_SMOKE_GMS_CANARY=true \
GILBOM_LLM_API_KEY_SECRET_FILE=/secure/path/gilbom_llm_api_key \
GILBOM_LLM_MODEL=gpt-5.4-mini \
GILBOM_LLM_DAILY_BUDGET=1 \
GILBOM_LLM_POLICY_APPROVED=true \
GILBOM_LLM_TRAINING_EXCLUSION_APPROVED=true \
GILBOM_LLM_DATA_RETENTION_APPROVED=true \
GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED=true \
sh scripts/container-report-smoke.sh
```

API key 값은 environment나 명령 인자가 아니라 file로만 받는다. script는 권한이
제한된 임시 복사본을 `/run/secrets/gilbom_llm_api_key`로 mount하고 종료 시
삭제한다. 일일 request limit과 동시 요청 수를 1로 고정하므로 retry도 두 번째
provider 호출을 예약할 수 없다. canary는 결과가 fallback이 아닌 `LLM`인지,
승인 model·prompt version인지 확인하고 quota ledger가 정확히 성공 1건인지
검증한다.

Compose network만으로 GMS hostname allowlist를 강제할 수 없으므로
`GILBOM_LLM_OUTBOUND_ALLOWLIST_APPLIED=true`는 host firewall, security group
또는 승인 egress proxy가 실제로 적용된 경우에만 설정한다.

## 보안·성능 판단

- Secret과 인증 상태는 mode `0700/0600` 임시 경로에만 두고 image build
  argument, image environment, Git, Jenkins artifact에 넣지 않는다.
- fixture SQL에는 password 원문이 없고 bootstrap hash를 별도 검토자에 복제해
  실제 로그인·MFA 흐름을 통과한다.
- 고유 project와 자동 volume 삭제가 다른 개발·운영 데이터를 건드리는 위험을
  없앤다.
- 운영 image의 non-root, read-only rootfs, capability drop, resource/log 상한을
  변경하지 않는다.
- Jenkins는 기존 integration stack을 먼저 내려 DB 두 개의 동시 memory 점유를
  피하고, 이미 빌드한 image를 재사용한다.
- SSE는 한 연결만 열고 provider canary도 최대 한 요청으로 제한해 smoke 자체의
  부하와 비용을 작게 유지한다.

## 검증 결과

2026-07-31에 운영 Backend/Frontend image를 다시 빌드했다. Frontend test
37건은 실패 0, skipped 0이었고 lint와 production build가 통과했다. 새 project와
빈 volume에서 필수 Template fallback 전체 흐름, SSE/REST 수렴, soft-delete,
승인 PDF 실제 파일/hash, Backend 재기동 후 DB·PDF 영속성, runtime log Secret
경계가 모두 PASS였다.

실제 GMS canary는 운영 API key와 네 가지 승인 플래그를 제공하지 않았으므로
의도대로 SKIPPED였다.

정상 보고서 흐름에 이어 장애·자동 재시작·전체 volume 영속성까지 검증하려면
이 smoke의 상위 실행기인 `scripts/container-resilience-smoke.sh`를 사용한다.
상세 시나리오는
[`day7-resilience-verification.md`](day7-resilience-verification.md)에 있다.
