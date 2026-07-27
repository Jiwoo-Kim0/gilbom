package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.mqtt.contract.v2.CommandResultPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class CommandResultMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<CommandResultPayload> {

    public CommandResultMqttMessageHandler() {
        super(MqttTopicType.COMMAND_RESULT, CommandResultPayload.class);
    }
}
