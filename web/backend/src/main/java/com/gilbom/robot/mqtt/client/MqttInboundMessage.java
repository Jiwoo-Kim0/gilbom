package com.gilbom.robot.mqtt.client;

import java.time.Instant;

public record MqttInboundMessage(
        String topic,
        String payload,
        int qos,
        Instant receivedAt
) {
}
