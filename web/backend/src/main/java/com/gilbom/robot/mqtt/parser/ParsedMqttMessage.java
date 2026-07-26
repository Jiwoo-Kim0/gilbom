package com.gilbom.robot.mqtt.parser;

import com.gilbom.robot.mqtt.contract.v2.MqttV2Payload;
import com.gilbom.robot.mqtt.topic.ParsedMqttTopic;

import java.time.Instant;
import java.util.Objects;

public record ParsedMqttMessage(
        ParsedMqttTopic topic,
        MqttV2Payload payload,
        int qos,
        Instant receivedAt
) {

    public ParsedMqttMessage {
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }
}
