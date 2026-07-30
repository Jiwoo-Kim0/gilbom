package com.gilbom.auth.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@ConfigurationProperties(prefix = "gilbom.security.rate-limit.public-auth")
public class PublicAuthRateLimitProperties {

    private int csrfTokenIssuanceMaxRequests = 60;
    private Duration csrfTokenIssuanceWindow = Duration.ofMinutes(1);
    private int emailAvailabilityMaxRequests = 20;
    private Duration emailAvailabilityWindow = Duration.ofMinutes(1);
    private int registrationMaxRequests = 5;
    private Duration registrationWindow = Duration.ofHours(1);
    private Duration cleanupInterval = Duration.ofMinutes(10);
    private int cleanupBatchSize = 1_000;

    @PostConstruct
    public void validate() {
        // 이 값은 새 익명 JDBC 세션 생성량의 보안 상한이다. 환경 설정 실수로
        // 사실상 무제한이 되지 않도록 인증 Rate Limit과 동일하게 고정한다.
        if (csrfTokenIssuanceMaxRequests != 60
                || !Duration.ofMinutes(1).equals(csrfTokenIssuanceWindow)) {
            throw new IllegalStateException(
                    "CSRF token issuance limit must be exactly 60 requests per minute");
        }
    }

    public int getCsrfTokenIssuanceMaxRequests() {
        return csrfTokenIssuanceMaxRequests;
    }

    public void setCsrfTokenIssuanceMaxRequests(int value) {
        this.csrfTokenIssuanceMaxRequests = value;
    }

    public Duration getCsrfTokenIssuanceWindow() {
        return csrfTokenIssuanceWindow;
    }

    public void setCsrfTokenIssuanceWindow(Duration value) {
        this.csrfTokenIssuanceWindow = value;
    }

    public int getEmailAvailabilityMaxRequests() {
        return emailAvailabilityMaxRequests;
    }

    public void setEmailAvailabilityMaxRequests(int value) {
        this.emailAvailabilityMaxRequests = value;
    }

    public Duration getEmailAvailabilityWindow() {
        return emailAvailabilityWindow;
    }

    public void setEmailAvailabilityWindow(Duration value) {
        this.emailAvailabilityWindow = value;
    }

    public int getRegistrationMaxRequests() {
        return registrationMaxRequests;
    }

    public void setRegistrationMaxRequests(int value) {
        this.registrationMaxRequests = value;
    }

    public Duration getRegistrationWindow() {
        return registrationWindow;
    }

    public void setRegistrationWindow(Duration value) {
        this.registrationWindow = value;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration value) {
        this.cleanupInterval = value;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int value) {
        this.cleanupBatchSize = value;
    }
}
