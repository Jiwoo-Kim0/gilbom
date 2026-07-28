package com.gilbom.robot.mqtt.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.mqtt.v2.common.MqttV2Payload;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.IngestReceiptPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicParser;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

@Service
public class MqttV2Publisher {

    private final MqttPublisher mqttPublisher;
    private final MqttTopicParser topicParser;
    private final ObjectMapper objectMapper;

    public MqttV2Publisher(
            MqttPublisher mqttPublisher,
            MqttTopicParser topicParser,
            ObjectMapper objectMapper
    ) {
        this.mqttPublisher = mqttPublisher;
        this.topicParser = topicParser;
        this.objectMapper = objectMapper
                .copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
    /*ForCustom*/
    public PublishResult publishCommand(
            String mqttId,
            CommandRequestPayload payload
    ) {
        long expirySeconds = Math.max(
                1,
                Duration.between(
                        payload.issuedAt(),
                        payload.expiresAt()).toSeconds());
        return publish(
                MqttTopicType.COMMAND_REQUEST,
                mqttId,
                payload,
                expirySeconds);
    }

    public PublishResult publishIngestReceipt(
            String mqttId,
            IngestReceiptPayload payload
    ) {
        return publish(MqttTopicType.INGEST_RECEIPT, mqttId, payload);
    }

    
    
    
    
    
    
    
    /*For Common*/
    private PublishResult publish(
            MqttTopicType topicType,
            String mqttId,
            MqttV2Payload payload
    ) {
        return publish(topicType, mqttId, payload, null);
    }

    private PublishResult publish(
            MqttTopicType topicType,
            String mqttId,
            MqttV2Payload payload,
            Long messageExpiryIntervalSeconds
    ) {
        Objects.requireNonNull(payload, "payload must not be null");

        String topic = topicParser.format(topicType, mqttId);//토픽 제조
        String serializedPayload = serialize(payload); //직렬화(json으로)
        boolean accepted = messageExpiryIntervalSeconds == null
                ? mqttPublisher.publish(
                        topic,
                        serializedPayload,
                        topicType.qos(),
                        topicType.retained())
                : mqttPublisher.publish(
                        topic,
                        serializedPayload,
                        topicType.qos(),
                        topicType.retained(),
                        messageExpiryIntervalSeconds);
        if (!accepted) {
            throw new BusinessException(ErrorCode.MQTT_UNAVAILABLE);
        }
        return new PublishResult(
                topic,
                topicType.qos(),
                topicType.retained()
        );
    }

    private String serialize(MqttV2Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
        }
    }

    public record PublishResult(
            String topic,
            int qos,
            boolean retained
    ) {
    }
}
