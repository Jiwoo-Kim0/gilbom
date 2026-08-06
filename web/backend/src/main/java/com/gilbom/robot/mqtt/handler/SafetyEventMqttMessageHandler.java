package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.SafetyEventPayload;
import com.gilbom.robot.event.RobotEventIngestionService;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.receipt.IngestReceiptCoordinator;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class SafetyEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<SafetyEventPayload> {

    private final RobotEventIngestionService ingestionService;
    private final IngestReceiptCoordinator receiptCoordinator;

    public SafetyEventMqttMessageHandler(
            RobotEventIngestionService ingestionService,
            IngestReceiptCoordinator receiptCoordinator
    ) {
        super(MqttTopicType.SAFETY_EVENT, SafetyEventPayload.class);
        this.ingestionService = ingestionService;
        this.receiptCoordinator = receiptCoordinator;
    }

    @Override
    protected void handlePayload(
            SafetyEventPayload payload,
            ParsedMqttMessage message
    ) {
        var result = ingestionService.ingest(
                payload,
                payload.eventType(),
                message.rawPayload(),
                message.receivedAt());
        receiptCoordinator.publishEvent(
                message.topic().mqttId(),
                MqttTopicType.SAFETY_EVENT,
                result,
                message.receivedAt());
    }
}
