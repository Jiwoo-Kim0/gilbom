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
            String robotId,
            CommandRequestPayload payload
    ) {
        return publish(MqttTopicType.COMMAND_REQUEST, robotId, payload);
    }

    public PublishResult publishIngestReceipt(
            String robotId,
            IngestReceiptPayload payload
    ) {
        return publish(MqttTopicType.INGEST_RECEIPT, robotId, payload);
    }

    
    
    
    
    
    
    
    /*For Common*/
    private PublishResult publish(
            MqttTopicType topicType,
            String robotId,
            MqttV2Payload payload
    ) {
        Objects.requireNonNull(payload, "payload must not be null");

        String topic = topicParser.format(topicType, robotId);//토픽 제조
        String serializedPayload = serialize(payload); //직렬화(json으로)
        boolean accepted = mqttPublisher.publish(
                topic,
                serializedPayload,
                topicType.qos(),
                topicType.retained()
        );
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
