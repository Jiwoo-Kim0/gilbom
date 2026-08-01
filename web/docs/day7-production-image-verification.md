# Day 7 이미지 최소 권한·Secret 정적 검사

기준일: 2026-07-31

## 1. 검증 경계

`scripts/verify-production-images.sh`를 Backend와 Frontend 운영 이미지의 단일
보안 gate로 사용한다. Dockerfile 선언, 저장된 image, 실제 container, 실행 로그를
서로 다른 경계에서 검사한다.

| 경계 | 주요 검사 |
|---|---|
| image config·history | `USER`가 root가 아님, Secret 성격의 `ARG`·`ENV`, `.env`·`secrets/` 경로와 실제 Secret 값 부재 |
| `docker save` archive | 실제 Secret 값 부재, layer 파일 목록에 `.env`·`secrets/` 부재 |
| Backend container | UID/GID 1883, capability 0, `NoNewPrivs=1`, read-only rootfs, 허용 volume과 `/tmp`만 쓰기 가능 |
| Frontend container | UID/GID 101, capability 0, `NoNewPrivs=1`, read-only rootfs, `/tmp`만 쓰기 가능 |
| runtime 내용 | Backend의 JDK·Gradle·Git·source·test 부재, Frontend의 Node·npm·`node_modules`·source map·test 부재 |
| Frontend bundle | 대표 provider token, private key, 서버 전용 Secret property 이름과 실제 Secret 값 부재 |
| container logs | 실제 Secret, Authorization·prompt·LLM 응답 canary, API key 성격의 구조 부재 |

Image `Config.User`만 검사하면 Compose override나 entrypoint 동작을 놓칠 수 있다.
따라서 role별 runtime 검증기는 제한 옵션으로 container를 직접 실행한 뒤
`id -u`, `/proc/self/status`, 실제 쓰기 성공·실패를 확인한다.

## 2. 실제 Secret 대조

`SECRET_SCAN_DIR` 아래 non-empty 파일의 모든 값을 private 임시 pattern 목록으로
수집한다. PEM header/footer는 값이 아니므로 제외한다. 8자 미만의 non-empty 값은
오탐 없이 archive와 대조할 수 없고 운영 Secret으로도 지나치게 약하므로 검사를
즉시 실패시킨다. 수집한 원문은
stdout, 실패 메시지, SBOM, CVE artifact에 출력하지 않고 검사 종료 시 삭제한다.

이 pattern을 다음과 exact match로 대조한다.

- `docker image inspect`
- `docker history --no-trunc`
- `docker save` 전체 archive
- 실행 중 Backend·Frontend의 전체 container log

따라서 LLM API key, 저장소 서명 key, DB·MQTT password/verifier와 다른 file
Secret이 image 또는 log에 복사되면 즉시 실패한다.

`.env`는 archive의 raw byte 문자열을 찾지 않고 각 layer의 파일 경로를 검사한다.
Docker Engine storage backend에 따라 `docker save`의 layer blob이 비압축 tar 또는
gzip tar일 수 있으므로 검증기는 두 형식을 모두 열어 같은 경로 정책을 적용한다.
Base image와 라이브러리에는 `.envsh`나 dotenv 관련 일반 문자열이 존재할 수 있어
raw substring 검사는 정상 image도 실패시키기 때문이다. 실제 `.env`,
`.env.*`, `secrets/` 파일 경로는 허용하지 않는다.

`docker save` archive, 압축 layer blob과 해제된 tar는 동시에 존재할 수 있으므로
작은 system `/tmp`를 사용하지 않는다. 기본 작업 위치는 Git에서 제외된
`.artifacts/image-verify-work/`이며 `IMAGE_VERIFY_WORK_ROOT`로 CI workspace 안의
다른 경로를 지정할 수 있다. 각 image 검사 완료 즉시 archive를 삭제하고 성공·실패
모두 종료 trap에서 private pattern과 남은 layer 작업 파일을 제거한다.

## 3. 로그 검사

`logs` mode는 실행 중인 Backend와 Frontend container ID를 받아 다음 요청을
Frontend proxy 경유로 보낸다.

1. 임의 Authorization Bearer canary를 포함한 health 요청
2. 임의 prompt·LLM response canary를 포함한 JSON 요청
3. 요청 완료 뒤 두 container의 전체 stdout/stderr 검사

실제 Secret이나 canary가 한 글자도 그대로 남으면 실패한다. 값이 알려지지 않은
유출도 찾기 위해 Authorization, prompt/body/raw, LLM request/response
body/content/raw, API key 형식도 함께 차단한다. 검사는 Secret 값을 오류 메시지에
반사하지 않는다.

실제 provider 응답 원문은 운영 smoke에서 LLM 호출을 만들지 않는다. 대신
`GmsLlmClientTest`가 provider 오류 body를 예외·로그로 전달하지 않고,
`LlmImageContentTest`가 prompt/result의 문자열 표현을 redaction하는 계약을
Backend 전체 테스트에서 검증한다.

## 4. SBOM·취약점 정책

Trivy 0.70.0이 image별로 다음 artifact를 `.artifacts/image-security/`에
mode `0600`으로 생성한다.

- `backend|frontend.sbom.cdx.json`: CycloneDX package SBOM
- `backend|frontend.trivy.json`: 발견된 전체 CVE 보고서
- `policy-summary.json`: Critical·High·예외·만료 판정
- `scanner-status.txt`: Trivy 고정 버전

Critical은 수정 가능 여부와 관계없이 항상 차단한다. High는
`infra/security/vulnerability-exceptions.yaml`의 CVE와 image/package 대상이
정확히 일치하고 담당자·승인 사유·만료일이 유효해야 통과한다. 만료 예외는 현재
finding이 없어도 차단한다. Medium·Low는 증거에 보존한다.

CI와 release 후보는 `IMAGE_SCANNER_MODE=required`만 사용한다. `off`는 scanner가
아닌 image Secret/layer 검증기의 합성 self-test와 이미 CI 증거가 승인된 offline
운영 host 점검에만 사용한다.

SBOM, archive와 CVE 검사는 build gate에서만 실행되므로 운영 container의 request
latency나 memory에는 영향을 주지 않는다. CI 시간과 artifact 저장량은 증가한다.

## 5. 실행 방법

전체 image 검증:

```sh
IMAGE_SCANNER_MODE=required sh scripts/verify-production-images.sh
```

정적 image·SBOM만:

```sh
IMAGE_VERIFY_MODE=static \
IMAGE_SCANNER_MODE=required \
  sh scripts/verify-production-images.sh
```

실행 중 Compose log만:

```sh
BACKEND_CONTAINER="$(docker compose ps -q backend)" \
FRONTEND_CONTAINER="$(docker compose ps -q frontend)" \
SECRET_SCAN_DIR=secrets \
IMAGE_VERIFY_MODE=logs \
  sh scripts/verify-production-images.sh
```

검증기가 실제 누출을 거부하는지 확인하는 합성 회귀:

```sh
sh scripts/test-verify-production-images.sh
```

합성 회귀는 임시 non-root Alpine image 두 개를 만들고 Secret `ENV`와 `.env`
layer가 각각 반드시 실패하는지 검사한 뒤 image를 삭제한다.

## 6. CI와 확인 결과

Jenkins image build 단계는 합성 회귀와 `full` mode를 실행하고 SBOM·CVE 결과를
fingerprint artifact로 보존한다. Integration smoke에서는 실제 CI Secret
디렉터리와 실행 container를 사용해 `logs` mode를 다시 실행한다.

| 검증 | 결과 |
|---|---|
| 합성 Secret `ENV` 거부 | PASS |
| 합성 `.env` layer 거부 | PASS |
| Backend image User·UID/GID·capability·read-only | PASS |
| Frontend image User·UID/GID·capability·read-only | PASS |
| 실제 Secret과 runtime 불필요 도구·소스 부재 | PASS |
| 실제 Compose container log canary | PASS |
| Backend SBOM | 112 packages indexed |
| Frontend SBOM | 87 packages indexed |
| 수정 가능한 Critical 차단 gate | 두 image 모두 PASS |

현재 전체 보고서에는 Backend `1 High / 4 Medium`, Frontend
`2 Low / 2 Unspecified`가 남아 있다. 이는 숨기지 않고 artifact로 보존한다.
Backend High는 PostgreSQL JDBC 42.7.11에 해당하며 Scout가 42.7.12를 수정
버전으로 제시한다. Jackson Databind Medium 3건도 2.21.5 수정 버전이 있다.
이 dependency 갱신과 전체 Backend 회귀는 다음 보안 dependency 정비 대상으로
분리한다. 이번 gate가 확인한 것은 두 image 모두 현재 수정 가능한 Critical 항목이
없다는 사실이며, 전체 취약점이 0이라는 뜻은 아니다.
