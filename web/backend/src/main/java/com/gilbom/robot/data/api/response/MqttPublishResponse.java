package com.gilbom.robot.data.api.response;

public record MqttPublishResponse(
        String topic,
        int qos,
        boolean retained,
        boolean accepted
) {
}
