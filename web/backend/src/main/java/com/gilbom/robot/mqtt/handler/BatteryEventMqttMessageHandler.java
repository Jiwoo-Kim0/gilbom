package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.BatteryEventPayload;
import com.gilbom.robot.event.RobotEventIngestionService;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.receipt.IngestReceiptCoordinator;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class BatteryEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<BatteryEventPayload> {

    private final RobotEventIngestionService ingestionService;
    private final IngestReceiptCoordinator receiptCoordinator;

    public BatteryEventMqttMessageHandler(
            RobotEventIngestionService ingestionService,
            IngestReceiptCoordinator receiptCoordinator
    ) {
        super(MqttTopicType.BATTERY_EVENT, BatteryEventPayload.class);
        this.ingestionService = ingestionService;
        this.receiptCoordinator = receiptCoordinator;
    }

    @Override
    protected void handlePayload(
            BatteryEventPayload payload,
            ParsedMqttMessage message
    ) {
        var result = ingestionService.ingest(
                payload,
                payload.eventType(),
                message.rawPayload(),
                message.receivedAt());
        receiptCoordinator.publishEvent(
                message.topic().mqttId(),
                MqttTopicType.BATTERY_EVENT,
                result,
                message.receivedAt());
    }
}
