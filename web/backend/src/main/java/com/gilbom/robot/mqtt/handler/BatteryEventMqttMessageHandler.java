package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.BatteryEventPayload;
import com.gilbom.robot.event.RobotEventIngestionService;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.springframework.stereotype.Component;

@Component
public final class BatteryEventMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<BatteryEventPayload> {

    private final RobotEventIngestionService ingestionService;

    public BatteryEventMqttMessageHandler(
            RobotEventIngestionService ingestionService
    ) {
        super(MqttTopicType.BATTERY_EVENT, BatteryEventPayload.class);
        this.ingestionService = ingestionService;
    }

    @Override
    protected void handlePayload(
            BatteryEventPayload payload,
            ParsedMqttMessage message
    ) {
        ingestionService.ingest(
                payload,
                payload.eventType(),
                message.rawPayload(),
                message.receivedAt());
    }
}
