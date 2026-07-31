package com.gilbom.health;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.integration.mqtt.core.ClientManager;
import org.springframework.stereotype.Component;

/**
 * 로봇 기능 의존성인 MQTT 연결 상태를 전체 Backend readiness와 분리해 노출한다.
 */
@Component("mqttConnection")
public final class MqttConnectionHealthIndicator
        implements HealthIndicator {

    private final ClientManager<
            IMqttAsyncClient,
            MqttConnectionOptions> clientManager;

    public MqttConnectionHealthIndicator(
            ClientManager<
                    IMqttAsyncClient,
                    MqttConnectionOptions> clientManager
    ) {
        this.clientManager = clientManager;
    }

    @Override
    public Health health() {
        boolean running = clientManager.isRunning();
        boolean connected = running && clientManager.isConnected();
        Health.Builder builder = connected
                ? Health.up()
                : Health.down();
        return builder
                .withDetail("running", running)
                .withDetail("connected", connected)
                .withDetail("readinessPolicy", "separate")
                .build();
    }
}
