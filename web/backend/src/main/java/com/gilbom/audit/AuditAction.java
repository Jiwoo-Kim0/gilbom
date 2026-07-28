package com.gilbom.audit;

public enum AuditAction {

    INITIAL_ADMIN_CREATED("AUTH_INITIAL_ADMIN_CREATED"),
    PUBLIC_REGISTRATION_CREATED("AUTH_PUBLIC_REGISTRATION_CREATED"),
    ACCESS_DENIED("AUTH_ACCESS_DENIED"),
    CSRF_FAILED("AUTH_CSRF_FAILED"),
    ROBOT_REGISTERED("ROBOT_REGISTERED"),
    ROBOT_CREDENTIAL_ISSUED("ROBOT_CREDENTIAL_ISSUED"),
    ROBOT_COMMAND_ISSUED("ROBOT_COMMAND_ISSUED"),
    AUDIT_LOG_VIEWED("AUDIT_LOG_VIEWED");

    private final String databaseValue;

    AuditAction(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
