-- MQTT Credential 발급과 함께 로봇 이미지 업로드 API Key를 발급하기 위해
-- 애플리케이션 역할에 INSERT 권한을 추가한다. 원문 Key는 저장하지 않고
-- 애플리케이션이 생성한 SHA-256 해시와 식별용 prefix만 저장한다.
GRANT INSERT ON storage.robot_image_api_key TO "${applicationRole}";
