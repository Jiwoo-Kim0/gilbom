package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.data.application.command.TelemetryIngestionCommand;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.telemetry.TelemetryIngestionService;
import org.springframework.stereotype.Component;

@Component
public final class TelemetryMqttMessageHandler
        extends AbstractLoggingMqttTopicMessageHandler<TelemetryPayload> {

    private final TelemetryIngestionService ingestionService;

    public TelemetryMqttMessageHandler(TelemetryIngestionService ingestionService) {
        super(MqttTopicType.TELEMETRY, TelemetryPayload.class);
        this.ingestionService = ingestionService;
    }

    @Override
    protected void handlePayload(
            TelemetryPayload payload,
            ParsedMqttMessage message
    ) {
        ingestionService.ingest(new TelemetryIngestionCommand(
                payload,
                message.rawPayload(),
                message.receivedAt()
        ));
    }
}
