package com.gilbom.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

class HealthPolicyConfigurationTest {

    @Test
    void keepsLlmAndMqttOutOfDefaultReadiness()
            throws IOException {
        PropertySource<?> properties =
                new YamlPropertySourceLoader()
                        .load(
                                "application",
                                new ClassPathResource(
                                        "application.yml"))
                        .getFirst();

        assertThat(properties.getProperty(
                "management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
        assertThat(properties.getProperty(
                "management.endpoint.health.group.readiness.include"))
                .isEqualTo(
                        "${GILBOM_HEALTH_READINESS_COMPONENTS:"
                                + "readinessState,db,flywayReadiness,"
                                + "storageReadiness}");
        assertThat(properties.getProperty(
                "management.endpoint.health.group.mqtt.include"))
                .isEqualTo("mqttConnection");
    }
}
