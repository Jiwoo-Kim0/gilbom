package com.gilbom.auth.session;

/**
 * 서버가 의도적으로 Spring Session 행을 삭제하는 원인.
 *
 * <p>값은 V44 DB trigger의 allowlist와 같은 이름을 사용한다. 세션 ID나 사용자 ID 같은
 * 식별자는 원인 컨텍스트에 포함하지 않는다.
 */
public enum SessionDeletionReason {
    EXPIRED_CLEANUP,
    CONCURRENT_LOGIN,
    LOGOUT,
    AUTHORIZATION_CHANGE,
    ENFORCEMENT_REJECT,
    CHALLENGE_FAILURE,
    PASSWORD_CHANGE,
    MFA_RESET,
    APPLICATION_REQUEST
}
