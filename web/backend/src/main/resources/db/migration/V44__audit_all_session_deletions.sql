-- Spring Session JDBC 자체 cleanup, 애플리케이션 logout, 동시 로그인, 권한 변경 등
-- 어떤 경로가 spring_session을 지워도 DB 경계에서 빠짐없이 원인을 남긴다.
-- 세션 ID와 serialized attribute는 감사 로그에 절대 기록하지 않는다.

CREATE FUNCTION session_store.audit_spring_session_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, audit, session_store
AS $$
DECLARE
    security_user_id UUID;
    security_authentication_state VARCHAR(40);
    security_absolute_expiry_time BIGINT;
    security_challenge_expiry_time BIGINT;
    deletion_reason VARCHAR(40);
    current_epoch_millis BIGINT;
    idle_expired BOOLEAN;
    absolute_expired BOOLEAN;
    challenge_expired BOOLEAN;
BEGIN
    SELECT
        security_state.user_id,
        security_state.authentication_state,
        security_state.absolute_expiry_time,
        security_state.authentication_challenge_expires_at
    INTO
        security_user_id,
        security_authentication_state,
        security_absolute_expiry_time,
        security_challenge_expiry_time
    FROM session_store.session_security_state security_state
    WHERE security_state.session_primary_id = OLD.primary_id;

    current_epoch_millis :=
        FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
    idle_expired := OLD.expiry_time <= current_epoch_millis;
    absolute_expired := security_absolute_expiry_time IS NOT NULL
        AND security_absolute_expiry_time <= current_epoch_millis;
    -- AUTHENTICATED 세션은 challenge가 끝난 상태다. 잘못 남은 과거 값이 있어도
    -- challenge 만료로 분류하거나 cleanup하지 않는다.
    challenge_expired := security_authentication_state IS DISTINCT FROM
            'AUTHENTICATED'
        AND security_challenge_expiry_time IS NOT NULL
        AND security_challenge_expiry_time <= current_epoch_millis;

    deletion_reason := NULLIF(
        current_setting('gilbom.session_delete_reason', TRUE),
        ''
    );
    IF deletion_reason NOT IN (
        'EXPIRED_CLEANUP',
        'CONCURRENT_LOGIN',
        'LOGOUT',
        'AUTHORIZATION_CHANGE',
        'ENFORCEMENT_REJECT',
        'CHALLENGE_FAILURE',
        'PASSWORD_CHANGE',
        'MFA_RESET',
        'APPLICATION_REQUEST'
    ) THEN
        deletion_reason := NULL;
    END IF;

    -- Spring Session JDBC의 자체 만료 cleanup은 애플리케이션 repository를
    -- 거치지 않는다. 컨텍스트가 없어도 DB 만료값으로 안전하게 분류한다.
    IF deletion_reason IS NULL THEN
        deletion_reason := CASE
            WHEN idle_expired OR absolute_expired OR challenge_expired
                THEN 'EXPIRED_CLEANUP'
            ELSE 'UNCLASSIFIED_DELETE'
        END;
    END IF;

    INSERT INTO audit.audit_log (
        request_id,
        actor_user_id,
        actor_type,
        action_type,
        target_type,
        target_id,
        before_value,
        after_value,
        reason,
        result,
        ip_address,
        user_agent
    ) VALUES (
        NULL,
        NULL,
        'SYSTEM',
        'AUTH_SESSION_DELETED',
        CASE WHEN security_user_id IS NULL THEN 'SESSION' ELSE 'USER' END,
        security_user_id,
        NULL,
        jsonb_strip_nulls(jsonb_build_object(
            'deletionReason', deletion_reason,
            'authenticationState', security_authentication_state,
            'idleExpired', idle_expired,
            'absoluteExpired', absolute_expired,
            'challengeExpired', challenge_expired
        )),
        deletion_reason,
        'SUCCESS',
        NULL,
        NULL
    );

    RETURN OLD;
END;
$$;

REVOKE ALL ON FUNCTION session_store.audit_spring_session_delete()
    FROM PUBLIC;

CREATE TRIGGER tr_spring_session_audit_delete
    BEFORE DELETE ON session_store.spring_session
    FOR EACH ROW
    EXECUTE FUNCTION session_store.audit_spring_session_delete();

COMMENT ON FUNCTION session_store.audit_spring_session_delete() IS
    '모든 Spring Session 삭제를 식별자 없이 원인·상태·만료 판정만 SYSTEM 감사 로그에 기록';

-- 권한 변경 함수는 HTTP 애플리케이션을 통하지 않고 session을 지운다. V44 trigger가
-- 이를 AUTHORIZATION_CHANGE로 분류하도록 같은 transaction에 원인을 설정한다.
-- 관리자 MFA 초기화가 먼저 MFA_RESET을 지정한 경우에는 그 더 구체적인 원인을 보존한다.
CREATE OR REPLACE FUNCTION auth.revoke_user_authorization_sessions(
    target_user_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, auth, session_store
AS $$
DECLARE
    deleted_count INTEGER;
    configured_reason VARCHAR;
BEGIN
    configured_reason := NULLIF(
        current_setting('gilbom.session_delete_reason', TRUE),
        ''
    );
    IF configured_reason IS DISTINCT FROM 'MFA_RESET' THEN
        PERFORM set_config(
            'gilbom.session_delete_reason',
            'AUTHORIZATION_CHANGE',
            TRUE
        );
    END IF;

    DELETE FROM session_store.spring_session session
    USING session_store.session_security_state security_state
    WHERE security_state.session_primary_id = session.primary_id
      AND security_state.user_id = target_user_id;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

CREATE OR REPLACE FUNCTION auth.revoke_role_authorization_sessions(
    target_role_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, auth, session_store
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    PERFORM set_config(
        'gilbom.session_delete_reason',
        'AUTHORIZATION_CHANGE',
        TRUE
    );

    DELETE FROM session_store.spring_session session
    USING session_store.session_security_state security_state
    WHERE security_state.session_primary_id = session.primary_id
      AND EXISTS (
          SELECT 1
          FROM auth.user_role user_role
          WHERE user_role.user_id = security_state.user_id
            AND user_role.role_id = target_role_id
            AND user_role.is_active = TRUE
      );

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

CREATE OR REPLACE FUNCTION auth.revoke_permission_authorization_sessions(
    target_permission_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, auth, session_store
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    PERFORM set_config(
        'gilbom.session_delete_reason',
        'AUTHORIZATION_CHANGE',
        TRUE
    );

    DELETE FROM session_store.spring_session session
    USING session_store.session_security_state security_state
    WHERE security_state.session_primary_id = session.primary_id
      AND (
          EXISTS (
              SELECT 1
              FROM auth.role_permission role_permission
              JOIN auth.user_role user_role
                ON user_role.role_id = role_permission.role_id
              WHERE role_permission.permission_id = target_permission_id
                AND role_permission.is_active = TRUE
                AND user_role.is_active = TRUE
                AND user_role.user_id = security_state.user_id
          )
          OR EXISTS (
              SELECT 1
              FROM auth.user_permission_override permission_override
              WHERE permission_override.permission_id = target_permission_id
                AND permission_override.is_active = TRUE
                AND permission_override.user_id = security_state.user_id
          )
      );

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;
