# Day 7 장애·재시작·영속성 검증

기준일: 2026-07-31

## 목표와 실행 경계

정상 보고서 workflow가 성공한 동일 revision의 운영 image에서 프로세스·의존
서비스·외부 LLM 장애를 주입하고 자동 복구와 named volume 영속성을 검증한다.
운영 stack에는 장애를 주입하지 않는다. 실행마다 별도 Compose project, 임시
Secret과 빈 volume을 만들고 종료·실패 시 `down --volumes --remove-orphans`로
해당 project만 정리한다.

## 산출물

- `compose.resilience.yaml`: 일회성 smoke에서 운영 `unless-stopped` 정책만 복원
- `scripts/container-resilience-smoke.sh`: 보고서 smoke와 복원력 검증을 잇는
  CI 진입점
- `scripts/verify-production-resilience.sh`: 장애 주입, 복구 대기, 영속성·log 검사
- `scripts/container-report-smoke-client.py`: 장애 중 일반 API와 두 종류 fallback
  검증 mode

`compose.prod.yaml`이 image, network, volume, 권한과 health의 정본이다.
`compose.smoke.yaml`과 `compose.resilience.yaml`은 검증 격리와 restart policy만
덧씌운다.

## 검증 시나리오

1. 기준 보고서의 APPROVED v3와 PDF SHA-256을 다시 확인하고 이미지 volume에
   non-root Backend가 canary 파일을 기록한다.
2. Backend PID 1에 직접 SIGTERM을 보내 `RestartCount` 증가, 새 host PID,
   readiness 복구와 기존 보고서 조회를 확인한다.
3. Frontend PID 1도 같은 방식으로 종료해 자동 재시작, `/healthz`와 정적
   `index.html` 복구를 확인한다.
4. MQTT를 중지·재시작한다. Backend PID는 유지되어야 하며 별도 MQTT health가
   DOWN에서 UP으로 돌아오고 재연결이 완료되어야 한다.
5. PostgreSQL을 중지·재시작한다. Backend liveness는 유지되고 readiness는
   실패해야 하며, DB 복구 뒤 Hikari connection pool과 readiness가 회복되어야
   한다.
6. 승인된 LLM 정책과 임시 fake key로 Backend를 만든 뒤 유일한
   `llm-egress` network를 분리한다. Backend health와 기존 조회·일반 보고서
   생성·수정은 유지되고, LLM 생성은 최대 2회 실패 후
   `TEMPLATE/LLM_UNAVAILABLE`로 fallback되어야 한다.
7. LLM을 비활성화하고 실제 key 경로를 생략한다. `/dev/null` 빈 Secret mount로
   Backend가 정상 기동하며 생성은 `TEMPLATE/POLICY_DISABLED`가 되어야 한다.
8. 전체 서비스를 `--force-recreate`한다. DB 보고서, 승인 PDF hash와 이미지
   canary hash가 모두 같아야 한다.
9. application DB password만 잘못된 임시 Secret으로 바꿔 Backend를 재생성한다.
   readiness가 성공하지 않고 restart/unhealthy 상태가 되며, log에는 password
   원문 없이 DB 기동 오류만 나타나야 한다. 정상 Secret 복구 후 기존 보고서를
   다시 조회한다.
10. 마지막 Backend/Nginx log를 실제 임시 Secret 값, Authorization header,
    prompt 원문, LLM 응답 원문과 대조해 비노출을 확인한다.

## PID 장애 주입 방식

`docker kill`은 Docker가 명시적 운영자 중지로 기록해 `unless-stopped` 자동
재시작을 억제할 수 있다. 반대로 PID namespace의 init인 PID 1은 컨테이너
내부에서 보낸 SIGKILL을 무시한다. 따라서 검증기는 컨테이너 내부에서 PID 1에
SIGTERM을 직접 보내며, Docker restart manager가 이를 프로세스 종료로 인식해
실제 `RestartCount`와 host PID가 변하는지 검사한다.

이 검사는 자동 재시작 계약을 위한 것이다. 진행 중 요청 drain과 제한 시간 내
graceful shutdown은 `scripts/verify-backend-graceful-shutdown.sh`가 별도로
검증한다.

## Secret 정책

DB, 서명키, 인증/MFA, MQTT Secret은 빈 파일도 허용하지 않고 계속 fail-closed다.
유일한 예외는 `GILBOM_LLM_ENABLED=false`인 LLM API key다. 이때만 읽을 수 있는
빈 file mount를 “미설정”으로 해석한다. LLM이 활성화됐거나 다른 Secret이
비어 있으면 Backend 기동은 실패한다.

이 구분으로 선택 서비스인 LLM Secret이 없는 배포에서도 일반 서비스를 제공하고,
필수 DB 인증 오류를 fallback으로 감추지 않는다. Secret 값은 image ARG/ENV,
명령 인자, 테스트 출력과 artifact에 넣지 않는다.

## 실행

운영 image 빌드와 전체 검증:

```sh
sh scripts/container-resilience-smoke.sh
```

같은 Git revision의 image를 앞 단계에서 이미 빌드한 CI:

```sh
REPORT_SMOKE_SKIP_BUILD=true sh scripts/container-resilience-smoke.sh
```

이 진입점은 `DOCKER_BIND_WORK_ROOT`를 보고서 smoke에 그대로 전달한다. 기본
`.artifacts/docker-bind-work`는 Jenkins와 DinD가 함께 보는 workspace이며,
원격 daemon 환경에서는 양쪽에서 같은 절대 경로로 보여야 한다.

복원력 검증과 실제 GMS canary는 한 실행에서 함께 허용하지 않는다. 장애 검증은
외부 network를 차단한 fake key만 사용하며 실제 provider 비용이나 데이터 전송을
발생시키지 않는다.

## 보안·성능 판단

- 장애는 고유 label의 임시 project에만 주입하고 volume 삭제 대상도 그 project로
  제한한다.
- LLM 호출은 request limit 2, budget 500, 동시 실행 1로 제한한 뒤 egress를 먼저
  끊는다.
- readiness와 재연결은 제한 시간 polling을 사용해 무한 대기와 과도한 요청을
  피한다.
- 각 시나리오를 같은 stack에서 순차 실행해 DB·JVM을 불필요하게 중복 기동하지
  않는다.
- 잘못된 DB Secret은 오류의 명확성과 원문 비노출을 함께 검사한다.
- 운영 non-root, read-only rootfs, `cap_drop: ALL`, resource/log 상한은 그대로
  유지한다.

## 최종 결과

2026-07-31 운영 Backend/Frontend image 재빌드와 전체 acceptance가 PASS했다.
Backend builder summary는 tests 760, failures 0, skipped 147이었다. Docker
socket이 없는 image build에서는 Testcontainers 검증을 명시적으로 제외하며,
별도 Docker socket 전체 gate는 같은 workspace에서
`760 tests, failures 0, skipped 0`을 통과했다.
정상 보고서 workflow, Backend·Frontend 자동 재시작, PostgreSQL pool/readiness
복구, MQTT 재연결, LLM egress fallback, LLM Secret 없는 기동, 잘못된 DB Secret
fail-closed가 모두 통과했다.

전체 Compose 재생성 전후 DB 보고서, 승인 PDF SHA-256과 이미지 canary SHA-256은
동일했다. 실제 GMS canary는 명시적 opt-in이 없어 SKIPPED됐다.
