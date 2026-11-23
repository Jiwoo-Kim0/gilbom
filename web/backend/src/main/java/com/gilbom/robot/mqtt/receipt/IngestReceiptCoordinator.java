package com.gilbom.robot.mqtt.receipt;

import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gilbom.defect.IncidentEventIngestionService.IncidentIngestionResult;
import com.gilbom.robot.data.mqtt.v2.outbound.IngestReceiptPayload;
import com.gilbom.robot.event.RobotEventIngestionResult;
import com.gilbom.robot.mqtt.client.MqttV2Publisher;
import com.gilbom.robot.mqtt.receipt.IncidentReceiptProjectionReader.IncidentReceiptProjection;
import com.gilbom.robot.mqtt.topic.MqttTopicType;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class IngestReceiptCoordinator {

    static final String PUBLISH_METRIC =
            "gilbom.mqtt.ingest.receipt.publish";

    private static final Logger LOG = LoggerFactory.getLogger(
            IngestReceiptCoordinator.class);

    private final MqttV2Publisher publisher;
    private final IncidentReceiptProjectionReader incidentReader;
    private final MeterRegistry meterRegistry;

    public IngestReceiptCoordinator(
            MqttV2Publisher publisher,
            IncidentReceiptProjectionReader incidentReader,
            MeterRegistry meterRegistry
    ) {
        this.publisher = Objects.requireNonNull(publisher);
        this.incidentReader = Objects.requireNonNull(incidentReader);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    public void publishEvent(
            String mqttId,
            MqttTopicType topicType,
            RobotEventIngestionResult result,
            Instant receivedAt
    ) {
        IngestReceiptPayload.Status status = result.inserted()
                ? IngestReceiptPayload.Status.STORED
                : IngestReceiptPayload.Status.DUPLICATE;
        publishBestEffort(
                mqttId,
                topicType,
                result,
                status,
                null,
                receivedAt);
    }

    public void publishIncident(
            String mqttId,
            IncidentIngestionResult result,
            Instant receivedAt
    ) {
        RobotEventIngestionResult event = result.event();
        try {
            IncidentReceiptProjection projection =
                    incidentReader.findByEventId(event.eventId());
            IngestReceiptPayload.Status status;
            if (!event.inserted()) {
                status = IngestReceiptPayload.Status.DUPLICATE;
            } else if (projection != null && !projection.representative()) {
                status = IngestReceiptPayload.Status.MERGED;
            } else {
                status = IngestReceiptPayload.Status.STORED;
            }
            publishOnce(
                    mqttId,
                    MqttTopicType.INCIDENT_EVENT,
                    event,
                    status,
                    projection == null ? null : projection.incidentId(),
                    receivedAt);
        } catch (RuntimeException exception) {
            recordFailure(
                    MqttTopicType.INCIDENT_EVENT,
                    mqttId,
                    event,
                    exception);
        }
    }

    private void publishBestEffort(
            String mqttId,
            MqttTopicType topicType,
            RobotEventIngestionResult result,
            IngestReceiptPayload.Status status,
            String incidentId,
            Instant receivedAt
    ) {
        try {
            publishOnce(
                    mqttId,
                    topicType,
                    result,
                    status,
                    incidentId,
                    receivedAt);
        } catch (RuntimeException exception) {
            recordFailure(
                    topicType,
                    mqttId,
                    result,
                    exception);
        }
    }

    private void publishOnce(
            String mqttId,
            MqttTopicType topicType,
            RobotEventIngestionResult result,
            IngestReceiptPayload.Status status,
            String incidentId,
            Instant receivedAt
    ) {
        publisher.publishIngestReceipt(
                mqttId,
                new IngestReceiptPayload(
                        "2.0",
                        result.eventId(),
                        status,
                        incidentId,
                        receivedAt));
        meterRegistry.counter(
                PUBLISH_METRIC,
                "topic",
                topicType.name(),
                "outcome",
                "sent").increment();
    }

    private void recordFailure(
            MqttTopicType topicType,
            String mqttId,
            RobotEventIngestionResult result,
            RuntimeException exception
    ) {
        meterRegistry.counter(
                PUBLISH_METRIC,
                "topic",
                topicType.name(),
                "outcome",
                "failed").increment();
        LOG.warn(
                "Best-effort ingest receipt publish failed. "
                        + "topicType={} mqttId={} eventId={}",
                topicType,
                mqttId,
                result.eventId(),
                exception);
    }
}
