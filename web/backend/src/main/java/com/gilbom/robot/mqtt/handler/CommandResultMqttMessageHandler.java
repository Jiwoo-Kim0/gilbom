package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.CommandResultPayload;
import com.gilbom.robot.command.RobotCommandResultIngestionService;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class CommandResultMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<CommandResultPayload> {

    private final RobotCommandResultIngestionService ingestionService;

    public CommandResultMqttMessageHandler(
            RobotCommandResultIngestionService ingestionService
    ) {
        super(MqttTopicType.COMMAND_RESULT, CommandResultPayload.class);
        this.ingestionService = ingestionService;
    }

    @Override
    protected void handlePayload(
            CommandResultPayload payload,
            ParsedMqttMessage message
    ) {
        ingestionService.ingest(
                payload,
                message.rawPayload(),
                message.receivedAt());
    }
}
