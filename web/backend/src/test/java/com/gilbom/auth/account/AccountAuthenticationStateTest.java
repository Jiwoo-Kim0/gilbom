package com.gilbom.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class AccountAuthenticationStateTest {

    private static final Instant LAST_FAILURE = Instant.parse("2026-07-27T00:00:00Z");
    private static final Instant LOCK_EXPIRY = Instant.parse("2026-07-27T00:15:00Z");

    @Test
    void onlyActiveAndCurrentlyUnlockedAccountsCanAttemptAuthentication() {
        AccountAuthenticationState active =
                new AccountAuthenticationState(AccountStatus.ACTIVE, 0, null, null);
        AccountAuthenticationState locked =
                new AccountAuthenticationState(
                        AccountStatus.ACTIVE,
                        5,
                        LAST_FAILURE,
                        LOCK_EXPIRY
                );

        assertThat(active.canAttemptAuthenticationAt(LAST_FAILURE)).isTrue();
        assertThat(locked.canAttemptAuthenticationAt(LAST_FAILURE)).isFalse();
        assertThat(locked.canAttemptAuthenticationAt(LOCK_EXPIRY)).isTrue();

        for (AccountStatus status : AccountStatus.values()) {
            if (status != AccountStatus.ACTIVE) {
                assertThat(new AccountAuthenticationState(status, 0, null, null)
                        .canAttemptAuthenticationAt(LAST_FAILURE)).isFalse();
            }
        }
    }

    @Test
    void rejectsStatesThatWouldDisagreeWithDatabaseConstraints() {
        assertThatThrownBy(() ->
                new AccountAuthenticationState(AccountStatus.ACTIVE, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AccountAuthenticationState(AccountStatus.ACTIVE, 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AccountAuthenticationState(
                        AccountStatus.PENDING_APPROVAL,
                        1,
                        LAST_FAILURE,
                        null
                ))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AccountAuthenticationState(
                        AccountStatus.ACTIVE,
                        5,
                        LAST_FAILURE,
                        LAST_FAILURE
                ))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
