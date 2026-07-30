package com.gilbom.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class PublicAuthRateLimitPropertiesTest {

    @Test
    void acceptsTheFixedCsrfTokenIssuancePolicy() {
        new PublicAuthRateLimitProperties().validate();
    }

    @Test
    void rejectsAWeakerCsrfTokenIssuanceLimit() {
        PublicAuthRateLimitProperties properties =
                new PublicAuthRateLimitProperties();
        properties.setCsrfTokenIssuanceMaxRequests(600);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("60 requests per minute");
    }

    @Test
    void rejectsADifferentCsrfTokenIssuanceWindow() {
        PublicAuthRateLimitProperties properties =
                new PublicAuthRateLimitProperties();
        properties.setCsrfTokenIssuanceWindow(Duration.ofMinutes(10));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("60 requests per minute");
    }
}
