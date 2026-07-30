package com.gilbom.robot.mqtt.client;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.mqtt.core.ClientManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Restarts a stalled Paho reconnect cycle without replacing the shared client.
 */
@Component
public class MqttReconnectWatchdog {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MqttReconnectWatchdog.class);

    private final ClientManager<
            IMqttAsyncClient,
            MqttConnectionOptions> clientManager;

    public MqttReconnectWatchdog(
            ClientManager<
                    IMqttAsyncClient,
                    MqttConnectionOptions> clientManager
    ) {
        this.clientManager = clientManager;
    }

    @Scheduled(
            initialDelayString = "${mqtt.reconnect-max-delay:5s}",
            fixedDelayString = "${mqtt.reconnect-max-delay:5s}"
    )
    void reconnectIfDisconnected() {
        if (!clientManager.isRunning() || clientManager.isConnected()) {
            return;
        }
        IMqttAsyncClient client = clientManager.getClient();
        if (client == null) {
            return;
        }
        try {
            client.reconnect();
            LOGGER.info("MQTT manual reconnect attempt started.");
        } catch (MqttException exception) {
            LOGGER.debug(
                    "MQTT reconnect attempt deferred. reasonCode={}",
                    exception.getReasonCode());
        }
    }
}
