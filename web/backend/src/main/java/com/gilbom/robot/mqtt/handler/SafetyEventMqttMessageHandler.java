package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.SafetyEventPayload;
import com.gilbom.robot.event.RobotEventIngestionService;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class SafetyEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<SafetyEventPayload> {

    private final RobotEventIngestionService ingestionService;

    public SafetyEventMqttMessageHandler(
            RobotEventIngestionService ingestionService
    ) {
        super(MqttTopicType.SAFETY_EVENT, SafetyEventPayload.class);
        this.ingestionService = ingestionService;
    }

    @Override
    protected void handlePayload(
            SafetyEventPayload payload,
            ParsedMqttMessage message
    ) {
        ingestionService.ingest(
                payload,
                payload.eventType(),
                message.rawPayload(),
                message.receivedAt());
    }
}
