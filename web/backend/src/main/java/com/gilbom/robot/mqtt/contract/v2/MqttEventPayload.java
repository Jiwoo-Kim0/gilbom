package com.gilbom.robot.mqtt.contract.v2;

import java.time.Instant;
import java.util.UUID;

public interface MqttEventPayload extends RobotIdentifiedPayload {

    UUID eventId();

    UUID bootId();

    Long seq();

    EventSource sourceComponent();

    Instant occurredAt();

    Instant sentAt();

    String missionId();
}
