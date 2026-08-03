package com.gilbom.auth.model;

/**
 * 인증 상태를 변경할 수 있는 서버 검증 완료 사건이다.
 */
public enum AuthenticationEvent {
    PASSWORD_VERIFIED_CHANGE_REQUIRED,
    PASSWORD_VERIFIED_MFA_ENROLLMENT_REQUIRED,
    PASSWORD_VERIFIED_MFA_REQUIRED,
    INITIAL_PASSWORD_CHANGED,
    MFA_VERIFIED,
    MFA_BYPASS_CONFIRMED,
    MFA_RESET,
    INVALIDATED
}
