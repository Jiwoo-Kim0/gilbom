package com.gilbom.robot.data.mqtt.v2.common;

import java.time.Instant;
import java.util.UUID;

public interface MqttEventPayload extends RobotIdentifiedPayload {

    UUID eventId();

    UUID bootId();

    Long seq();

    EventSource sourceComponent();

    Instant occurredAt();

    Instant sentAt();

    Integer ttlSec();

    String missionId();
}
