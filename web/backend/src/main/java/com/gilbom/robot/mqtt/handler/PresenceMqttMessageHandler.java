package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.PresencePayload;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class PresenceMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<PresencePayload> {

    public PresenceMqttMessageHandler() {
        super(MqttTopicType.PRESENCE, PresencePayload.class);
    }
}
