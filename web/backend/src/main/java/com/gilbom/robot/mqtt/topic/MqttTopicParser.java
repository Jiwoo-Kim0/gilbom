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
    private static final Pattern MQTT_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public ParsedMqttTopic parse(String topic) {
        if (topic == null || topic.isBlank()) {
            throw invalidTopic("MQTT topic must not be blank");
        }

        String[] segments = topic.split("/", -1);
        if (segments.length < 5
                || !NAMESPACE.equals(segments[0])
                || !ROBOTS_SEGMENT.equals(segments[2])) {
            throw invalidTopic("MQTT topic must match tbs/v2/robots/{mqtt_id}/...");
        }
        if (!VERSION_SEGMENT.equals(segments[1])) {
            throw new BusinessException(ErrorCode.MQTT_SCHEMA_VERSION_UNSUPPORTED);
        }

        String mqttId = segments[3];
        if (!MQTT_ID_PATTERN.matcher(mqttId).matches()) {
            throw invalidTopic("MQTT topic contains an invalid mqtt_id");
        }

        String relativePath = String.join(
                "/",
                Arrays.copyOfRange(segments, 4, segments.length)
        );
        return new ParsedMqttTopic(
                ParsedMqttTopic.SUPPORTED_VERSION,
                mqttId,
                MqttTopicType.fromRelativePath(relativePath)
        );
    }

    public String format(MqttTopicType type, String mqttId) {
        Objects.requireNonNull(type, "type must not be null");
        if (mqttId == null || !MQTT_ID_PATTERN.matcher(mqttId).matches()) {
            throw invalidTopic("Cannot format topic with an invalid mqtt_id");
        }
        return "%s/%s/%s/%s/%s".formatted(
                NAMESPACE,
                VERSION_SEGMENT,
                ROBOTS_SEGMENT,
                mqttId,
                type.relativePath()
        );
    }

    private BusinessException invalidTopic(String message) {
        return new BusinessException(ErrorCode.MQTT_TOPIC_INVALID, message);
    }
}
