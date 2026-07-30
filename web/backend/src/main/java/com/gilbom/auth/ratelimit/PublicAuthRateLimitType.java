package com.gilbom.auth.ratelimit;

public enum PublicAuthRateLimitType {

    CSRF_TOKEN_ISSUANCE("IP_CSRF_TOKEN_ISSUANCE"),
    EMAIL_AVAILABILITY("IP_EMAIL_AVAILABILITY_REQUEST"),
    REGISTRATION("IP_PUBLIC_REGISTRATION_REQUEST");

    private final String databaseValue;

    PublicAuthRateLimitType(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
