package com.gilbom.robot.mqtt.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.Test;

import com.gilbom.robot.mqtt.factory.MqttSecurityStrategyFactory;
import com.gilbom.robot.mqtt.strategy.MqttSecurityStrategy;

class MqttConnectionConfigTest {

    @Test
    void limitsAutomaticReconnectBackoff() throws Exception {
        MqttProperties properties = new MqttProperties();
        properties.setNowMqttSecurityStrategy("tls");
        properties.setBroker("ssl://localhost:8883");
        properties.setClientId("mqtt-config-test");
        properties.setUsername("backend");
        properties.setPassword("secret");
        properties.setReconnectMaxDelay(Duration.ofSeconds(5));

        MqttSecurityStrategy strategy = new MqttSecurityStrategy() {
            @Override
            public String mode() {
                return "tls";
            }

            @Override
            public SSLContext createSslContext() {
                try {
                    return SSLContext.getDefault();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
        MqttSecurityStrategyFactory factory =
                new MqttSecurityStrategyFactory(
                        properties,
                        List.of(strategy));

        MqttConnectionOptions options = new MqttConnectionConfig()
                .mqttConnectionOptions(properties, factory);

        assertThat(options.isAutomaticReconnect()).isTrue();
        assertThat(options.getMaxReconnectDelay()).isEqualTo(5_000);
    }
}
