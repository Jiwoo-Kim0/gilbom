package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.mqtt.contract.v2.MqttV2Payload;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

abstract class AbstractLoggingMqttTopicMessageHandler<T extends MqttV2Payload>
        implements MqttTopicMessageHandler<T> {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final MqttTopicType topicType;
    private final Class<T> payloadType;

    protected AbstractLoggingMqttTopicMessageHandler(
            MqttTopicType topicType,
            Class<T> payloadType
    ) {
        this.topicType = Objects.requireNonNull(topicType, "topicType must not be null");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType must not be null");
    }

    @Override
    public final MqttTopicType topicType() {
        return topicType;
    }

    @Override
    public final Class<T> payloadType() {
        return payloadType;
    }

    @Override
    public final void handle(ParsedMqttMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (message.topic().type() != topicType) {
            throw new IllegalArgumentException(
                    "Handler for %s cannot process %s"
                            .formatted(topicType, message.topic().type())
            );
        }

        T payload = payloadType.cast(message.payload());
        log.info(
                "MQTT message routed to handler. type={}, robotId={}, qos={}, "
                        + "receivedAt={}, payloadType={}",
                topicType,
                message.topic().robotId(),
                message.qos(),
                message.receivedAt(),
                payloadType.getSimpleName()
        );
        handlePayload(payload, message);
    }

    protected void handlePayload(T payload, ParsedMqttMessage message) {
        // Topic-specific business processing is added here after its contract is finalized.
    }
}
