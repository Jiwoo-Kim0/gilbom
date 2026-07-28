package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.mission.MissionEventIngestionService;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class MissionEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<MissionEventPayload> {

    private final MissionEventIngestionService ingestionService;

    public MissionEventMqttMessageHandler(
            MissionEventIngestionService ingestionService
    ) {
        super(MqttTopicType.MISSION_EVENT, MissionEventPayload.class);
        this.ingestionService = ingestionService;
    }

    @Override
    protected void handlePayload(
            MissionEventPayload payload,
            ParsedMqttMessage message
    ) {
        ingestionService.ingest(
                payload,
                message.rawPayload(),
                message.receivedAt());
    }
}
