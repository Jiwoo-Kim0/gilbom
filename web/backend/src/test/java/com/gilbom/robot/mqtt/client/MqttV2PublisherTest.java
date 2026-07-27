package com.gilbom.robot.mqtt.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.IngestReceiptPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttV2PublisherTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-26T05:00:00Z");

    @Test
    void publishesCommandToRobotCommandRequestTopic() {
        MqttPublisher delegate = mock(MqttPublisher.class);
        MqttV2Publisher publisher = publisher(delegate);
        CommandRequestPayload payload = commandPayload();
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(delegate.publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()
        )).thenReturn(true);

        MqttV2Publisher.PublishResult result =
                publisher.publishCommand("RBT-001", payload);

        verify(delegate).publish(
                org.mockito.ArgumentMatchers.eq(
                        "tbs/v2/robots/RBT-001/commands/request"
                ),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(false)
        );
        assertThat(payloadCaptor.getValue())
                .contains("\"schema_version\":\"2.0\"")
                .contains("\"command_id\":\"" + payload.commandId() + "\"")
                .contains("\"command_type\":\"EMERGENCY_STOP\"")
                .doesNotContain("schemaVersion")
                .doesNotContain("commandId");
        assertThat(result).isEqualTo(new MqttV2Publisher.PublishResult(
                "tbs/v2/robots/RBT-001/commands/request",
                1,
                false
        ));
    }

    @Test
    void publishesReceiptToRobotIngestReceiptTopic() {
        MqttPublisher delegate = mock(MqttPublisher.class);
        MqttV2Publisher publisher = publisher(delegate);
        IngestReceiptPayload payload = new IngestReceiptPayload(
                "2.0",
                UUID.fromString("93bd80e4-082f-4578-92b1-8ad95ad39ca3"),
                IngestReceiptPayload.Status.STORED,
                "INC-001",
                ISSUED_AT
        );
        when(delegate.publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()
        )).thenReturn(true);

        MqttV2Publisher.PublishResult result =
                publisher.publishIngestReceipt("RBT-001", payload);

        assertThat(result.topic())
                .isEqualTo("tbs/v2/robots/RBT-001/ingest/receipt");
        verify(delegate).publish(
                org.mockito.ArgumentMatchers.eq(
                        "tbs/v2/robots/RBT-001/ingest/receipt"
                ),
                org.mockito.ArgumentMatchers.contains("\"ingest_status\":\"STORED\""),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(false)
        );
    }

    @Test
    void failsWhenOutboundChannelRejectsMessage() {
        MqttPublisher delegate = mock(MqttPublisher.class);
        MqttV2Publisher publisher = publisher(delegate);
        when(delegate.publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()
        )).thenReturn(false);

        assertThatThrownBy(() ->
                publisher.publishCommand("RBT-001", commandPayload()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.MQTT_UNAVAILABLE));
    }

    private MqttV2Publisher publisher(MqttPublisher delegate) {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        return new MqttV2Publisher(
                delegate,
                new MqttTopicParser(),
                objectMapper
        );
    }

    private CommandRequestPayload commandPayload() {
        return new CommandRequestPayload(
                "2.0",
                UUID.fromString("726b72a3-e18b-49bf-a864-0a2954624c0e"),
                CommandRequestPayload.CommandType.EMERGENCY_STOP,
                ISSUED_AT,
                ISSUED_AT.plusSeconds(30),
                new CommandRequestPayload.RequestedBy(
                        CommandRequestPayload.ActorType.ADMIN,
                        "admin-001"
                ),
                new CommandRequestPayload.Parameters(
                        null,
                        null,
                        "operator requested emergency stop"
                )
        );
    }
}
