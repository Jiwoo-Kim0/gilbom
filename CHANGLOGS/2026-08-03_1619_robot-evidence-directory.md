# 변경 로그: 로봇 증거 이미지 폴더 골격 추가

- 작성 시각: 2026-08-03 16:19 (Asia/Seoul)
- 작업 유형: 설정
- 상태: 완료

## 작업 요약

Jetson이 결함 증거 JPEG를 HTTPS로 업로드하기 전 저장하는 기본 경로
`embedded/var/evidence`를 저장소에서 확인할 수 있도록 폴더 골격을 추가했다.
실제 JPEG와 Outbox 등 런타임 데이터는 기존처럼 Git에 추적되지 않도록
제외 규칙을 세분화했다.

## 변경 파일

| 파일 | 상태 | 상세 변경 내용 |
| --- | --- | --- |
| `.gitignore` | 수정 | `embedded/var` 하위 런타임 파일은 제외하되 `evidence` 디렉터리의 안내 문서만 추적하도록 예외 규칙을 추가했다. |
| `embedded/var/evidence/README.md` | 추가 | 로봇 증거 JPEG의 기본 경로, 파일명, 크기 제한, HTTPS 업로드와 MQTT `image_id` 연결 방식을 기록했다. |
| `CHANGLOGS/2026-08-03_1619_robot-evidence-directory.md` | 추가 | 폴더 골격 추가와 런타임 파일 제외 규칙, 로컬 검증 결과를 기록했다. |

## 상세 내용

- 기존 `embedded/config.yaml`의 `detect.evidence_dir: var/evidence`와
  `embedded/detect/camera.py`의 자동 디렉터리 생성 동작은 변경하지 않았다.
- Git은 빈 디렉터리를 추적하지 않으므로 목적과 운영 규칙을 담은
  `README.md`를 디렉터리 골격으로 사용했다.
- `*.jpg`를 포함한 `embedded/var/evidence` 하위 런타임 파일은 계속
  Git ignore 대상이다.

## 검증

- 실행한 명령:
  - `git check-ignore -v embedded/var/evidence/sample.jpg`
  - `git check-ignore -v embedded/var/evidence/README.md`
  - `uv run --with pytest pytest embedded/test/test_hardware.py -q -p no:cacheprovider`
  - `git diff --check -- .gitignore embedded/var/evidence/README.md`
- 결과:
  - 예시 JPEG는 `.gitignore`에 의해 제외되고 `README.md`는 추적 가능함을 확인했다.
  - Camera·증거 이미지 관련 하드웨어 테스트 50건이 모두 통과했다.
  - 변경 파일의 공백 오류가 없음을 확인했다.
- 미실행 항목과 사유:
  - 실제 Jetson 카메라 촬영과 운영 HTTPS 업로드는 현재 Windows 개발 환경에
    장비와 운영 Credential이 없어 실행하지 않았다.

## 영향 및 주의사항

- 기본 저장 경로는 Jetson 프로세스의 작업 디렉터리가 `embedded`일 때
  `embedded/var/evidence`로 해석된다.
- 증거 JPEG는 민감한 촬영 데이터이므로 Git에 추가하지 않는다.
- 현재 코드는 업로드 후 로컬 JPEG를 자동 삭제하지 않으므로 Jetson 디스크
  용량을 주기적으로 확인해야 한다.

## 미완료 및 후속 작업

- 실제 Jetson에서 `embedded/var/evidence` 쓰기 권한과 남은 디스크 용량을 확인한다.
- 운영 전에 업로드 성공 파일의 자동 정리 또는 보존 기간 정책을 결정한다.
