package com.gilbom.robot.mqtt.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.mqtt.client.MqttInboundMessage;
import com.gilbom.robot.data.mqtt.v2.common.MqttV2Payload;
import com.gilbom.robot.data.mqtt.v2.common.RobotIdentifiedPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.BatteryEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.CommandResultPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.PresencePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.SafetyEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.StatePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.IngestReceiptPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicParser;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.mqtt.topic.ParsedMqttTopic;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MQTT 원문을 Topic별 v2 계약 객체로 변환하고 공통 계약을 검증한다.
 */
@Component
public class MqttMessageParser {

    private final MqttTopicParser topicParser;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public MqttMessageParser(
            MqttTopicParser topicParser,
            ObjectMapper objectMapper,
            Validator validator
    ) {
        this.topicParser = topicParser;
        this.objectMapper = objectMapper
                .copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    public ParsedMqttMessage parse(MqttInboundMessage inboundMessage) {
        Objects.requireNonNull(inboundMessage, "inboundMessage must not be null");

        ParsedMqttTopic topic = topicParser.parse(inboundMessage.topic());
        validateQos(topic.type(), inboundMessage.qos());
        MqttV2Payload payload = deserialize(topic.type(), inboundMessage.payload());
        validateSchemaVersion(payload);
        validatePayload(payload);
        validateMqttId(topic, payload);

        return new ParsedMqttMessage(
                topic,
                payload,
                inboundMessage.payload(),
                inboundMessage.qos(),
                inboundMessage.receivedAt()
        );
    }

    private MqttV2Payload deserialize(MqttTopicType topicType, String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new BusinessException(ErrorCode.MQTT_PAYLOAD_MALFORMED);
        }
        try {
            return objectMapper.readValue(rawPayload, payloadType(topicType));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.MQTT_PAYLOAD_MALFORMED);
        }
    }

    private Class<? extends MqttV2Payload> payloadType(MqttTopicType topicType) {
        return switch (topicType) {
            case PRESENCE -> PresencePayload.class;
            case STATE -> StatePayload.class;
            case TELEMETRY -> TelemetryPayload.class;
            case INCIDENT_EVENT -> IncidentEventPayload.class;
            case BATTERY_EVENT -> BatteryEventPayload.class;
            case SAFETY_EVENT -> SafetyEventPayload.class;
            case MISSION_EVENT -> MissionEventPayload.class;
            case COMMAND_RESULT -> CommandResultPayload.class;
            case COMMAND_REQUEST -> CommandRequestPayload.class;
            case INGEST_RECEIPT -> IngestReceiptPayload.class;
        };
    }

    private void validatePayload(MqttV2Payload payload) {
        Set<ConstraintViolation<MqttV2Payload>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .sorted(Comparator.comparing(
                            violation -> violation.getPropertyPath().toString()
                    ))
                    .map(violation -> "%s: %s".formatted(
                            violation.getPropertyPath(),
                            violation.getMessage()
                    ))
                    .collect(Collectors.joining(", "));
            throw new BusinessException(
                    ErrorCode.MQTT_PAYLOAD_VALIDATION_FAILED,
                    "MQTT v2 payload validation failed: " + details
            );
        }
    }

    private void validateSchemaVersion(MqttV2Payload payload) {
        if (payload.schemaVersion() != null
                && !MqttV2Payload.SCHEMA_VERSION.equals(payload.schemaVersion())) {
            throw new BusinessException(ErrorCode.MQTT_SCHEMA_VERSION_UNSUPPORTED);
        }
    }

    private void validateMqttId(ParsedMqttTopic topic, MqttV2Payload payload) {
        if (payload instanceof RobotIdentifiedPayload identified
                && !topic.mqttId().equals(identified.mqttId())) {
            throw new BusinessException(ErrorCode.MQTT_ID_MISMATCH);
        }
    }

    private void validateQos(MqttTopicType topicType, int actualQos) {
        if (actualQos != topicType.qos()) {
            throw new BusinessException(ErrorCode.MQTT_QOS_MISMATCH);
        }
    }
}
