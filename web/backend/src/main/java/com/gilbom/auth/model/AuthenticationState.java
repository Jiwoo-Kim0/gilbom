package com.gilbom.auth.model;

/**
 * 브라우저 세션의 인증 진행 상태다.
 *
 * 비밀번호 검증 성공은 인증 완료가 아니며 AUTHENTICATED만 보호 API에 접근할 수 있다.
 */
public enum AuthenticationState {
    ANONYMOUS,
    PASSWORD_CHANGE_REQUIRED,
    MFA_ENROLLMENT_REQUIRED,
    MFA_REQUIRED,
    AUTHENTICATED;

    public boolean isFullyAuthenticated() {
        return this == AUTHENTICATED;
    }
}
