package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.mission.MissionEventIngestionService;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.receipt.IngestReceiptCoordinator;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class MissionEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<MissionEventPayload> {

    private final MissionEventIngestionService ingestionService;
    private final IngestReceiptCoordinator receiptCoordinator;

    public MissionEventMqttMessageHandler(
            MissionEventIngestionService ingestionService,
            IngestReceiptCoordinator receiptCoordinator
    ) {
        super(MqttTopicType.MISSION_EVENT, MissionEventPayload.class);
        this.ingestionService = ingestionService;
        this.receiptCoordinator = receiptCoordinator;
    }

    @Override
    protected void handlePayload(
            MissionEventPayload payload,
            ParsedMqttMessage message
    ) {
        var result = ingestionService.ingest(
                payload,
                message.rawPayload(),
                message.receivedAt());
        receiptCoordinator.publishEvent(
                message.topic().mqttId(),
                MqttTopicType.MISSION_EVENT,
                result,
                message.receivedAt());
    }
}
