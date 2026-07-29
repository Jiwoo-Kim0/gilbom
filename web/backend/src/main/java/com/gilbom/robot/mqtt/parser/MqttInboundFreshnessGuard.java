package com.gilbom.robot.mqtt.parser;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.mqtt.v2.common.MqttEventPayload;
import com.gilbom.robot.data.mqtt.v2.common.MqttV2Payload;
import com.gilbom.robot.data.mqtt.v2.inbound.CommandResultPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.StatePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.mqtt.config.MqttInboundFreshnessProperties;
import com.gilbom.robot.mqtt.topic.MqttTopicType;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class MqttInboundFreshnessGuard {

    static final String REJECTION_METRIC =
            "gilbom.mqtt.inbound.rejected";

    private final Duration telemetryMaxDelay;
    private final Duration stateMaxDelay;
    private final Duration commandResultMaxDelay;
    private final Duration futureTolerance;
    private final MeterRegistry meterRegistry;

    public MqttInboundFreshnessGuard(
            MqttInboundFreshnessProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.telemetryMaxDelay = requirePositive(
                properties.getTelemetryMaxDelay(),
                "telemetryMaxDelay");
        this.stateMaxDelay = requirePositive(
                properties.getStateMaxDelay(),
                "stateMaxDelay");
        this.commandResultMaxDelay = requirePositive(
                properties.getCommandResultMaxDelay(),
                "commandResultMaxDelay");
        this.futureTolerance = requireNotNegative(
                properties.getFutureTolerance(),
                "futureTolerance");
        this.meterRegistry = meterRegistry;
    }

    public void validate(
            MqttTopicType topicType,
            MqttV2Payload payload,
            Instant serverReceivedAt
    ) {
        Objects.requireNonNull(topicType, "topicType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(
                serverReceivedAt,
                "serverReceivedAt must not be null");

        FreshnessRule rule = rule(payload);
        if (rule == null) {
            return;
        }

        Duration age = Duration.between(
                rule.referenceTime(),
                serverReceivedAt);
        if (age.compareTo(futureTolerance.negated()) < 0) {
            reject(
                    topicType,
                    "future_timestamp",
                    ErrorCode.MQTT_TIMESTAMP_IN_FUTURE);
        }
        if (age.compareTo(rule.maxDelay()) >= 0) {
            reject(
                    topicType,
                    "expired",
                    ErrorCode.MQTT_MESSAGE_EXPIRED);
        }
    }

    public void validateExpiry(
            MqttTopicType topicType,
            Instant expiresAt,
            Instant serverReceivedAt
    ) {
        Objects.requireNonNull(topicType, "topicType must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(
                serverReceivedAt,
                "serverReceivedAt must not be null");
        if (!serverReceivedAt.isBefore(expiresAt)) {
            reject(
                    topicType,
                    "expired",
                    ErrorCode.MQTT_MESSAGE_EXPIRED);
        }
    }

    private FreshnessRule rule(MqttV2Payload payload) {
        if (payload instanceof TelemetryPayload telemetry) {
            return new FreshnessRule(
                    telemetry.sampledAt(),
                    telemetryMaxDelay);
        }
        if (payload instanceof StatePayload state) {
            return new FreshnessRule(
                    state.updatedAt(),
                    stateMaxDelay);
        }
        if (payload instanceof MqttEventPayload event) {
            return new FreshnessRule(
                    event.occurredAt(),
                    Duration.ofSeconds(event.ttlSec()));
        }
        if (payload instanceof CommandResultPayload result) {
            Instant resultTime = result.completedAt() == null
                    ? result.receivedAt()
                    : result.completedAt();
            return new FreshnessRule(
                    resultTime,
                    commandResultMaxDelay);
        }
        return null;
    }

    private void reject(
            MqttTopicType topicType,
            String reason,
            ErrorCode errorCode
    ) {
        meterRegistry.counter(
                REJECTION_METRIC,
                "topic",
                topicType.name(),
                "reason",
                reason).increment();
        throw new BusinessException(errorCode);
    }

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive");
        }
        return value;
    }

    private static Duration requireNotNegative(
            Duration value,
            String name
    ) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must not be negative");
        }
        return value;
    }

    private record FreshnessRule(
            Instant referenceTime,
            Duration maxDelay
    ) {
    }
}
