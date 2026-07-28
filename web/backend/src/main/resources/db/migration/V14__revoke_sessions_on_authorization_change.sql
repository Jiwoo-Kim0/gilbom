-- 인증 완료 시 SecurityContext에 유효 권한 snapshot을 저장하므로 권한의 입력값이
-- 바뀌면 대상 사용자의 기존 session을 같은 transaction에서 모두 폐기한다.
-- DB trigger로 강제해 관리자 API 외의 DML 경로에서도 stale authority를 남기지 않는다.
CREATE FUNCTION auth.revoke_user_authorization_sessions(
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
BEGIN
    DELETE FROM session_store.spring_session session
    USING session_store.session_security_state security_state
    WHERE security_state.session_primary_id = session.primary_id
      AND security_state.user_id = target_user_id;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

REVOKE ALL
    ON FUNCTION auth.revoke_user_authorization_sessions(UUID)
    FROM PUBLIC;

COMMENT ON FUNCTION auth.revoke_user_authorization_sessions(UUID) IS
    '권한·객체 범위 변경과 같은 transaction에서 대상 사용자의 인증·challenge session을 전부 폐기';

-- 사용자-역할 관계는 추가·변경·종료 모두 역할 목록이나 유효 권한을 바꾼다.
CREATE FUNCTION auth.revoke_sessions_on_user_role_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM auth.revoke_user_authorization_sessions(OLD.user_id);
        RETURN OLD;
    END IF;

    PERFORM auth.revoke_user_authorization_sessions(NEW.user_id);

    IF TG_OP = 'UPDATE' AND OLD.user_id <> NEW.user_id THEN
        PERFORM auth.revoke_user_authorization_sessions(OLD.user_id);
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.revoke_sessions_on_user_role_change()
    FROM PUBLIC;

CREATE TRIGGER tr_user_role_revoke_sessions
    AFTER INSERT OR UPDATE OR DELETE ON auth.user_role
    FOR EACH ROW
    EXECUTE FUNCTION auth.revoke_sessions_on_user_role_change();

-- 역할의 permission이 바뀌면 그 역할을 현재 가진 모든 사용자의 snapshot을 폐기한다.
CREATE FUNCTION auth.revoke_role_authorization_sessions(
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

REVOKE ALL
    ON FUNCTION auth.revoke_role_authorization_sessions(UUID)
    FROM PUBLIC;

CREATE FUNCTION auth.revoke_sessions_on_role_permission_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM auth.revoke_role_authorization_sessions(OLD.role_id);
        RETURN OLD;
    END IF;

    PERFORM auth.revoke_role_authorization_sessions(NEW.role_id);

    IF TG_OP = 'UPDATE' AND OLD.role_id <> NEW.role_id THEN
        PERFORM auth.revoke_role_authorization_sessions(OLD.role_id);
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL
    ON FUNCTION auth.revoke_sessions_on_role_permission_change()
    FROM PUBLIC;

CREATE TRIGGER tr_role_permission_revoke_sessions
    AFTER INSERT OR UPDATE OR DELETE ON auth.role_permission
    FOR EACH ROW
    EXECUTE FUNCTION auth.revoke_sessions_on_role_permission_change();

-- 역할 code/system 여부/활성 상태가 바뀌면 역할 목록 또는 권한 snapshot이 달라진다.
CREATE FUNCTION auth.revoke_sessions_on_role_lifecycle_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM auth.revoke_role_authorization_sessions(OLD.role_id);
        RETURN OLD;
    END IF;

    IF OLD.role_code IS DISTINCT FROM NEW.role_code
       OR OLD.is_system IS DISTINCT FROM NEW.is_system
       OR OLD.is_active IS DISTINCT FROM NEW.is_active THEN
        PERFORM auth.revoke_role_authorization_sessions(NEW.role_id);
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL
    ON FUNCTION auth.revoke_sessions_on_role_lifecycle_change()
    FROM PUBLIC;

CREATE TRIGGER tr_role_lifecycle_revoke_sessions
    AFTER UPDATE OF role_code, is_system, is_active OR DELETE ON auth.role
    FOR EACH ROW
    EXECUTE FUNCTION auth.revoke_sessions_on_role_lifecycle_change();

-- permission code 또는 활성 상태 변경은 role과 사용자 override 양쪽 사용자를 폐기한다.
CREATE FUNCTION auth.revoke_permission_authorization_sessions(
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
    DELETE FROM session_store.spring_session session
    USING session_store.session_security_state security_state
    WHERE security_state.session_primary_id = session.primary_id
      AND (
          EXISTS (
              SELECT 1
              FROM auth.role_permission role_permission
              JOIN auth.user_role user_role
                ON user_role.role_id = role_permission.role_id
              WHERE role_permission.permission_id =
                    target_permission_id
                AND role_permission.is_active = TRUE
                AND user_role.is_active = TRUE
                AND user_role.user_id = security_state.user_id
          )
          OR EXISTS (
              SELECT 1
              FROM auth.user_permission_override
                   permission_override
              WHERE permission_override.permission_id =
                    target_permission_id
                AND permission_override.is_active = TRUE
                AND permission_override.user_id =
                    security_state.user_id
          )
      );

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

REVOKE ALL
    ON FUNCTION auth.revoke_permission_authorization_sessions(UUID)
    FROM PUBLIC;

CREATE FUNCTION auth.revoke_sessions_on_permission_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM auth.revoke_permission_authorization_sessions(
            OLD.permission_id
        );
        RETURN OLD;
    END IF;

    IF OLD.is_active IS DISTINCT FROM NEW.is_active
       OR OLD.permission_code IS DISTINCT FROM NEW.permission_code THEN
        PERFORM auth.revoke_permission_authorization_sessions(
            NEW.permission_id
        );
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.revoke_sessions_on_permission_change()
    FROM PUBLIC;

CREATE TRIGGER tr_permission_revoke_sessions
    AFTER UPDATE OF is_active, permission_code OR DELETE
    ON auth.permission
    FOR EACH ROW
    EXECUTE FUNCTION auth.revoke_sessions_on_permission_change();

-- 사용자 override와 로봇 객체 범위는 해당 사용자 한 명의 session만 폐기한다.
CREATE FUNCTION auth.revoke_sessions_on_user_scope_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM auth.revoke_user_authorization_sessions(OLD.user_id);
        RETURN OLD;
    END IF;

    PERFORM auth.revoke_user_authorization_sessions(NEW.user_id);

    IF TG_OP = 'UPDATE' AND OLD.user_id <> NEW.user_id THEN
        PERFORM auth.revoke_user_authorization_sessions(OLD.user_id);
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.revoke_sessions_on_user_scope_change()
    FROM PUBLIC;

CREATE TRIGGER tr_user_permission_override_revoke_sessions
    AFTER INSERT OR UPDATE OR DELETE
    ON auth.user_permission_override
    FOR EACH ROW
    EXECUTE FUNCTION auth.revoke_sessions_on_user_scope_change();

CREATE TRIGGER tr_user_robot_assignment_revoke_sessions
    AFTER INSERT OR UPDATE OR DELETE
    ON auth.user_robot_assignment
    FOR EACH ROW
    EXECUTE FUNCTION auth.revoke_sessions_on_user_scope_change();

-- 관리자 승인·비활성화·삭제 상태 전환도 이전 인증 session을 유지하지 않는다.
CREATE FUNCTION auth.revoke_sessions_on_account_lifecycle_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF OLD.account_status IS DISTINCT FROM NEW.account_status
       OR OLD.is_active IS DISTINCT FROM NEW.is_active
       OR OLD.deleted_at IS DISTINCT FROM NEW.deleted_at THEN
        PERFORM auth.revoke_user_authorization_sessions(NEW.user_id);
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL
    ON FUNCTION auth.revoke_sessions_on_account_lifecycle_change()
    FROM PUBLIC;

CREATE TRIGGER tr_admin_user_lifecycle_revoke_sessions
    AFTER UPDATE OF account_status, is_active, deleted_at
    ON auth.admin_user
    FOR EACH ROW
    EXECUTE FUNCTION auth.revoke_sessions_on_account_lifecycle_change();
