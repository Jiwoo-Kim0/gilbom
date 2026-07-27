package com.gilbom.robot.mqtt.topic;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import java.util.Arrays;

/**
 * final-mqtt-topic-schema-v2.md에 정의된 Topic 목록과 전송 속성이다.
 */
public enum MqttTopicType {

    PRESENCE("presence", MqttTopicDirection.ROBOT_TO_SERVER, 1, true),
    STATE("state", MqttTopicDirection.ROBOT_TO_SERVER, 1, true),
    TELEMETRY("telemetry", MqttTopicDirection.ROBOT_TO_SERVER, 0, false),
    INCIDENT_EVENT("events/incident", MqttTopicDirection.ROBOT_TO_SERVER, 1, false),
    BATTERY_EVENT("events/battery", MqttTopicDirection.ROBOT_TO_SERVER, 1, false),
    SAFETY_EVENT("events/safety", MqttTopicDirection.ROBOT_TO_SERVER, 1, false),
    MISSION_EVENT("events/mission", MqttTopicDirection.ROBOT_TO_SERVER, 1, false),
    COMMAND_RESULT("comamnds/result", MqttTopicDirection.ROBOT_TO_SERVER, 1, false),
    COMMAND_REQUEST("commands/request", MqttTopicDirection.SERVER_TO_ROBOT, 1, false),
    INGEST_RECEIPT("ingest/receipt", MqttTopicDirection.SERVER_TO_ROBOT, 1, false);

    private final String relativePath;
    private final MqttTopicDirection direction;
    private final int qos;
    private final boolean retained;

    MqttTopicType(
            String relativePath,
            MqttTopicDirection direction,
            int qos,
            boolean retained
    ) {
        this.relativePath = relativePath;
        this.direction = direction;
        this.qos = qos;
        this.retained = retained;
    }

    public String relativePath() {
        return relativePath;
    }

    public MqttTopicDirection direction() {
        return direction;
    }

    public int qos() {
        return qos;
    }

    public boolean retained() {
        return retained;
    }

    public static MqttTopicType fromRelativePath(String relativePath) {
        return Arrays.stream(values())
                .filter(type -> type.relativePath.equals(relativePath))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MQTT_TOPIC_UNSUPPORTED
                ));
    }
}
