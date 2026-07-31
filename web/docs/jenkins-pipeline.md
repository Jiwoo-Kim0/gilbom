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
- Compose의 health dependency가 준비된 뒤 프론트 Nginx를 통해 Backend API를
  호출한다. 컨테이너가 단순히 실행 중인지만 확인하지 않는다.
- 프론트 컨테이너에서 Backend 전체 Actuator health를 호출해 DB health
  contributor까지 `UP`인지 확인한다.
- DB에 직접 접속해 PostGIS 확장, 성공한 Flyway 이력, Spring Session JDBC
  테이블을 확인한다.
- Integration smoke 마지막에는 DB 응답을 기다리는 실제 HTTP 요청을 유지한 채
  SIGTERM을 보내 요청 drain과 30초 이내 graceful 종료를 검증한다.
- CI 비밀번호는 매 빌드에서 ignored `secrets/` 파일로 생성하고 container runtime에
  `/run/secrets`로만 mount한다. Jenkins 환경변수·저장소·artifact로 보관하지 않는다.
- 운영 Compose contract gate는 image build 전에 공개 port가 Frontend/MQTT 두
  개뿐인지, DB·MQTT health dependency, 4개 network, named volume,
  resource/log 상한과 `/run/secrets` target을 검증한다.
- 종료 시 named volume을 제거해 다음 빌드도 빈 DB 최초 migration부터 검증한다.

## Jenkins agent prerequisites

- Docker Engine과 Docker Compose v2
- Git
- OpenSSL
- Docker daemon을 사용할 수 있는 전용 Linux agent

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
