# Robot evidence images

이 디렉터리는 Jetson이 결함을 확정했을 때 HTTPS 업로드 전 JPEG 증거
이미지를 임시로 저장하는 런타임 경로입니다.

- 설정 값: `detect.evidence_dir`
- 기본 경로: `embedded/var/evidence`
- 파일 형식: `incident-<captured-at>.jpg`
- 제한: JPEG, 최대 512,000 bytes

실제 `*.jpg` 파일은 촬영 데이터이므로 Git에 추적하지 않습니다. 이미지는
`POST /api/v1/images`로 업로드되며, 서버가 반환한 `image_id`만 MQTT
Incident Event에 첨부됩니다.

업로드 URL과 로봇 전용 API Key는 관리자 Robot Credential 발급 결과의
`imageUpload.url`과 `imageUpload.apiKey`를 각각 `UPLOAD_URL`, `UPLOAD_API_KEY`로
주입합니다.
