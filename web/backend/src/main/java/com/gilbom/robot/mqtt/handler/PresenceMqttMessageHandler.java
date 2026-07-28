package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.application.command.PresenceIngestionCommand;
import com.gilbom.robot.data.mqtt.v2.inbound.PresencePayload;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.status.RobotStatusIngestionService;
import org.springframework.stereotype.Component;

@Component
public final class PresenceMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<PresencePayload> {

    private final RobotStatusIngestionService ingestionService;

    public PresenceMqttMessageHandler(
            RobotStatusIngestionService ingestionService
    ) {
        super(MqttTopicType.PRESENCE, PresencePayload.class);
        this.ingestionService = ingestionService;
    }

    @Override
    protected void handlePayload(
            PresencePayload payload,
            ParsedMqttMessage message
    ) {
        ingestionService.ingestPresence(new PresenceIngestionCommand(
                payload,
                message.rawPayload(),
                message.receivedAt()
        ));
    }
}
