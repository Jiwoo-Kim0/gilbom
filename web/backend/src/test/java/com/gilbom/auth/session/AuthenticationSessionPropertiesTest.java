package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class AuthenticationSessionPropertiesTest {

    @Test
    void acceptsTheFixedSecurityPolicy() {
        AuthenticationSessionProperties properties =
                new AuthenticationSessionProperties();

        properties.validate();

        assertThat(properties.idleTimeoutSeconds()).isEqualTo(1800);
        assertThat(properties.getAbsoluteTimeout()).isEqualTo(Duration.ofHours(8));
        assertThat(properties.getChallengeTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getMaximumAuthenticatedSessions()).isEqualTo(1);
    }

    @Test
    void rejectsSecurityWeakeningConfigurationDrift() {
        AuthenticationSessionProperties properties =
                new AuthenticationSessionProperties();
        properties.setIdleTimeout(Duration.ofHours(1));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Session idle timeout must be exactly 30 minutes");
    }
}
