package com.gilbom.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class AuthenticationRateLimitPropertiesTest {

    @Test
    void acceptsTheFixedAuthenticationDefensePolicy() {
        new AuthenticationRateLimitProperties().validate();
    }

    @Test
    void rejectsAWeakerIpLimit() {
        AuthenticationRateLimitProperties properties =
                new AuthenticationRateLimitProperties();
        properties.setIpMaximumRequests(100);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAShorterTemporaryLock() {
        AuthenticationRateLimitProperties properties =
                new AuthenticationRateLimitProperties();
        properties.setTemporaryLockDuration(Duration.ofMinutes(1));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class);
    }
}
