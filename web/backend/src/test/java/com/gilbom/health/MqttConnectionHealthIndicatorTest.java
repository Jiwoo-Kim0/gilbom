package com.gilbom.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.integration.mqtt.core.ClientManager;

class MqttConnectionHealthIndicatorTest {

    @Test
    void reportsUpOnlyWhenTheManagerIsRunningAndConnected() {
        ClientManager<IMqttAsyncClient, MqttConnectionOptions> manager =
                manager();
        when(manager.isRunning()).thenReturn(true);
        when(manager.isConnected()).thenReturn(true);

        var health = new MqttConnectionHealthIndicator(manager).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("running", true)
                .containsEntry("connected", true)
                .containsEntry("readinessPolicy", "separate");
    }

    @Test
    void reportsDownWhenTheBrokerConnectionIsUnavailable() {
        ClientManager<IMqttAsyncClient, MqttConnectionOptions> manager =
                manager();
        when(manager.isRunning()).thenReturn(true);
        when(manager.isConnected()).thenReturn(false);

        var health = new MqttConnectionHealthIndicator(manager).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("running", true)
                .containsEntry("connected", false);
    }

    @SuppressWarnings("unchecked")
    private static ClientManager<
            IMqttAsyncClient,
            MqttConnectionOptions> manager() {
        return mock(ClientManager.class);
    }
}
