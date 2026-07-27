package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.mqtt.contract.v2.TelemetryPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class TelemetryMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<TelemetryPayload> {

    public TelemetryMqttMessageHandler() {
        super(MqttTopicType.TELEMETRY, TelemetryPayload.class);
    }
}
