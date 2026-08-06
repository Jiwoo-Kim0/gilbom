package com.gilbom.robot.mqtt.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gilbom.defect.IncidentEventIngestionService.IncidentIngestionResult;
import com.gilbom.robot.data.mqtt.v2.outbound.IngestReceiptPayload;
import com.gilbom.robot.event.RobotEventIngestionResult;
import com.gilbom.robot.mqtt.client.MqttV2Publisher;
import com.gilbom.robot.mqtt.receipt.IncidentReceiptProjectionReader.IncidentReceiptProjection;
import com.gilbom.robot.mqtt.topic.MqttTopicType;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class IngestReceiptCoordinatorTest {

    private static final Instant RECEIVED_AT =
            Instant.parse("2026-08-06T01:02:03Z");
    private static final UUID EVENT_ID = UUID.fromString(
            "31000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID = UUID.fromString(
            "31000000-0000-4000-8000-000000000002");

    private MqttV2Publisher publisher;
    private IncidentReceiptProjectionReader incidentReader;
    private SimpleMeterRegistry meterRegistry;
    private IngestReceiptCoordinator coordinator;

    @BeforeEach
    void setUp() {
        publisher = mock(MqttV2Publisher.class);
        incidentReader = mock(IncidentReceiptProjectionReader.class);
        meterRegistry = new SimpleMeterRegistry();
        coordinator = new IngestReceiptCoordinator(
                publisher,
                incidentReader,
                meterRegistry);
    }

    @Test
    void publishesStoredReceiptForNewMissionEvent() {
        RobotEventIngestionResult result = event(true);

        coordinator.publishEvent(
                "RBT-001",
                MqttTopicType.MISSION_EVENT,
                result,
                RECEIVED_AT);

        IngestReceiptPayload payload = capturePayload("RBT-001");
        assertThat(payload.eventId()).isEqualTo(EVENT_ID);
        assertThat(payload.ingestStatus())
                .isEqualTo(IngestReceiptPayload.Status.STORED);
        assertThat(payload.incidentId()).isNull();
        assertThat(payload.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(metric("MISSION_EVENT", "sent")).isEqualTo(1.0);
    }

    @Test
    void publishesMergedIncidentWithBusinessIncidentCode() {
        RobotEventIngestionResult event = event(true);
        IncidentIngestionResult result = new IncidentIngestionResult(
                event,
                UUID.fromString(
                        "31000000-0000-4000-8000-000000000003"),
                true);
        when(incidentReader.findByEventId(EVENT_ID))
                .thenReturn(new IncidentReceiptProjection(
                        "INC-20260806-000001",
                        false));

        coordinator.publishIncident("RBT-002", result, RECEIVED_AT);

        IngestReceiptPayload payload = capturePayload("RBT-002");
        assertThat(payload.ingestStatus())
                .isEqualTo(IngestReceiptPayload.Status.MERGED);
        assertThat(payload.incidentId())
                .isEqualTo("INC-20260806-000001");
    }

    @Test
    void swallowsPublishFailureWithoutCreatingRetryWork() {
        RobotEventIngestionResult result = event(false);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher)
                .publishIngestReceipt(
                        eq("RBT-003"),
                        org.mockito.ArgumentMatchers.any());

        coordinator.publishEvent(
                "RBT-003",
                MqttTopicType.SAFETY_EVENT,
                result,
                RECEIVED_AT);

        assertThat(metric("SAFETY_EVENT", "failed")).isEqualTo(1.0);
    }

    private IngestReceiptPayload capturePayload(String mqttId) {
        ArgumentCaptor<IngestReceiptPayload> captor =
                ArgumentCaptor.forClass(IngestReceiptPayload.class);
        verify(publisher).publishIngestReceipt(eq(mqttId), captor.capture());
        return captor.getValue();
    }

    private RobotEventIngestionResult event(boolean inserted) {
        return new RobotEventIngestionResult(
                EVENT_ID,
                ROBOT_ID,
                null,
                inserted);
    }

    private double metric(String topic, String outcome) {
        return meterRegistry.counter(
                IngestReceiptCoordinator.PUBLISH_METRIC,
                "topic",
                topic,
                "outcome",
                outcome).count();
    }
}
