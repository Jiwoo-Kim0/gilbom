# Runtime version baseline

이 문서는 개발자 로컬, Docker, Jenkins가 사용하는 프로젝트 실행 기준선을
정의한다. 범위 태그나 major-only 태그를 사용하지 않아 같은 commit의 재빌드가
예상치 못한 runtime patch를 가져오지 않게 한다.

| 구성요소 | 고정 버전 | 적용 위치 | 선택 이유 |
|---|---:|---|---|
| Spring Boot | 3.5.16 | `backend/build.gradle` | 현재 Boot 3 호환 기준선; OSS 지원 종료로 4.x migration 필요 |
| React / React DOM | 19.2.8 | `package.json`, `package-lock.json` | 현재 React 19.2 stable patch |
| React Router | 8.3.0 | `package.json`, `package-lock.json` | 사용 중인 BrowserRouter의 보안 수정 기준선 |
| Node.js | 24.18.0 LTS | `frontend/Dockerfile`, `.nvmrc`, `engines` | 운영 권장 LTS와 Vite 8 지원 범위 충족 |
| npm | 11.16.0 | `packageManager`, `engines` | Node image에 포함된 lockfile 작성 도구 고정 |
| Java | Amazon Corretto 21.0.12+8 | `backend/Dockerfile`, `.java-version` | Java 21 LTS의 2026년 7월 CPU patch |
| Gradle | 8.14.3 | Gradle Wrapper | 개발자와 Docker가 동일한 Wrapper 사용 |

## 운영 규칙

- 버전 변경은 이 표, Dockerfile, package lock 또는 Gradle 선언을 같은 commit에서
  함께 변경한다.
- `scripts/verify-supported-versions.sh`가 선언 간 불일치를 검사하며 Jenkins에서도
  실행한다.
- Node 개발 환경은 `nvm use`로 `.nvmrc`를 적용한다.
- Java 개발 환경은 Amazon Corretto 21.0.12를 사용한다. `.java-version`은 기능 release와
  security patch를 고정하며 Dockerfile은 vendor build와 OS까지 고정한다.
- Spring Boot 3.5.16은 3.5 계열의 마지막 OSS 릴리스다. 새로운 OSS patch가
  제공되지 않으므로 Boot 4.x 전환은 호환성 테스트를 포함한 별도 migration으로
  관리한다.

## Backend 전체 테스트

Host에 Java를 별도로 설치하지 않아도 저장소 root에서 다음 명령으로 Backend 전체
테스트를 실행할 수 있다.

```bash
./scripts/backend-test.sh
```

이 스크립트는 고정된 `amazoncorretto:21.0.12-alpine3.22` 안에서 `JAVA_HOME`과
Java 21을 확인하고, Host Docker socket을 Testcontainers에 연결한 뒤 Gradle
`test`를 실행한다. Testcontainers가 Docker를 찾지 못해
`disabledWithoutDocker = true` 테스트가 하나라도 skip되면 빌드를 실패시킨다.

Backend 디렉터리에서 실행하는 일반 `./gradlew test`도 skip을 허용하지 않는다.
Docker image builder는 daemon socket을 받지 않는 격리된 단계이므로
`-PallowSkippedTests=true`를 명시하지만, Jenkins가 그 전에 socket을 연결한 전체
테스트 gate를 반드시 통과시킨다. 이 예외 옵션을 개발자 로컬 성공 기준으로
사용하지 않는다.
