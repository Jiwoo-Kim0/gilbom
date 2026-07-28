-- 사용자별 권한 예외와 로봇 객체 범위를 추가한다.
-- 역할 권한, 사용자 ALLOW, 사용자 DENY는 조회 시점의 is_active와 expires_at을
-- 함께 평가하며, 동일 권한에 ALLOW와 DENY가 모두 유효하면 DENY를 우선한다.
CREATE TABLE auth.user_permission_override (
    permission_override_id UUID DEFAULT gen_random_uuid() NOT NULL,
    user_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    override_effect VARCHAR(10) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    granted_by UUID NOT NULL,
    granted_at TIMESTAMPTZ DEFAULT statement_timestamp() NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_by UUID,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    updated_at TIMESTAMPTZ DEFAULT statement_timestamp() NOT NULL,
    CONSTRAINT pk_user_permission_override
        PRIMARY KEY (permission_override_id),
    CONSTRAINT ck_user_permission_override_effect CHECK (
        override_effect IN ('ALLOW', 'DENY')
    ),
    CONSTRAINT ck_user_permission_override_reason CHECK (
        reason = BTRIM(reason)
        AND reason <> ''
        AND reason !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_user_permission_override_expiry CHECK (
        expires_at IS NULL OR expires_at > granted_at
    ),
    CONSTRAINT ck_user_permission_override_lifecycle CHECK (
        (
            is_active = TRUE
            AND revoked_by IS NULL
            AND revoked_at IS NULL
            AND revocation_reason IS NULL
        )
        OR
        (
            is_active = FALSE
            AND revoked_by IS NOT NULL
            AND revoked_at IS NOT NULL
            AND revoked_at >= granted_at
            AND revocation_reason IS NOT NULL
            AND revocation_reason = BTRIM(revocation_reason)
            AND revocation_reason <> ''
            AND revocation_reason !~ '[[:cntrl:]]'
        )
    ),
    CONSTRAINT ck_user_permission_override_timestamps CHECK (
        updated_at >= granted_at
        AND (revoked_at IS NULL OR updated_at >= revoked_at)
    ),
    CONSTRAINT fk_user_permission_override_user
        FOREIGN KEY (user_id)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_user_permission_override_permission
        FOREIGN KEY (permission_id)
        REFERENCES auth.permission (permission_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_user_permission_override_granted_by
        FOREIGN KEY (granted_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_user_permission_override_revoked_by
        FOREIGN KEY (revoked_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

-- 같은 권한에는 유효한 ALLOW와 DENY가 동시에 있을 수 있지만, 같은 effect의
-- 활성 행은 하나만 둔다. 만료된 활성 행은 명시적으로 종료한 뒤 새 행을 추가한다.
CREATE UNIQUE INDEX uq_user_permission_override_active_effect
    ON auth.user_permission_override (
        user_id,
        permission_id,
        override_effect
    )
    WHERE is_active = TRUE;

-- 로그인 완료 시 한 사용자의 전체 유효 권한을 계산하는 접근 경로다.
CREATE INDEX ix_user_permission_override_active_user
    ON auth.user_permission_override (
        user_id,
        permission_id,
        override_effect,
        expires_at
    )
    WHERE is_active = TRUE;

-- 관리자 화면의 권한별 부여 현황과 마지막 ADMIN 검사를 지원한다.
CREATE INDEX ix_user_permission_override_active_permission
    ON auth.user_permission_override (
        permission_id,
        override_effect,
        user_id,
        expires_at
    )
    WHERE is_active = TRUE;

CREATE INDEX ix_user_permission_override_expiry
    ON auth.user_permission_override (expires_at, permission_override_id)
    WHERE is_active = TRUE AND expires_at IS NOT NULL;

COMMENT ON TABLE auth.user_permission_override IS
    'Role 권한 합집합에 추가하거나 제거하는 사용자별 ALLOW/DENY 예외와 수명주기';
COMMENT ON COLUMN auth.user_permission_override.override_effect IS
    'ALLOW는 역할 권한에 추가하고 DENY는 역할·ALLOW 결과에서 제거한다. DENY 우선';
COMMENT ON COLUMN auth.user_permission_override.reason IS
    '권한 예외를 부여한 업무 사유. 비밀번호, token, session ID, secret 기록 금지';
COMMENT ON COLUMN auth.user_permission_override.expires_at IS
    'NULL이면 무기한. 현재 시각 이하이면 is_active가 TRUE여도 권한 계산에서 제외';
COMMENT ON COLUMN auth.user_permission_override.is_active IS
    '관리자가 명시적으로 종료하지 않은 lifecycle 상태. expires_at 유효성은 별도 평가';

-- 배정 등급은 역할과 독립적인 로봇 객체 범위다.
CREATE TABLE auth.user_robot_assignment (
    user_robot_assignment_id UUID DEFAULT gen_random_uuid() NOT NULL,
    user_id UUID NOT NULL,
    robot_id UUID NOT NULL,
    assignment_type VARCHAR(10) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    assigned_by UUID NOT NULL,
    assigned_at TIMESTAMPTZ DEFAULT statement_timestamp() NOT NULL,
    expires_at TIMESTAMPTZ,
    ended_by UUID,
    ended_at TIMESTAMPTZ,
    end_reason VARCHAR(1000),
    updated_at TIMESTAMPTZ DEFAULT statement_timestamp() NOT NULL,
    CONSTRAINT pk_user_robot_assignment
        PRIMARY KEY (user_robot_assignment_id),
    CONSTRAINT ck_user_robot_assignment_type CHECK (
        assignment_type IN ('VIEWER', 'OPERATOR', 'OWNER')
    ),
    CONSTRAINT ck_user_robot_assignment_reason CHECK (
        reason = BTRIM(reason)
        AND reason <> ''
        AND reason !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_user_robot_assignment_expiry CHECK (
        expires_at IS NULL OR expires_at > assigned_at
    ),
    CONSTRAINT ck_user_robot_assignment_lifecycle CHECK (
        (
            is_active = TRUE
            AND ended_by IS NULL
            AND ended_at IS NULL
            AND end_reason IS NULL
        )
        OR
        (
            is_active = FALSE
            AND ended_by IS NOT NULL
            AND ended_at IS NOT NULL
            AND ended_at >= assigned_at
            AND end_reason IS NOT NULL
            AND end_reason = BTRIM(end_reason)
            AND end_reason <> ''
            AND end_reason !~ '[[:cntrl:]]'
        )
    ),
    CONSTRAINT ck_user_robot_assignment_timestamps CHECK (
        updated_at >= assigned_at
        AND (ended_at IS NULL OR updated_at >= ended_at)
    ),
    CONSTRAINT fk_user_robot_assignment_user
        FOREIGN KEY (user_id)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_user_robot_assignment_robot
        FOREIGN KEY (robot_id)
        REFERENCES robot.robot (robot_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_user_robot_assignment_assigned_by
        FOREIGN KEY (assigned_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_user_robot_assignment_ended_by
        FOREIGN KEY (ended_by)
        REFERENCES auth.admin_user (user_id)
        ON DELETE RESTRICT
);

-- 한 사용자에게 같은 로봇의 VIEWER/OPERATOR/OWNER를 중첩하지 않는다.
-- 등급 변경은 기존 행을 종료하고 새 행을 추가해 감사 가능한 수명주기를 남긴다.
CREATE UNIQUE INDEX uq_user_robot_assignment_active_robot
    ON auth.user_robot_assignment (user_id, robot_id)
    WHERE is_active = TRUE;

-- 목록·통계·SSE query가 권한 없는 행을 읽지 않고 사용자 범위부터 제한하는 index다.
CREATE INDEX ix_user_robot_assignment_active_user_scope
    ON auth.user_robot_assignment (
        user_id,
        assignment_type,
        robot_id,
        expires_at
    )
    WHERE is_active = TRUE;

-- 관리자 배정 현황과 특정 로봇의 사용자 역조회용 index다.
CREATE INDEX ix_user_robot_assignment_active_robot_users
    ON auth.user_robot_assignment (
        robot_id,
        user_id,
        assignment_type,
        expires_at
    )
    WHERE is_active = TRUE;

CREATE INDEX ix_user_robot_assignment_expiry
    ON auth.user_robot_assignment (
        expires_at,
        user_robot_assignment_id
    )
    WHERE is_active = TRUE AND expires_at IS NOT NULL;

COMMENT ON TABLE auth.user_robot_assignment IS
    '사용자의 로봇 및 로봇 파생 리소스 VIEWER/OPERATOR/OWNER 객체 범위';
COMMENT ON COLUMN auth.user_robot_assignment.assignment_type IS
    'VIEWER는 조회, OPERATOR는 조회·제어, OWNER는 조회·제어·임무 관리를 허용';
COMMENT ON COLUMN auth.user_robot_assignment.reason IS
    '로봇 객체 범위를 부여한 업무 사유. 민감 인증정보 기록 금지';
COMMENT ON COLUMN auth.user_robot_assignment.expires_at IS
    'NULL이면 무기한. 현재 시각 이하이면 is_active가 TRUE여도 query 범위에서 제외';

-- 시스템 ADMIN 역할과 ADMIN_SETTINGS_MANAGE catalog 자체를 비활성화하거나
-- 다른 code로 바꾸면 모든 관리자가 동시에 사라질 수 있으므로 불변 조건으로 보호한다.
CREATE FUNCTION auth.protect_system_admin_role_catalog()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF OLD.role_code = 'ADMIN' AND OLD.is_system = TRUE THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'system ADMIN role is protected'
                USING ERRCODE = '42501';
        END IF;

        IF NEW.role_code <> 'ADMIN'
           OR NEW.is_system = FALSE
           OR NEW.is_active = FALSE THEN
            RAISE EXCEPTION 'system ADMIN role is protected'
                USING ERRCODE = '42501';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.protect_system_admin_role_catalog() FROM PUBLIC;

CREATE TRIGGER tr_role_protect_system_admin
    BEFORE UPDATE OR DELETE ON auth.role
    FOR EACH ROW EXECUTE FUNCTION auth.protect_system_admin_role_catalog();

CREATE FUNCTION auth.protect_admin_settings_permission_catalog()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF OLD.permission_code = 'ADMIN_SETTINGS_MANAGE' THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'ADMIN_SETTINGS_MANAGE permission is protected'
                USING ERRCODE = '42501';
        END IF;

        IF NEW.permission_code <> 'ADMIN_SETTINGS_MANAGE'
           OR NEW.is_system = FALSE
           OR NEW.is_active = FALSE THEN
            RAISE EXCEPTION 'ADMIN_SETTINGS_MANAGE permission is protected'
                USING ERRCODE = '42501';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.protect_admin_settings_permission_catalog() FROM PUBLIC;

CREATE TRIGGER tr_permission_protect_admin_settings
    BEFORE UPDATE OR DELETE ON auth.permission
    FOR EACH ROW
    EXECUTE FUNCTION auth.protect_admin_settings_permission_catalog();

-- V4의 제한 권한 trigger를 확장한다. 시스템 ADMIN의 관리 권한은 제거할 수 없고,
-- 다른 역할에는 활성 상태로 부여할 수 없다.
CREATE OR REPLACE FUNCTION auth.enforce_restricted_permission_assignment()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    old_is_protected BOOLEAN := FALSE;
    new_is_protected BOOLEAN := FALSE;
    new_permission_code VARCHAR(80);
    new_role_code VARCHAR(50);
    new_role_is_system BOOLEAN;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT (
            role.role_code = 'ADMIN'
            AND role.is_system = TRUE
            AND permission.permission_code = 'ADMIN_SETTINGS_MANAGE'
            AND OLD.is_active = TRUE
        )
          INTO old_is_protected
          FROM auth.role role
          JOIN auth.permission permission
            ON permission.permission_id = OLD.permission_id
         WHERE role.role_id = OLD.role_id;
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF old_is_protected THEN
            RAISE EXCEPTION 'system ADMIN management permission is protected'
                USING ERRCODE = '42501';
        END IF;
        RETURN OLD;
    END IF;

    SELECT role.role_code, role.is_system, permission.permission_code
      INTO new_role_code, new_role_is_system, new_permission_code
      FROM auth.role role
      JOIN auth.permission permission
        ON permission.permission_id = NEW.permission_id
     WHERE role.role_id = NEW.role_id;

    new_is_protected := (
        new_role_code = 'ADMIN'
        AND new_role_is_system = TRUE
        AND new_permission_code = 'ADMIN_SETTINGS_MANAGE'
        AND NEW.is_active = TRUE
    );

    IF NEW.is_active
       AND new_permission_code = 'ADMIN_SETTINGS_MANAGE'
       AND (
           new_role_code <> 'ADMIN'
           OR new_role_is_system = FALSE
       ) THEN
        RAISE EXCEPTION 'restricted permission assignment'
            USING ERRCODE = '42501';
    END IF;

    IF old_is_protected AND NOT new_is_protected THEN
        RAISE EXCEPTION 'system ADMIN management permission is protected'
            USING ERRCODE = '42501';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.enforce_restricted_permission_assignment() FROM PUBLIC;

DROP TRIGGER tr_role_permission_restricted_assignment
    ON auth.role_permission;

CREATE TRIGGER tr_role_permission_restricted_assignment
    BEFORE INSERT OR UPDATE OR DELETE ON auth.role_permission
    FOR EACH ROW
    EXECUTE FUNCTION auth.enforce_restricted_permission_assignment();

-- 마지막 ADMIN을 검사하는 모든 변경은 같은 transaction advisory lock을 사용한다.
-- 애플리케이션 서비스도 변경 transaction의 첫 DB statement로 이 함수를 호출해야 한다.
CREATE FUNCTION auth.lock_last_admin_guard()
RETURNS VOID
LANGUAGE sql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
    SELECT pg_catalog.pg_advisory_xact_lock(1195983954, 1094994254);
$$;

REVOKE ALL ON FUNCTION auth.lock_last_admin_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.lock_last_admin_guard()
    TO "${applicationRole}";

COMMENT ON FUNCTION auth.lock_last_admin_guard() IS
    'ADMIN 감소 변경을 직렬화하는 transaction lock. 서비스 transaction의 첫 DB statement로 호출';

-- 유효한 시스템 ADMIN은 활성 계정, 활성 시스템 ADMIN 역할, 활성 관리 permission을
-- 모두 가지며 만료되지 않은 사용자 DENY로 차단되지 않은 사용자다.
CREATE FUNCTION auth.is_active_system_admin(
    target_user_id UUID,
    evaluated_at TIMESTAMPTZ DEFAULT statement_timestamp()
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM auth.admin_user admin_user
        JOIN auth.user_role user_role
          ON user_role.user_id = admin_user.user_id
        JOIN auth.role role
          ON role.role_id = user_role.role_id
        JOIN auth.role_permission role_permission
          ON role_permission.role_id = role.role_id
        JOIN auth.permission permission
          ON permission.permission_id = role_permission.permission_id
        WHERE admin_user.user_id = target_user_id
          AND admin_user.account_status = 'ACTIVE'
          AND admin_user.is_active = TRUE
          AND admin_user.deleted_at IS NULL
          AND user_role.is_active = TRUE
          AND role.role_code = 'ADMIN'
          AND role.is_system = TRUE
          AND role.is_active = TRUE
          AND role_permission.is_active = TRUE
          AND permission.permission_code = 'ADMIN_SETTINGS_MANAGE'
          AND permission.is_active = TRUE
          AND NOT EXISTS (
              SELECT 1
              FROM auth.user_permission_override denied_override
              JOIN auth.permission denied_permission
                ON denied_permission.permission_id =
                   denied_override.permission_id
              WHERE denied_override.user_id = admin_user.user_id
                AND denied_override.override_effect = 'DENY'
                AND denied_override.is_active = TRUE
                AND (
                    denied_override.expires_at IS NULL
                    OR denied_override.expires_at > evaluated_at
                )
                AND denied_permission.permission_code =
                    'ADMIN_SETTINGS_MANAGE'
                AND denied_permission.is_active = TRUE
          )
    );
$$;

REVOKE ALL ON FUNCTION auth.is_active_system_admin(UUID, TIMESTAMPTZ)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.is_active_system_admin(UUID, TIMESTAMPTZ)
    TO "${applicationRole}";

COMMENT ON FUNCTION auth.is_active_system_admin(UUID, TIMESTAMPTZ) IS
    '계정·시스템 ADMIN 역할·관리 permission·사용자 DENY 만료를 반영한 활성 관리자 판정';

CREATE FUNCTION auth.assert_last_active_admin_preserved(
    target_user_id UUID
)
RETURNS VOID
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    evaluated_at TIMESTAMPTZ := statement_timestamp();
    active_admin_count BIGINT;
BEGIN
    PERFORM auth.lock_last_admin_guard();

    IF NOT auth.is_active_system_admin(target_user_id, evaluated_at) THEN
        RETURN;
    END IF;

    SELECT COUNT(DISTINCT user_role.user_id)
      INTO active_admin_count
      FROM auth.user_role user_role
      JOIN auth.admin_user admin_user
        ON admin_user.user_id = user_role.user_id
     WHERE user_role.is_active = TRUE
       AND admin_user.account_status = 'ACTIVE'
       AND admin_user.is_active = TRUE
       AND admin_user.deleted_at IS NULL
       AND auth.is_active_system_admin(
           user_role.user_id,
           evaluated_at
       );

    IF active_admin_count <= 1 THEN
        RAISE EXCEPTION 'last active system ADMIN must be preserved'
            USING ERRCODE = '42501';
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION auth.assert_last_active_admin_preserved(UUID)
    FROM PUBLIC;

-- 계정 비활성화·삭제 상태 전환으로 마지막 ADMIN이 사라지는 경로를 막는다.
CREATE FUNCTION auth.protect_last_admin_on_user_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
BEGIN
    IF (
        NEW.account_status <> 'ACTIVE'
        OR NEW.is_active = FALSE
        OR NEW.deleted_at IS NOT NULL
    ) THEN
        PERFORM auth.assert_last_active_admin_preserved(OLD.user_id);
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.protect_last_admin_on_user_change()
    FROM PUBLIC;

CREATE TRIGGER tr_admin_user_protect_last_admin
    BEFORE UPDATE OF account_status, is_active, deleted_at
    ON auth.admin_user
    FOR EACH ROW
    EXECUTE FUNCTION auth.protect_last_admin_on_user_change();

-- 시스템 ADMIN 역할 해제·행 삭제·다른 사용자/역할로 PK 변경하는 경로를 막는다.
CREATE FUNCTION auth.protect_last_admin_on_user_role_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    old_is_system_admin_role BOOLEAN;
BEGIN
    SELECT (
        role.role_code = 'ADMIN'
        AND role.is_system = TRUE
    )
      INTO old_is_system_admin_role
      FROM auth.role role
     WHERE role.role_id = OLD.role_id;

    IF OLD.is_active = TRUE AND old_is_system_admin_role THEN
        IF TG_OP = 'DELETE' THEN
            PERFORM auth.assert_last_active_admin_preserved(OLD.user_id);
            RETURN OLD;
        END IF;

        IF NEW.is_active = FALSE
           OR NEW.user_id <> OLD.user_id
           OR NEW.role_id <> OLD.role_id THEN
            PERFORM auth.assert_last_active_admin_preserved(OLD.user_id);
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.protect_last_admin_on_user_role_change()
    FROM PUBLIC;

CREATE TRIGGER tr_user_role_protect_last_admin
    BEFORE UPDATE OR DELETE ON auth.user_role
    FOR EACH ROW
    EXECUTE FUNCTION auth.protect_last_admin_on_user_role_change();

-- ADMIN_SETTINGS_MANAGE는 사용자 ALLOW로 절대 만들 수 없다.
-- DENY는 허용하되 새로 유효해지는 DENY가 마지막 ADMIN을 차단하면 거부한다.
CREATE FUNCTION auth.enforce_user_permission_override()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $$
DECLARE
    evaluated_at TIMESTAMPTZ := statement_timestamp();
    old_permission_code VARCHAR(80);
    new_permission_code VARCHAR(80);
    old_is_effective_admin_deny BOOLEAN := FALSE;
    new_is_effective_admin_deny BOOLEAN := FALSE;
BEGIN
    SELECT permission_code
      INTO new_permission_code
      FROM auth.permission
     WHERE permission_id = NEW.permission_id;

    IF new_permission_code = 'ADMIN_SETTINGS_MANAGE'
       AND NEW.override_effect = 'ALLOW' THEN
        RAISE EXCEPTION 'ADMIN_SETTINGS_MANAGE user ALLOW is prohibited'
            USING ERRCODE = '42501';
    END IF;

    new_is_effective_admin_deny := (
        new_permission_code = 'ADMIN_SETTINGS_MANAGE'
        AND NEW.override_effect = 'DENY'
        AND NEW.is_active = TRUE
        AND (
            NEW.expires_at IS NULL
            OR NEW.expires_at > evaluated_at
        )
    );

    IF TG_OP = 'UPDATE' THEN
        SELECT permission_code
          INTO old_permission_code
          FROM auth.permission
         WHERE permission_id = OLD.permission_id;

        old_is_effective_admin_deny := (
            old_permission_code = 'ADMIN_SETTINGS_MANAGE'
            AND OLD.override_effect = 'DENY'
            AND OLD.is_active = TRUE
            AND (
                OLD.expires_at IS NULL
                OR OLD.expires_at > evaluated_at
            )
        );
    END IF;

    IF new_is_effective_admin_deny THEN
        IF TG_OP = 'INSERT' THEN
            PERFORM auth.assert_last_active_admin_preserved(NEW.user_id);
        ELSIF NOT old_is_effective_admin_deny
              OR NEW.user_id <> OLD.user_id THEN
            PERFORM auth.assert_last_active_admin_preserved(NEW.user_id);
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION auth.enforce_user_permission_override()
    FROM PUBLIC;

CREATE TRIGGER tr_user_permission_override_security
    BEFORE INSERT OR UPDATE ON auth.user_permission_override
    FOR EACH ROW
    EXECUTE FUNCTION auth.enforce_user_permission_override();

-- runtime 계정은 lifecycle 행을 물리 삭제하지 않고 SELECT/INSERT/UPDATE만 사용한다.
GRANT SELECT, INSERT, UPDATE
    ON auth.user_permission_override
    TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE
    ON auth.user_robot_assignment
    TO "${applicationRole}";
