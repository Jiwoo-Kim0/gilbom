-- V14의 권한 변경 trigger는 stale authority가 남지 않도록 session을 즉시
-- 폐기하지만, 어떤 trigger가 실제 session을 삭제했는지는 관찰할 수 없었다.
-- 기존 audit API와 화면은 audit.audit_log의 임의 action을 그대로 표시하므로
-- 별도 API 없이 DB 경계에서 원인과 삭제 건수를 기록한다.

CREATE FUNCTION auth.record_authorization_session_revocation(
    target_type_code VARCHAR,
    target_entity_id UUID,
    revocation_source VARCHAR,
    trigger_name VARCHAR,
    trigger_operation VARCHAR,
    deleted_session_count INTEGER
)
RETURNS VOID
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, audit
AS $$
BEGIN
    IF deleted_session_count <= 0 THEN
        RETURN;
    END IF;

    IF target_type_code NOT IN ('USER', 'ROLE', 'PERMISSION')
       OR revocation_source NOT IN (
           'USER_ROLE',
           'ROLE_PERMISSION',
           'ROLE_LIFECYCLE',
           'PERMISSION_LIFECYCLE',
           'USER_PERMISSION_OVERRIDE',
           'USER_ROBOT_ASSIGNMENT',
           'ACCOUNT_LIFECYCLE'
       )
       OR trigger_name !~ '^tr_[a-z0-9_]{1,76}$'
       OR trigger_operation NOT IN ('INSERT', 'UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'invalid authorization session revocation audit input'
            USING ERRCODE = '22023';
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
        'AUTH_SESSION_REVOKED',
        target_type_code,
        target_entity_id,
        NULL,
        jsonb_build_object(
            'deletedSessionCount', deleted_session_count,
            'source', revocation_source,
            'trigger', trigger_name,
            'operation', trigger_operation
        ),
        'AUTHORIZATION_CHANGE:' || revocation_source,
        'SUCCESS',
        NULL,
        NULL
    );
END;
$$;

REVOKE ALL
    ON FUNCTION auth.record_authorization_session_revocation(
        VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, INTEGER
    )
    FROM PUBLIC;

COMMENT ON FUNCTION auth.record_authorization_session_revocation(
    VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, INTEGER
) IS
    '권한 변경 DB trigger가 실제 session을 삭제했을 때 원인·trigger·DML·건수를 SYSTEM 감사 로그로 기록';

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_user_role_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        deleted_count := auth.revoke_user_authorization_sessions(OLD.user_id);
        PERFORM auth.record_authorization_session_revocation(
            'USER', OLD.user_id, 'USER_ROLE',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
        RETURN OLD;
    END IF;

    deleted_count := auth.revoke_user_authorization_sessions(NEW.user_id);
    PERFORM auth.record_authorization_session_revocation(
        'USER', NEW.user_id, 'USER_ROLE',
        TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
    );

    IF TG_OP = 'UPDATE' AND OLD.user_id <> NEW.user_id THEN
        deleted_count := auth.revoke_user_authorization_sessions(OLD.user_id);
        PERFORM auth.record_authorization_session_revocation(
            'USER', OLD.user_id, 'USER_ROLE',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_role_permission_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        deleted_count := auth.revoke_role_authorization_sessions(OLD.role_id);
        PERFORM auth.record_authorization_session_revocation(
            'ROLE', OLD.role_id, 'ROLE_PERMISSION',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
        RETURN OLD;
    END IF;

    deleted_count := auth.revoke_role_authorization_sessions(NEW.role_id);
    PERFORM auth.record_authorization_session_revocation(
        'ROLE', NEW.role_id, 'ROLE_PERMISSION',
        TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
    );

    IF TG_OP = 'UPDATE' AND OLD.role_id <> NEW.role_id THEN
        deleted_count := auth.revoke_role_authorization_sessions(OLD.role_id);
        PERFORM auth.record_authorization_session_revocation(
            'ROLE', OLD.role_id, 'ROLE_PERMISSION',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_role_lifecycle_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        deleted_count := auth.revoke_role_authorization_sessions(OLD.role_id);
        PERFORM auth.record_authorization_session_revocation(
            'ROLE', OLD.role_id, 'ROLE_LIFECYCLE',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
        RETURN OLD;
    END IF;

    IF OLD.role_code IS DISTINCT FROM NEW.role_code
       OR OLD.is_system IS DISTINCT FROM NEW.is_system
       OR OLD.is_active IS DISTINCT FROM NEW.is_active THEN
        deleted_count := auth.revoke_role_authorization_sessions(NEW.role_id);
        PERFORM auth.record_authorization_session_revocation(
            'ROLE', NEW.role_id, 'ROLE_LIFECYCLE',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_permission_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        deleted_count := auth.revoke_permission_authorization_sessions(
            OLD.permission_id
        );
        PERFORM auth.record_authorization_session_revocation(
            'PERMISSION', OLD.permission_id, 'PERMISSION_LIFECYCLE',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
        RETURN OLD;
    END IF;

    IF OLD.is_active IS DISTINCT FROM NEW.is_active
       OR OLD.permission_code IS DISTINCT FROM NEW.permission_code THEN
        deleted_count := auth.revoke_permission_authorization_sessions(
            NEW.permission_id
        );
        PERFORM auth.record_authorization_session_revocation(
            'PERMISSION', NEW.permission_id, 'PERMISSION_LIFECYCLE',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_user_scope_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    deleted_count INTEGER;
    source_code VARCHAR;
BEGIN
    source_code := CASE TG_TABLE_NAME
        WHEN 'user_permission_override' THEN 'USER_PERMISSION_OVERRIDE'
        WHEN 'user_robot_assignment' THEN 'USER_ROBOT_ASSIGNMENT'
        ELSE NULL
    END;

    IF source_code IS NULL THEN
        RAISE EXCEPTION 'unsupported user scope revocation table: %',
            TG_TABLE_NAME
            USING ERRCODE = '22023';
    END IF;

    IF TG_OP = 'DELETE' THEN
        deleted_count := auth.revoke_user_authorization_sessions(OLD.user_id);
        PERFORM auth.record_authorization_session_revocation(
            'USER', OLD.user_id, source_code,
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
        RETURN OLD;
    END IF;

    deleted_count := auth.revoke_user_authorization_sessions(NEW.user_id);
    PERFORM auth.record_authorization_session_revocation(
        'USER', NEW.user_id, source_code,
        TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
    );

    IF TG_OP = 'UPDATE' AND OLD.user_id <> NEW.user_id THEN
        deleted_count := auth.revoke_user_authorization_sessions(OLD.user_id);
        PERFORM auth.record_authorization_session_revocation(
            'USER', OLD.user_id, source_code,
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_account_lifecycle_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    IF OLD.account_status IS DISTINCT FROM NEW.account_status
       OR OLD.is_active IS DISTINCT FROM NEW.is_active
       OR OLD.deleted_at IS DISTINCT FROM NEW.deleted_at THEN
        deleted_count := auth.revoke_user_authorization_sessions(NEW.user_id);
        PERFORM auth.record_authorization_session_revocation(
            'USER', NEW.user_id, 'ACCOUNT_LIFECYCLE',
            TG_NAME::VARCHAR, TG_OP::VARCHAR, deleted_count
        );
    END IF;
    RETURN NEW;
END;
$$;
