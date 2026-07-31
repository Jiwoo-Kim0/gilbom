package com.gilbom.robot.mqtt.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.DirectFieldAccessor;

class BoundedMqttv5ClientManagerTest {

    @Test
    void boundsBothQuiesceAndDisconnectBeforeClosingClient()
            throws Exception {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setServerURIs(new String[]{"ssl://localhost:8883"});
        BoundedMqttv5ClientManager manager =
                new BoundedMqttv5ClientManager(options, "shutdown-test");
        manager.setDisconnectCompletionTimeout(3_000L);
        IMqttAsyncClient client = mock(IMqttAsyncClient.class);
        new DirectFieldAccessor(manager).setPropertyValue("client", client);

        manager.stop();

        verify(client).disconnectForcibly(3_000L, 3_000L, false);
        verify(client).close(true);
        assertThat(manager.getClient()).isNull();
    }
}
