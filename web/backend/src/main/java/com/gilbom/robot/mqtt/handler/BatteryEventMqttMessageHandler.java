package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.mqtt.contract.v2.BatteryEventPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class BatteryEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<BatteryEventPayload> {

    public BatteryEventMqttMessageHandler() {
        super(MqttTopicType.BATTERY_EVENT, BatteryEventPayload.class);
    }
}
