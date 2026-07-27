package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.mqtt.contract.v2.IncidentEventPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class IncidentEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<IncidentEventPayload> {

    public IncidentEventMqttMessageHandler() {
        super(MqttTopicType.INCIDENT_EVENT, IncidentEventPayload.class);
    }
}
