package com.gilbom.robot.mqtt.handler;

import com.gilbom.defect.IncidentEventIngestionService;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.receipt.IngestReceiptCoordinator;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class IncidentEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<IncidentEventPayload> {

    private final IncidentEventIngestionService ingestionService;
    private final IngestReceiptCoordinator receiptCoordinator;

    public IncidentEventMqttMessageHandler(
            IncidentEventIngestionService ingestionService,
            IngestReceiptCoordinator receiptCoordinator
    ) {
        super(MqttTopicType.INCIDENT_EVENT, IncidentEventPayload.class);
        this.ingestionService = ingestionService;
        this.receiptCoordinator = receiptCoordinator;
    }

    @Override
    protected void handlePayload(
            IncidentEventPayload payload,
            ParsedMqttMessage message
    ) {
        var result = ingestionService.ingest(
                payload,
                message.rawPayload(),
                message.receivedAt());
        receiptCoordinator.publishIncident(
                message.topic().mqttId(),
                result,
                message.receivedAt());
    }
}
