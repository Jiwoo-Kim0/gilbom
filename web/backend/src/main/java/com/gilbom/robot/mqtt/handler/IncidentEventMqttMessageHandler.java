package com.gilbom.robot.mqtt.handler;

import com.gilbom.defect.IncidentEventIngestionService;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class IncidentEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<IncidentEventPayload> {

    private final IncidentEventIngestionService ingestionService;

    public IncidentEventMqttMessageHandler(
            IncidentEventIngestionService ingestionService
    ) {
        super(MqttTopicType.INCIDENT_EVENT, IncidentEventPayload.class);
        this.ingestionService = ingestionService;
    }

    @Override
    protected void handlePayload(
            IncidentEventPayload payload,
            ParsedMqttMessage message
    ) {
        ingestionService.ingest(
                payload,
                message.rawPayload(),
                message.receivedAt());
    }
}
