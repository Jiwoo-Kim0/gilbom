-- 가입 요청 거절은 계정 비활성화·삭제와 의미가 다르므로 별도 수명주기 상태로 보존한다.
ALTER TABLE auth.admin_user
    DROP CONSTRAINT ck_admin_account_status,
    DROP CONSTRAINT ck_admin_account_lifecycle;

ALTER TABLE auth.admin_user
    ADD CONSTRAINT ck_admin_account_status CHECK (
        account_status IN (
            'PENDING_APPROVAL',
            'ACTIVE',
            'DISABLED',
            'REJECTED',
            'DELETED'
        )
    ),
    ADD CONSTRAINT ck_admin_account_lifecycle CHECK (
        (
            account_status = 'PENDING_APPROVAL'
            AND is_active = FALSE
            AND deleted_at IS NULL
        )
        OR
        (
            account_status = 'ACTIVE'
            AND is_active = TRUE
            AND deleted_at IS NULL
        )
        OR
        (
            account_status IN ('DISABLED', 'REJECTED')
            AND is_active = FALSE
            AND deleted_at IS NULL
        )
        OR
        (
            account_status = 'DELETED'
            AND is_active = FALSE
            AND deleted_at IS NOT NULL
        )
    );

COMMENT ON COLUMN auth.admin_user.account_status IS
    'PENDING_APPROVAL, ACTIVE, DISABLED, REJECTED, DELETED 계정 생명주기';
