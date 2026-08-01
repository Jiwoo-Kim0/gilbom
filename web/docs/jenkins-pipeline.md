# Jenkins pipeline

`Jenkinsfile`은 build·test·통합 검증의 정본이다. Registry push와 운영 배포는
승인 경계로 남겨 pipeline에 포함하지 않았다.

## 설계 이유

- Backend image 빌드는 Gradle test와 `bootJar`를 수행한다.
- Backend runtime gate는 read-only/non-root 상태에서 허용 volume 쓰기,
  capability 0, JDK·Gradle·Git·source·test artifact 부재를 확인한다.
- Frontend image 빌드는 37개 test, ESLint와 Vite production build를 수행한다.
- Frontend runtime gate는 UID 101·read-only·capability 0, Node/source/sourcemap과
  Secret 패턴 부재, `/healthz`·`/api`·SSE·cache header를 mock Backend로 검증한다.
- Backend·Frontend·MQTT init image build는 `compose.build.yaml`에 격리하고,
  `compose.prod.yaml`에는 `build:`가 다시 들어오지 못하게 contract gate로 막는다.
- Frontend TLS gate는 인증서가 없는 bootstrap, HTTP-01 webroot, HTTP 308
  redirect, HTTPS 8443 reverse proxy와 무중단 Nginx reload를 검증한다.
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
- 운영 Compose contract gate는 image build 전에 공개 port가 Frontend
  HTTP·HTTPS/MQTT 세 개뿐인지, DB·MQTT health dependency, 4개 network, named volume,
  resource/log 상한과 `/run/secrets` target을 검증한다.
- repository policy에서 운영 배포·rollback·backup·Secret 생성 스크립트의
  POSIX shell 구문과 Runbook/담당 범위 동결 문서 존재를 함께 확인한다.
- repository policy는 `secrets/` 아래 추적 파일을 차단하되, 실제 Secret이
  추적되지 않게 만드는 `secrets/.gitignore` 한 경로만 허용한다.
- 종료 시 named volume을 제거해 다음 빌드도 빈 DB 최초 migration부터 검증한다.

## Jenkins agent prerequisites

- `compose.jenkins.yaml`의 Jenkins 2.555.3 LTS와 TLS DinD 29.6.2
- Git
- OpenSSL
- Python 3(표준 라이브러리 smoke client)
- Docker Scout 권장. release에서 SBOM을 강제할 agent는 필수
- `/opt/jenkins/home` 영속 경로

운영 Jenkins는 초기화 스크립트로 Executor를 1개로 고정하고
`COMPOSE_PARALLEL_LIMIT=1`을 적용하며 DinD container도 하나만 실행한다.
Jenkins UI는 기본 `127.0.0.1:8081`, Docker TLS 2376은 host에 publish하지 않는다.
`Jenkinsfile`은 host Docker socket 개발 환경과 TLS DinD를 모두 지원하지만 운영
Compose는 host socket을 mount하지 않는다.

호스트 Docker의 `gilbom-ci` network는 Jenkins와 DinD service를 연결하는 바깥
network이며 DinD 내부 daemon에는 존재하지 않는다. DinD가 생성하는 Backend test
container에는 `docker:host-gateway`를 추가해 같은 network 이름을 중첩 생성하지
않고 TLS endpoint `tcp://docker:2376`과 Testcontainers의 공개 port에 접근한다.

Backend test container는 UID/GID 1000의 non-root로 실행한다. Base image의 기본
home인 `/`에는 쓸 수 없으므로 `/test-home`을 빌드별 tmpfs로 제공하고
`HOME`, `GRADLE_USER_HOME`, Gradle project cache와 build directory를 모두 그
경로로 격리한다. Backend source는 read-only mount를 유지하며 테스트 컨테이너
종료 시 다운로드·캐시·산출물도 함께 제거된다.

Image layer 검증의 `docker save` archive와 압축 해제본은 Jenkins의 256 MiB
`/tmp`에 두지 않고 workspace의 ignored `.artifacts/image-verify-work/`에서
처리한다. Role별 검사가 끝나면 큰 archive를 즉시 지우고 pipeline 종료 시
workspace cleanup으로 빈 작업 root까지 제거한다.

Jenkins Git Client는 HTTPS Username/Password Credential을 전달하기 위한 일회성
`askpass` script를 `java.io.tmpdir`에서 실행한다. Jenkins의 `/tmp`는 container
종료 시 사라지는 256 MiB tmpfs로 유지하고 `nosuid,nodev`를 적용하되, checkout에
필요한 script 실행은 허용한다. `/tmp`를 `noexec`로 두면 Credential이 올바르게
등록돼 있어도 `jenkins-gitclient-pass*.sh-copy: Permission denied`로 checkout이
실패한다.

Jenkins의 `Free Temp Space` monitor 기본 Offline 임계값 1 GiB는 이 tmpfs 상한보다
크므로 그대로 두면 빈 tmpfs도 Built-In Node를 Offline 처리한다. Custom Jenkins
image는 Offline 임계값 64 MiB, warning 임계값 128 MiB를 영속 설정한다. Monitor를
끄지 않으므로 비정상적인 임시파일 증가로 여유 공간이 64 MiB 아래가 되면 빌드는
계속 차단된다.

각 build는 `.artifacts/runtime/build-resources.txt`에 메모리, root filesystem,
Docker storage와 실행 container 사용량을 기록한다. 이 artifact를 보고 15GB
host에서 Jenkins 2GB, DinD 4GB 상한과 build cache 정리 주기를 조정한다.

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
- Container Registry 주소·Credential 확정 후 push stage 추가
- 배포 대상과 승인 정책 확정 후 deploy stage 추가
