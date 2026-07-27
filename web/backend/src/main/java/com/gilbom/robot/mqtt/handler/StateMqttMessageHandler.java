package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.mqtt.contract.v2.StatePayload;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class StateMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<StatePayload> {

    public StateMqttMessageHandler() {
        super(MqttTopicType.STATE, StatePayload.class);
    }
}
