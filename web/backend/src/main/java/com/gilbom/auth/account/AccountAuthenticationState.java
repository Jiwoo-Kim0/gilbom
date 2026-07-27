package com.gilbom.auth.account;

import java.time.Instant;
import java.util.Objects;

/**
 * 계정 생명주기와 연속 로그인 실패·임시 잠금을 함께 판단하는 값 객체다.
 */
public record AccountAuthenticationState(
        AccountStatus accountStatus,
        int failedLoginCount,
        Instant lastFailedLoginAt,
        Instant lockedUntil
) {

    public AccountAuthenticationState {
        Objects.requireNonNull(accountStatus, "accountStatus must not be null");
        if (failedLoginCount < 0) {
            throw new IllegalArgumentException("failedLoginCount must not be negative");
        }
        if (failedLoginCount == 0
                && (lastFailedLoginAt != null || lockedUntil != null)) {
            throw new IllegalArgumentException(
                    "zero failures cannot retain failure or lock timestamps");
        }
        if (failedLoginCount > 0 && lastFailedLoginAt == null) {
            throw new IllegalArgumentException(
                    "positive failures require lastFailedLoginAt");
        }
        if (!accountStatus.isAuthenticationEligible()
                && (failedLoginCount != 0
                || lastFailedLoginAt != null
                || lockedUntil != null)) {
            throw new IllegalArgumentException(
                    "ineligible accounts cannot retain active login failure state");
        }
        if (lockedUntil != null && !lockedUntil.isAfter(lastFailedLoginAt)) {
            throw new IllegalArgumentException(
                    "lockedUntil must be after lastFailedLoginAt");
        }
    }

    public boolean isLockedAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean canAttemptAuthenticationAt(Instant now) {
        return accountStatus.isAuthenticationEligible() && !isLockedAt(now);
    }
}
