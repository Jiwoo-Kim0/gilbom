-- 외부 컨설턴트 검증용 계정 한 개만 MFA 예외로 허용한다.
ALTER TABLE auth.admin_user
    ADD COLUMN mfa_required BOOLEAN DEFAULT TRUE NOT NULL,
    ADD CONSTRAINT ck_admin_single_consultant_mfa_exemption CHECK (
        mfa_required = TRUE
        OR (
            user_id = '39000000-0000-4000-8000-000000000001'
            AND normalized_email = 'consultant.manager@gilbom.test'
            AND password_change_required = FALSE
        )
    );

CREATE UNIQUE INDEX uq_single_mfa_exempt_account
    ON auth.admin_user ((mfa_required))
    WHERE mfa_required = FALSE;

COMMENT ON COLUMN auth.admin_user.mfa_required IS
    'TRUE이면 비밀번호 검증 뒤 TOTP 등록 또는 검증을 강제한다. FALSE는 고정 컨설턴트 테스트 계정 한 개만 허용한다.';

INSERT INTO auth.admin_user (
    user_id,
    user_code,
    password_hash,
    display_name,
    email,
    account_status,
    is_active,
    password_change_required,
    password_changed_at,
    account_origin,
    mfa_required
) VALUES (
    '39000000-0000-4000-8000-000000000001',
    'CONSULTANT-MANAGER',
    '{argon2}$argon2id$v=19$m=19456,t=2,p=1$YbFCFgLyItF63QVu1wpBOQ$95Rh3d92ngGeMWg48I1JEwkLEzqEzv1IYmvDbsZA1Vg',
    '컨설턴트 매니저',
    'consultant.manager@gilbom.test',
    'ACTIVE',
    TRUE,
    FALSE,
    statement_timestamp(),
    'ADMIN_CREATED',
    FALSE
);

INSERT INTO auth.user_role (
    user_id,
    role_id,
    is_primary,
    is_active,
    assigned_by
) VALUES (
    '39000000-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-000000000002',
    TRUE,
    TRUE,
    NULL
);
