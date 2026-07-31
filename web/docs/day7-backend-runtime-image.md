# Day 7 Backend 운영 이미지

기준일: 2026-07-31

## 1. 결정

Backend 이미지는 다음 세 단계로 고정한다.

| 단계 | 기준 이미지 | 책임 | Runtime 전달물 |
|---|---|---|---|
| `builder` | `amazoncorretto:21.0.12-alpine3.22` | Gradle test, `bootJar`, `jlink` | custom Java runtime, 실행 JAR |
| `mqtt-tools` | `eclipse-mosquitto:2.1.2-alpine` | credential 도구 공급 | `mosquitto_passwd` 한 파일 |
| `runtime` | `alpine:3.22` | 애플리케이션 실행 | 위 산출물과 최소 동적 라이브러리만 포함 |

Runtime에는 builder의 소스, Gradle cache, Wrapper, JDK 명령, test class와
test report를 복사하지 않는다. `jlink` module 목록은 `bootJar`와 runtime
dependency에 대한 `jdeps` 결과를 기준으로 하고, TLS provider·DNS·한국어 locale처럼
동적으로 로드되는 module만 명시적으로 보완한다.

`java.compiler` module은 runtime dependency가 요구하므로 Java runtime 안에는
남지만 `javac`, `jar`, `jdeps`, `jlink` 실행 파일과 JDK header는 없다.

## 2. Runtime 도구 경계

| 항목 | 판정 | 이유 |
|---|---|---|
| `java` | 유지 | Spring Boot 실행 |
| BusyBox `wget` | 유지 | readiness healthcheck |
| `ca-certificates` | 유지 | HTTPS와 MQTT TLS 신뢰 검증 |
| `mosquitto_passwd` | 유지 | 로봇 MQTT credential 발급·회전·폐기 |
| `cjson`, `libcrypto3` | 유지 | `mosquitto_passwd`의 실제 동적 링크 dependency |
| `curl` | 제거 | healthcheck를 `wget`으로 대체 |
| JDK·Gradle·Git | 제거 | 실행에 불필요하고 공격 표면·이미지 크기만 늘림 |

`mosquitto_passwd`를 제거하면
`PasswordFileMqttCredentialProvisioner`의 robot credential 기능이 깨진다. 로봇
credential을 별도 서비스로 분리하기 전까지는 Backend의 실제 런타임 의존성이다.

## 3. 사용자와 쓰기 경계

프로세스 사용자는 `spring`, UID/GID는 `1883:1883`으로 고정한다. 실행 JAR은
`root:1883`, mode `0440`이며 애플리케이션 사용자가 수정할 수 없다.

| 경로 | 접근 | 저장 방식 |
|---|---|---|
| `/app/data/report-pdf` | RW | 영속 PDF volume |
| `/app/data/robot-images` | RW | 영속 결함 이미지 volume |
| `/app/config/mqtt/credentials` | RW | credential provisioning 사용 시 `passwd` volume |
| `/tmp` | RW | `noexec,nosuid,nodev`, 64 MiB tmpfs |
| `/app/config/mqtt/certs` | RO | 공개 인증서 bind mount |
| `/run/secrets` | RO | 실행 시점 Docker Secret |
| 그 밖의 root filesystem | RO | 쓰기 금지 |

새 named volume은 이미지에 미리 만든 UID/GID 1883 디렉터리 권한을 이어받는다.
기존 host storage나 외부 volume은 배포 전에 소유권 또는 `fsGroup`을 1883에 맞춰야
한다. Secret 파일 권한은
[`day7-secret-injection.md`](day7-secret-injection.md)의 file-backed Compose
규칙을 따른다.

## 4. Container 보안 제약

Compose Backend에는 다음 제약을 함께 적용한다.

- `read_only: true`
- `/tmp` 전용 tmpfs
- `cap_drop: ALL`
- `security_opt: no-new-privileges:true`
- PDF·이미지·MQTT credential 세 mount만 명시적 RW

Non-root만 선언하고 root filesystem 쓰기를 열어 두는 것보다 컨테이너 탈취 뒤
지속성 확보와 실행 파일 변조 범위를 줄인다. tmpfs 크기 상한은 비정상적인 임시
파일 증가가 host disk 전체를 압박하지 않게 한다.

## 5. Graceful shutdown

종료 시간의 포함 관계는 다음과 같다.

| 계층 | 상한 |
|---|---:|
| Spring lifecycle phase | 20초 |
| MQTT connect completion | 10초 |
| MQTT quiesce·disconnect | 5초 |
| Docker `stop_grace_period` | 30초 |

`server.shutdown=graceful`로 SIGTERM 이후 신규 HTTP 요청 수락을 중단하고 진행 중
요청을 정리한다. `spring.lifecycle.timeout-per-shutdown-phase=20s`는 Spring bean과
Tomcat 종료 상한을 Docker 강제 종료보다 짧게 둔다.

Spring Integration 6.5.10 기본 MQTT manager가 호출하는 Paho 단일 인자
`disconnectForcibly`는 전달받은 값을 disconnect timeout에만 쓰고 quiesce는
30초로 유지한다. Broker 장애 중 Docker의 30초 SIGKILL 경계까지 대기하는 문제를
막기 위해 `BoundedMqttv5ClientManager`가 quiesce와 disconnect에 모두 5초 상한을
적용한다. 정상 연결에서는 기존과 같이 disconnect와 reconnect timer 정리를
수행한다.

## 6. 자동 검증

`scripts/verify-backend-runtime-image.sh`는 다음을 실제 container에서 검사한다.

- UID/GID 1883, effective capability 0, `NoNewPrivs=1`
- read-only root filesystem과 `/tmp` tmpfs
- PDF·이미지 파일 및 실제 `mosquitto_passwd` 쓰기 성공
- JAR 수정 실패와 JDK·Gradle·Git·curl·source·test artifact 부재
- readiness healthcheck와 SIGTERM 선언

`scripts/verify-backend-graceful-shutdown.sh`는 DB readiness 요청을 의도적으로
in-flight 상태로 유지한 뒤 SIGTERM을 보낸다. Backend가 요청 해제 전에는 살아
있고, 해제 뒤 `Graceful shutdown complete`를 기록하며 exit code 137 없이 30초
미만에 종료해야 통과한다. 두 검증기는 Jenkins의 image build와 integration smoke
gate에 포함한다.

Health group 구성, DB·MQTT 장애 전환, transaction 원자성 검증은
[`day7-health-readiness-shutdown.md`](day7-health-readiness-shutdown.md)에
정리했다.

## 7. 확인 결과

| 검증 | 결과 |
|---|---|
| Backend 전체 회귀 | `755 tests, failures=0, skipped=0` |
| Docker builder test | `755 tests, failures=0` (`147 skipped`: build에 Docker socket을 전달하지 않는 정책) |
| Runtime 경계·도구·쓰기 | PASS |
| Non-root + read-only 실제 Spring 기동 | PASS |
| DB·MQTT health 장애 전환 | PASS |
| in-flight 요청 중 SIGTERM | PASS, 2초, SIGKILL 없음 |
| 최종 image 크기 | `161,099,211 bytes` |
| 변경 전 image 크기 | `406,801,510 bytes` |

이미지는 약 60.4% 작아져 registry 전송량, 배포 pull 시간과 node 저장 공간이
줄어든다. 애플리케이션 처리량 개선을 직접 보장하는 변경은 아니며, custom runtime
module 목록이 바뀌는 dependency 추가 시에는 실제 기동 smoke를 다시 통과해야 한다.
