package com.gilbom.robot.mqtt.topic;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class MqttTopicParser {

    private static final String NAMESPACE = "tbs";
    private static final String ROBOTS_SEGMENT = "robots";
    private static final String VERSION_SEGMENT = "v2";
    private static final Pattern ROBOT_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public ParsedMqttTopic parse(String topic) {
        if (topic == null || topic.isBlank()) {
            throw invalidTopic("MQTT topic must not be blank");
        }

        String[] segments = topic.split("/", -1);
        if (segments.length < 5
                || !NAMESPACE.equals(segments[0])
                || !ROBOTS_SEGMENT.equals(segments[2])) {
            throw invalidTopic("MQTT topic must match tbs/v2/robots/{robot_id}/...");
        }
        if (!VERSION_SEGMENT.equals(segments[1])) {
            throw new BusinessException(ErrorCode.MQTT_SCHEMA_VERSION_UNSUPPORTED);
        }

        String robotId = segments[3];
        if (!ROBOT_ID_PATTERN.matcher(robotId).matches()) {
            throw invalidTopic("MQTT topic contains an invalid robot_id");
        }

        String relativePath = String.join(
                "/",
                Arrays.copyOfRange(segments, 4, segments.length)
        );
        return new ParsedMqttTopic(
                ParsedMqttTopic.SUPPORTED_VERSION,
                robotId,
                MqttTopicType.fromRelativePath(relativePath)
        );
    }

    public String format(MqttTopicType type, String robotId) {
        Objects.requireNonNull(type, "type must not be null");
        if (robotId == null || !ROBOT_ID_PATTERN.matcher(robotId).matches()) {
            throw invalidTopic("Cannot format topic with an invalid robot_id");
        }
        return "%s/%s/%s/%s/%s".formatted(
                NAMESPACE,
                VERSION_SEGMENT,
                ROBOTS_SEGMENT,
                robotId,
                type.relativePath()
        );
    }

    private BusinessException invalidTopic(String message) {
        return new BusinessException(ErrorCode.MQTT_TOPIC_INVALID, message);
    }
}
