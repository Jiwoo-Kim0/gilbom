package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.SafetyEventPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class SafetyEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<SafetyEventPayload> {

    public SafetyEventMqttMessageHandler() {
        super(MqttTopicType.SAFETY_EVENT, SafetyEventPayload.class);
    }
}
