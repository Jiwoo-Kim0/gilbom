package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.application.command.StateIngestionCommand;
import com.gilbom.robot.data.mqtt.v2.inbound.StatePayload;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.status.RobotStatusIngestionService;
import org.springframework.stereotype.Component;

@Component
public final class StateMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<StatePayload> {

    private final RobotStatusIngestionService ingestionService;

    public StateMqttMessageHandler(
            RobotStatusIngestionService ingestionService
    ) {
        super(MqttTopicType.STATE, StatePayload.class);
        this.ingestionService = ingestionService;
    }

    @Override
    protected void handlePayload(
            StatePayload payload,
            ParsedMqttMessage message
    ) {
        ingestionService.ingestState(new StateIngestionCommand(
                payload,
                message.rawPayload(),
                message.receivedAt()
        ));
    }
}
