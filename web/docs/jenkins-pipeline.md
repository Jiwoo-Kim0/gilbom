# Jenkins pipeline

`Jenkinsfile`은 Day 2에서 필요한 빌드·테스트·로컬 통합 검증의 초안이다.
Registry push와 운영 배포는 의도적으로 포함하지 않았다.

## 설계 이유

- Backend image 빌드는 Gradle test와 `bootJar`를 수행한다.
- Backend runtime gate는 read-only/non-root 상태에서 허용 volume 쓰기,
  capability 0, JDK·Gradle·Git·source·test artifact 부재를 확인한다.
- Frontend image 빌드는 37개 test, ESLint와 Vite production build를 수행한다.
- Frontend runtime gate는 UID 101·read-only·capability 0, Node/source/sourcemap과
  Secret 패턴 부재, `/healthz`·`/api`·SSE·cache header를 mock Backend로 검증한다.
- 통합 image gate는 image history·`docker save` layer와 실제 Secret 파일 값을
  대조하고, 합성 Secret `ENV`와 `.env` layer가 반드시 거부되는지도 자체 테스트한다.
- Docker Scout가 있는 agent에서는 Backend·Frontend SBOM과 전체 CVE 보고서를
  artifact로 보존하고 수정 가능한 Critical 취약점을 배포 차단한다.
- Compose의 health dependency가 준비된 뒤 프론트 Nginx를 통해 Backend API를
  호출한다. 컨테이너가 단순히 실행 중인지만 확인하지 않는다.
- 프론트 컨테이너에서 Backend 전체 Actuator health를 호출해 DB health
  contributor까지 `UP`인지 확인한다.
- Integration smoke의 실제 Secret과 Authorization·prompt·LLM response canary가
  Backend·Nginx container log에 남지 않는지 검사한다.
- DB에 직접 접속해 PostGIS 확장, 성공한 Flyway 이력, Spring Session JDBC
  테이블을 확인한다.
- Integration smoke 마지막에는 DB 응답을 기다리는 실제 HTTP 요청을 유지한 채
  SIGTERM을 보내 요청 drain과 30초 이내 graceful 종료를 검증한다.
- 별도 Container report smoke는 고유 Compose project와 빈 named volume에서
  로그인·CSRF·MFA, fallback 초안, 수정·반려·재제출·타 사용자 승인, SSE 후 REST
  재조회, soft-delete, 승인 PDF hash와 Backend 재기동 영속성을 검증한다.
- 보고서 smoke는 운영 Backend/Frontend image와 `compose.prod.yaml`을 사용하며
  host의 별도 애플리케이션 프로세스나 개발용 API port에 의존하지 않는다.
- 같은 격리 stack에서 Backend/Frontend PID 종료, PostgreSQL·MQTT 재시작,
  LLM egress 차단, Secret 생략/오류와 전체 Compose 재생성을 검증한다.
- 자동 재시작 뒤 DB 보고서와 승인 PDF·이미지 hash가 유지되고, 잘못된 DB
  Secret 원문이 log에 나타나지 않아야 최종 stage가 통과한다.
- CI 비밀번호는 매 빌드에서 ignored `secrets/` 파일로 생성하고 container runtime에
  `/run/secrets`로만 mount한다. Jenkins 환경변수·저장소·artifact로 보관하지 않는다.
- 운영 Compose contract gate는 image build 전에 공개 port가 Frontend/MQTT 두
  개뿐인지, DB·MQTT health dependency, 4개 network, named volume,
  resource/log 상한과 `/run/secrets` target을 검증한다.
- repository policy에서 운영 배포·rollback·backup·Secret 생성 스크립트의
  POSIX shell 구문과 Runbook/담당 범위 동결 문서 존재를 함께 확인한다.
- 종료 시 named volume을 제거해 다음 빌드도 빈 DB 최초 migration부터 검증한다.

## Jenkins agent prerequisites

- Docker Engine과 Docker Compose v2
- Git
- OpenSSL
- Python 3(표준 라이브러리 smoke client)
- Docker Scout 권장. release에서 SBOM을 강제할 agent는 필수
- Docker daemon을 사용할 수 있는 전용 Linux agent

실제 GMS canary는 비용·외부 전송·Secret 승인이 필요한 운영 확인이므로 기본
Jenkins stage에서는 실행하지 않는다. 승인된 별도 canary Job에서만
`REPORT_SMOKE_GMS_CANARY=true`와 운영 정책 플래그 및 file Secret을 주입한다.
복원력 stage는 외부 호출 대신 fake key를 넣은 뒤 Backend의 `llm-egress`
network를 먼저 분리하므로 비용과 외부 데이터 전송이 발생하지 않는다.

사설 GitLab 저장소를 Jenkins가 checkout하려면 읽기 전용 GitLab SCM
Credential과 Jenkins Job 설정 권한이 필요하다. 현재 pipeline은 image를
외부 Registry로 push하지 않으므로 Container Registry Credential은 필요하지
않다. Registry push나 운영 배포 stage를 추가할 때 별도 Credential ID를
확정하고 Jenkins credential binding으로 주입해야 한다.

## 의도적으로 남긴 다음 단계

- GitLab webhook 또는 multibranch pipeline 연결
- Jenkins agent label과 resource 제한 확정
- Container Registry 주소·Credential 확정 후 push stage 추가
- 배포 대상과 승인 정책 확정 후 deploy stage 추가
