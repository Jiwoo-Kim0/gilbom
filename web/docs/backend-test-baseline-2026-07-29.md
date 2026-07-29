# Backend 전체 테스트 기준선 — 2026-07-29

## 실행 기준

- Git commit: `02b821370061`
- 실행 시각: `2026-07-29T09:19:06+09:00`
- Host: WSL2 Linux `x86_64`
- Docker Engine: Docker Desktop `29.6.2`
- Java image: `amazoncorretto:21.0.12-alpine3.22`
- Java: Corretto `21.0.12+8-LTS`
- `JAVA_HOME`: `/usr/lib/jvm/default-jvm`
- Gradle Wrapper: `8.14.3`

## 실행 명령

```bash
./scripts/backend-test.sh
```

스크립트는 Backend 소스를 read-only로 마운트하고 Gradle 사용자 cache, project
cache, build 결과를 일회성 tmpfs에 둔다. Host의 기존 `.gradle`·`build` 소유권이나
오래된 결과를 성공 근거로 사용하지 않는다.

## 결과

```text
Backend test summary: tests=423, failures=0, skipped=0
BUILD SUCCESSFUL in 3m 35s
```

`skipped=0`이므로 `@Testcontainers(disabledWithoutDocker = true)`가 선언된 13개
통합 테스트 class도 Docker 부재로 건너뛰지 않았다.

Docker socket을 의도적으로 연결하지 않고
`PatrolMissionSchemaIntegrationTest`만 실행한 음성 검증은 다음처럼 실패했다.

```text
Backend test summary: tests=6, failures=0, skipped=6
Backend tests must not be skipped: 6 skipped.
BUILD FAILED
```

따라서 Docker를 사용할 수 없는 환경에서 Testcontainers skip이 성공 기준선으로
기록되지 않는다.

테스트 종료 과정에서 Spring Session cleanup scheduler가 이미 종료 중인
Testcontainer PostgreSQL connection을 요청해 오류 log를 남겼으나, 테스트 assertion
실패나 skip은 발생하지 않았다. 종료 log 정리는 테스트 실행 환경 정상화와 분리된
후속 개선 사항이다.
