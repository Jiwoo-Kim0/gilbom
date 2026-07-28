package com.gilbom.robot.mqtt.topic;

import java.util.Objects;

public record ParsedMqttTopic(
        int version,
        String mqttId,
        MqttTopicType type
) {

    public static final int SUPPORTED_VERSION = 2;

    public ParsedMqttTopic {
        if (version != SUPPORTED_VERSION) {
            throw new IllegalArgumentException("Only MQTT namespace v2 is supported");
        }
        if (mqttId == null || mqttId.isBlank()) {
            throw new IllegalArgumentException("mqttId must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
    }
}
