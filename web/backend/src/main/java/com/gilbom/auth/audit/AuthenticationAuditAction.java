package com.gilbom.auth.audit;

public enum AuthenticationAuditAction {

    LOGIN_SUCCEEDED("AUTH_LOGIN_SUCCEEDED", FailurePolicy.REQUIRED),
    LOGIN_FAILED("AUTH_LOGIN_FAILED", FailurePolicy.REQUIRED),
    PASSWORD_VERIFIED("AUTH_PASSWORD_VERIFIED", FailurePolicy.REQUIRED),
    ACCOUNT_TEMPORARILY_LOCKED(
            "AUTH_ACCOUNT_TEMPORARILY_LOCKED",
            FailurePolicy.REQUIRED
    ),
    MFA_CHALLENGE_FAILED(
            "AUTH_MFA_CHALLENGE_FAILED",
            FailurePolicy.REQUIRED
    ),
    MFA_ENROLLMENT_STARTED(
            "AUTH_MFA_ENROLLMENT_STARTED",
            FailurePolicy.REQUIRED
    ),
    MFA_ENROLLED("AUTH_MFA_ENROLLED", FailurePolicy.REQUIRED),
    RECOVERY_CODE_USED(
            "AUTH_RECOVERY_CODE_USED",
            FailurePolicy.REQUIRED
    ),
    INITIAL_PASSWORD_CHANGED(
            "AUTH_INITIAL_PASSWORD_CHANGED",
            FailurePolicy.REQUIRED
    ),
    INITIAL_PASSWORD_CHANGE_FAILED(
            "AUTH_INITIAL_PASSWORD_CHANGE_FAILED",
            FailurePolicy.REQUIRED
    ),
    PASSWORD_CHANGED("AUTH_PASSWORD_CHANGED", FailurePolicy.REQUIRED),
    PASSWORD_CHANGE_FAILED(
            "AUTH_PASSWORD_CHANGE_FAILED",
            FailurePolicy.REQUIRED
    ),
    RATE_LIMITED("AUTH_RATE_LIMITED", FailurePolicy.BEST_EFFORT),
    LOGOUT("AUTH_LOGOUT", FailurePolicy.BEST_EFFORT),
    SESSION_REVOKED("AUTH_SESSION_REVOKED", FailurePolicy.REQUIRED),
    ROLE_PERMISSIONS_CHANGED(
            "RBAC_ROLE_PERMISSIONS_CHANGED",
            FailurePolicy.REQUIRED
    ),
    USER_ROLES_CHANGED(
            "RBAC_USER_ROLES_CHANGED",
            FailurePolicy.REQUIRED
    ),
    USER_OVERRIDES_CHANGED(
            "RBAC_USER_OVERRIDES_CHANGED",
            FailurePolicy.REQUIRED
    );

    private final String databaseValue;
    private final FailurePolicy failurePolicy;

    AuthenticationAuditAction(
            String databaseValue,
            FailurePolicy failurePolicy
    ) {
        this.databaseValue = databaseValue;
        this.failurePolicy = failurePolicy;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }

    public enum FailurePolicy {
        REQUIRED,
        BEST_EFFORT
    }
}
