package com.gilbom.robot.mqtt.client;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.mqtt.core.ClientManager;

@ExtendWith(MockitoExtension.class)
class MqttReconnectWatchdogTest {

    @Mock
    private ClientManager<
            IMqttAsyncClient,
            MqttConnectionOptions> clientManager;

    @Mock
    private IMqttAsyncClient client;

    @Test
    void restartsReconnectCycleWhenManagedClientIsDisconnected()
            throws Exception {
        when(clientManager.isRunning()).thenReturn(true);
        when(clientManager.isConnected()).thenReturn(false);
        when(clientManager.getClient()).thenReturn(client);

        new MqttReconnectWatchdog(clientManager)
                .reconnectIfDisconnected();

        verify(client).reconnect();
    }

    @Test
    void leavesConnectedClientAlone() throws Exception {
        when(clientManager.isRunning()).thenReturn(true);
        when(clientManager.isConnected()).thenReturn(true);

        new MqttReconnectWatchdog(clientManager)
                .reconnectIfDisconnected();

        verify(client, never()).reconnect();
    }
}
