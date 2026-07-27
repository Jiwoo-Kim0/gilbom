-- 공개 회원가입 계정을 초기 관리자 bootstrap으로 오인해 승격하지 않도록 생성 경로를 보존한다.
ALTER TABLE auth.admin_user
    ADD COLUMN account_origin VARCHAR(24);

UPDATE auth.admin_user
SET account_origin = 'MIGRATED';

ALTER TABLE auth.admin_user
    ALTER COLUMN account_origin SET DEFAULT 'PUBLIC_REGISTRATION',
    ALTER COLUMN account_origin SET NOT NULL,
    ADD CONSTRAINT ck_admin_account_origin CHECK (
        account_origin IN (
            'PUBLIC_REGISTRATION',
            'BOOTSTRAP',
            'ADMIN_CREATED',
            'MIGRATED'
        )
    );

CREATE UNIQUE INDEX uq_single_bootstrap_admin
    ON auth.admin_user (account_origin)
    WHERE account_origin = 'BOOTSTRAP';

COMMENT ON COLUMN auth.admin_user.account_origin IS
    'PUBLIC_REGISTRATION, BOOTSTRAP, ADMIN_CREATED, MIGRATED 생성 경로';
