package com.gilbom.auth.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gilbom.security.rate-limit.public-auth")
public class PublicAuthRateLimitProperties {

    private int emailAvailabilityMaxRequests = 20;
    private Duration emailAvailabilityWindow = Duration.ofMinutes(1);
    private int registrationMaxRequests = 5;
    private Duration registrationWindow = Duration.ofHours(1);
    private Duration cleanupInterval = Duration.ofMinutes(10);
    private int cleanupBatchSize = 1_000;

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
