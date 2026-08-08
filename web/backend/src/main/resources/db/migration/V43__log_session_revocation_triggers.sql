-- 권한 변경 trigger가 session을 폐기할 때 흔적을 남긴다.
--
-- V14의 trigger들은 session 행을 DB 안에서 직접 지운다. 애플리케이션은 그 사실을 전혀
-- 알지 못하므로, 사용자는 다음 요청에서 401을 받고 로그인 화면으로 튕기는데 backend
-- 로그에는 아무것도 남지 않았다. idle 만료로 사라진 것인지 trigger가 지운 것인지
-- 구분할 방법이 없었다.
--
-- 실제로 폐기된 session이 있을 때만 남긴다. 로그인한 사용자가 없는 대상에 대한 평범한
-- 관리 작업까지 남기면 소음만 늘고 정작 찾는 줄이 묻힌다.
--
-- 판정 자체는 하나도 바꾸지 않는다. PERFORM을 대입으로 바꿔 삭제 건수를 받고,
-- 0보다 클 때 RAISE LOG를 더한 것뿐이다.

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_user_role_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    revoked INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        revoked := auth.revoke_user_authorization_sessions(OLD.user_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, user=%, sessions=%',
                TG_TABLE_NAME, TG_OP, OLD.user_id, revoked;
        END IF;
        RETURN OLD;
    END IF;

    revoked := auth.revoke_user_authorization_sessions(NEW.user_id);
    IF revoked > 0 THEN
        RAISE LOG 'session revoked by trigger: table=%, op=%, user=%, sessions=%',
            TG_TABLE_NAME, TG_OP, NEW.user_id, revoked;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.user_id <> NEW.user_id THEN
        revoked := auth.revoke_user_authorization_sessions(OLD.user_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, user=%, sessions=%',
                TG_TABLE_NAME, TG_OP, OLD.user_id, revoked;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.revoke_sessions_on_user_role_change()
    FROM PUBLIC;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_role_permission_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    revoked INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        revoked := auth.revoke_role_authorization_sessions(OLD.role_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, role=%, sessions=%',
                TG_TABLE_NAME, TG_OP, OLD.role_id, revoked;
        END IF;
        RETURN OLD;
    END IF;

    revoked := auth.revoke_role_authorization_sessions(NEW.role_id);
    IF revoked > 0 THEN
        RAISE LOG 'session revoked by trigger: table=%, op=%, role=%, sessions=%',
            TG_TABLE_NAME, TG_OP, NEW.role_id, revoked;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.role_id <> NEW.role_id THEN
        revoked := auth.revoke_role_authorization_sessions(OLD.role_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, role=%, sessions=%',
                TG_TABLE_NAME, TG_OP, OLD.role_id, revoked;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL
    ON FUNCTION auth.revoke_sessions_on_role_permission_change()
    FROM PUBLIC;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_role_lifecycle_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    revoked INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        revoked := auth.revoke_role_authorization_sessions(OLD.role_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, role=%, sessions=%',
                TG_TABLE_NAME, TG_OP, OLD.role_id, revoked;
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.role_code IS DISTINCT FROM NEW.role_code
       OR OLD.is_system IS DISTINCT FROM NEW.is_system
       OR OLD.is_active IS DISTINCT FROM NEW.is_active THEN
        revoked := auth.revoke_role_authorization_sessions(NEW.role_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, role=%, sessions=%',
                TG_TABLE_NAME, TG_OP, NEW.role_id, revoked;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL
    ON FUNCTION auth.revoke_sessions_on_role_lifecycle_change()
    FROM PUBLIC;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_permission_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    revoked INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        revoked := auth.revoke_permission_authorization_sessions(
            OLD.permission_id
        );
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, permission=%, sessions=%',
                TG_TABLE_NAME, TG_OP, OLD.permission_id, revoked;
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.is_active IS DISTINCT FROM NEW.is_active
       OR OLD.permission_code IS DISTINCT FROM NEW.permission_code THEN
        revoked := auth.revoke_permission_authorization_sessions(
            NEW.permission_id
        );
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, permission=%, sessions=%',
                TG_TABLE_NAME, TG_OP, NEW.permission_id, revoked;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.revoke_sessions_on_permission_change()
    FROM PUBLIC;

-- 사용자 override와 로봇 객체 범위는 해당 사용자 한 명의 session만 폐기한다.
-- 값이 실제로 바뀌었는지 보지 않고 행이 건드려지기만 하면 발동한다는 점은 그대로 둔다
-- (권한 입력값의 재기록도 stale authority로 볼 수 있다). 이제 그 사실이 로그에 드러난다.
CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_user_scope_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    revoked INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        revoked := auth.revoke_user_authorization_sessions(OLD.user_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, user=%, sessions=%',
                TG_TABLE_NAME, TG_OP, OLD.user_id, revoked;
        END IF;
        RETURN OLD;
    END IF;

    revoked := auth.revoke_user_authorization_sessions(NEW.user_id);
    IF revoked > 0 THEN
        RAISE LOG 'session revoked by trigger: table=%, op=%, user=%, sessions=%',
            TG_TABLE_NAME, TG_OP, NEW.user_id, revoked;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.user_id <> NEW.user_id THEN
        revoked := auth.revoke_user_authorization_sessions(OLD.user_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, user=%, sessions=%',
                TG_TABLE_NAME, TG_OP, OLD.user_id, revoked;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.revoke_sessions_on_user_scope_change()
    FROM PUBLIC;

CREATE OR REPLACE FUNCTION auth.revoke_sessions_on_account_lifecycle_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    revoked INTEGER;
BEGIN
    IF OLD.account_status IS DISTINCT FROM NEW.account_status
       OR OLD.is_active IS DISTINCT FROM NEW.is_active
       OR OLD.deleted_at IS DISTINCT FROM NEW.deleted_at THEN
        revoked := auth.revoke_user_authorization_sessions(NEW.user_id);
        IF revoked > 0 THEN
            RAISE LOG 'session revoked by trigger: table=%, op=%, user=%, sessions=%',
                TG_TABLE_NAME, TG_OP, NEW.user_id, revoked;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL
    ON FUNCTION auth.revoke_sessions_on_account_lifecycle_change()
    FROM PUBLIC;
