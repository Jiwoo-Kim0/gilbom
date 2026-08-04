# 변경 로그: EC2 이미지 업로드 URL 반영 준비

- 작성 시각: 2026-08-03 19:35 (Asia/Seoul)
- 작업 유형: 설정
- 상태: 부분 완료

## 작업 요약

배포 환경의 Robot Credential 회전 응답이 이미지 업로드 URL을
`http://localhost:3000/api/v1/images`로 반환한 상태를 확인했다. EC2에
별도 전달하는 로컬 `.envEC2` 원본에
`https://i15d101.p.ssafy.io/api/v1/images`를 추가하고, EC2 검증 스크립트가
환경 파일과 실행 Backend의 URL을 모두 확인하도록 보강했다. 실제
EC2 파일 교체와 Backend 재생성은 SSH 키 부재로 수행하지 못했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `.envEC2` | 수정 | EC2 Production Compose에 주입할 `GILBOM_STORAGE_UPLOAD_URL=https://i15d101.p.ssafy.io/api/v1/images`를 추가; Host 별 설정이라 Git에는 포함하지 않음 |
| `.gitignore` | 수정 | Host 별 EC2 Runtime 설정 `.envEC2`가 실수로 Git에 포함되지 않도록 제외 규칙 복구 |
| `web/scripts/verify-day8-ec2.sh` | 수정 | `runtime.env`와 실행 중 Backend의 업로드 URL이 `https://$DOMAIN/api/v1/images`와 정확히 일치하는지 검증 |
| `CHANGLOGS/2026-08-03_1935_ec2-upload-url-preparation.md` | 추가 | 로컬 EC2 설정 수정, 검증 결과, 실서버 미반영 사유를 기록 |

## 상세 내용

- Credential 회전은 URL을 계산하지 않고 Backend Runtime의
  `GILBOM_STORAGE_UPLOAD_URL`을 그대로 응답한다.
- 운영 Compose는 이 값을 필수로 요구하지만, 기존 EC2 Host 전용
  `.envEC2`에는 신규 필드가 없었다.
- `.envEC2`는 기존 운영 계약에 따라 `/opt/gilbom/app/.envEC2`로 별도
  전달하는 Host 설정이며, 최신 루트 배포 경로에서는
  `/opt/gilbom/deploy/runtime.env`에 동일한 키가 필요하다.
- 검증 스크립트는 환경 파일만 보지 않고 Container의 실제
  `printenv` 결과까지 대조해 재생성 누락을 감지한다.

## 검증

- 실행한 명령:
  - `.envEC2` + `web/compose.prod.yaml` `docker compose config --quiet`
  - 렌더링된 Backend `GILBOM_STORAGE_UPLOAD_URL` 값 확인
  - Linux Alpine 기반 Container에서 `sh -n web/scripts/verify-day8-ec2.sh`
  - `git check-ignore -v .envEC2`
  - 대상 파일 `git diff --check`
  - `ubuntu@i15d101.p.ssafy.io` SSH BatchMode 접속 시도
- 결과:
  - EC2 로컬 설정 원본의 Production Compose 렌더링 성공.
  - 렌더링된 Backend URL은
    `https://i15d101.p.ssafy.io/api/v1/images`.
  - `.envEC2` Git 제외, Shell 문법, `git diff --check` 통과.
  - EC2 SSH 접속은 `Permission denied (publickey)`로 실패.
- 미실행 항목과 사유:
  - `/opt/gilbom/app/.envEC2` 또는 `/opt/gilbom/deploy/runtime.env` 실제 수정은
    EC2 SSH Private Key/Jenkins 배포 Credential이 현재 실행 환경에 없어 미실행.
  - Backend Container 재생성과 Health, Container Runtime URL, 재회전 응답
    확인은 실서버 설정 미반영으로 미실행.

## 영향 및 주의사항

- 현재 실서버 Credential을 추가 회전하면 localhost URL이 반복되고
  MQTT·이미지 Secret만 다시 무효화되므로 서버 재설정 전에는 회전하지
  않아야 한다.
- 채팅에 노출된 기존 MQTT 비밀번호와 이미지 API Key는 설정 반영 후
  최종 1회 회전으로 폐기해야 한다.
- DB와 `robot_images` Volume을 보존하기 위해 `down --volumes`를 사용하지
  않는다.

## 미완료 및 후속 작업

- EC2 SSH Private Key 접근 또는 승인된 Jenkins 배포 Job으로 운영 Runtime
  환경 파일에 동일한 URL을 반영한다.
- `compose config --quiet` 후 Backend만 재생성하고 Health·Runtime URL을 확인한다.
- 그 뒤 관리자 화면에서 Credential을 한 번만 회전하고 새 값을 안전하게
  Jetson에 전달한다.
