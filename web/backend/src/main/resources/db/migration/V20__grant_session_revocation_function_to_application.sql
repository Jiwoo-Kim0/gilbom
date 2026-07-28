-- V14에서 PUBLIC 실행 권한을 회수한 SECURITY DEFINER 함수는 관리자 MFA 초기화
-- 서비스가 직접 호출한다. 런타임 계정에는 이 함수 하나의 EXECUTE만 허용한다.
REVOKE ALL
    ON FUNCTION auth.revoke_user_authorization_sessions(UUID)
    FROM PUBLIC;

GRANT EXECUTE
    ON FUNCTION auth.revoke_user_authorization_sessions(UUID)
    TO "${applicationRole}";
