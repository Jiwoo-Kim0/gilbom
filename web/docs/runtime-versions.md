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
