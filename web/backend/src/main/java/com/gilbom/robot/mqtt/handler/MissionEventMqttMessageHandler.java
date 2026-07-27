package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class MissionEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<MissionEventPayload> {

    public MissionEventMqttMessageHandler() {
        super(MqttTopicType.MISSION_EVENT, MissionEventPayload.class);
    }
}
