package com.gilbom.auth.ratelimit;

/**
 * 로그인과 MFA가 공통으로 사용하는 영속 Rate Limit bucket 종류다.
 */
public enum AuthenticationRateLimitType {

    IP_AUTHENTICATION_REQUEST("IP_AUTHENTICATION_REQUEST"),
    ACCOUNT_PASSWORD_FAILURE("ACCOUNT_PASSWORD_FAILURE"),
    ACCOUNT_MFA_FAILURE("ACCOUNT_MFA_FAILURE");

    private final String databaseValue;

    AuthenticationRateLimitType(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
