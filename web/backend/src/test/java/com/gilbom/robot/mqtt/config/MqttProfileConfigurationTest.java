package com.gilbom.robot.mqtt.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class MqttProfileConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "MQTT_USERNAME=gilbom",
                    "MQTT_PASSWORD=test-password"
            );

    @Test
    void localProfileUsesLocalMqttDefaults() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    MqttProperties properties = context.getBean(MqttProperties.class);

                    assertThat(properties.getBroker()).isEqualTo("ssl://localhost:8883");
                    assertThat(properties.getClientId()).isEqualTo("gilbom-local");
                    assertThat(properties.getSubscribeTopics()).containsExactly(
                            "tbs/v2/robots/+/presence",
                            "tbs/v2/robots/+/state",
                            "tbs/v2/robots/+/telemetry",
                            "tbs/v2/robots/+/events/#",
                            "tbs/v2/robots/+/commands/result"
                    );
                    assertThat(properties.getSecurity().getClientCertificate()).isEmpty();
                    assertThat(properties.getSecurity().getClientPrivateKey()).isEmpty();
                });
    }

    @Test
    void ec2ProfileUsesEc2MqttDefaults() {
        contextRunner
                .withPropertyValues("spring.profiles.active=ec2")
                .run(context -> {
                    MqttProperties properties = context.getBean(MqttProperties.class);

                    assertThat(properties.getBroker())
                            .isEqualTo("ssl://i15d101.p.ssafy.io:8883");
                    assertThat(properties.getClientId()).isEqualTo("gilbom-backend-ec2");
                    assertThat(properties.getSecurity().getClientCertificate())
                            .isEqualTo("/opt/gilbom/backend/certs/client.pem");
                    assertThat(properties.getSecurity().getClientPrivateKey())
                            .isEqualTo("/opt/gilbom/backend/certs/client-key.pem");
                });
    }

    @Test
    void localDockerProfilesUseLocalBrokerAndContainerCertificatePaths() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local,docker",
                        "MQTT_BROKER=ssl://mosquitto:8883",
                        "MQTT_CLIENT_ID=gilbom-local-docker"
                )
                .run(context -> {
                    MqttProperties properties = context.getBean(MqttProperties.class);

                    assertThat(properties.getBroker()).isEqualTo("ssl://mosquitto:8883");
                    assertThat(properties.getClientId()).isEqualTo("gilbom-local-docker");
                    assertThat(properties.getSecurity().getClientCertificate())
                            .isEqualTo("/app/config/mqtt/certs/client.pem");
                    assertThat(properties.getSecurity().getClientPrivateKey())
                            .isEqualTo("/app/config/mqtt/certs/client-key.pem");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MqttProperties.class)
    static class TestConfiguration {
    }
}
