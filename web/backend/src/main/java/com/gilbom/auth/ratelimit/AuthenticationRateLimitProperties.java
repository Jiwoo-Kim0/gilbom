package com.gilbom.auth.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@ConfigurationProperties(prefix = "gilbom.security.rate-limit.authentication")
public class AuthenticationRateLimitProperties {

    private int ipMaximumRequests = 20;
    private Duration ipWindow = Duration.ofMinutes(1);
    private int passwordMaximumFailures = 5;
    private Duration passwordFailureWindow = Duration.ofMinutes(15);
    private int mfaMaximumFailures = 5;
    private Duration mfaFailureWindow = Duration.ofMinutes(15);
    private Duration temporaryLockDuration = Duration.ofMinutes(15);

    @PostConstruct
    public void validate() {
        if (ipMaximumRequests != 20
                || !Duration.ofMinutes(1).equals(ipWindow)) {
            throw new IllegalStateException(
                    "Authentication IP limit must be exactly 20 requests per minute");
        }
        if (passwordMaximumFailures != 5
                || !Duration.ofMinutes(15).equals(passwordFailureWindow)) {
            throw new IllegalStateException(
                    "Password failure limit must be exactly five per 15 minutes");
        }
        if (mfaMaximumFailures != 5
                || !Duration.ofMinutes(15).equals(mfaFailureWindow)) {
            throw new IllegalStateException(
                    "MFA failure limit must be exactly five per 15 minutes");
        }
        if (!Duration.ofMinutes(15).equals(temporaryLockDuration)) {
            throw new IllegalStateException(
                    "Authentication temporary lock must be exactly 15 minutes");
        }
    }

    public int getIpMaximumRequests() {
        return ipMaximumRequests;
    }

    public void setIpMaximumRequests(int ipMaximumRequests) {
        this.ipMaximumRequests = ipMaximumRequests;
    }

    public Duration getIpWindow() {
        return ipWindow;
    }

    public void setIpWindow(Duration ipWindow) {
        this.ipWindow = ipWindow;
    }

    public int getPasswordMaximumFailures() {
        return passwordMaximumFailures;
    }

    public void setPasswordMaximumFailures(int passwordMaximumFailures) {
        this.passwordMaximumFailures = passwordMaximumFailures;
    }

    public Duration getPasswordFailureWindow() {
        return passwordFailureWindow;
    }

    public void setPasswordFailureWindow(Duration passwordFailureWindow) {
        this.passwordFailureWindow = passwordFailureWindow;
    }

    public int getMfaMaximumFailures() {
        return mfaMaximumFailures;
    }

    public void setMfaMaximumFailures(int mfaMaximumFailures) {
        this.mfaMaximumFailures = mfaMaximumFailures;
    }

    public Duration getMfaFailureWindow() {
        return mfaFailureWindow;
    }

    public void setMfaFailureWindow(Duration mfaFailureWindow) {
        this.mfaFailureWindow = mfaFailureWindow;
    }

    public Duration getTemporaryLockDuration() {
        return temporaryLockDuration;
    }

    public void setTemporaryLockDuration(Duration temporaryLockDuration) {
        this.temporaryLockDuration = temporaryLockDuration;
    }
}
