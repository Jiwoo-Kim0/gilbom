package com.gilbom.robot.command;

import java.time.Instant;
import java.util.UUID;

import com.gilbom.robot.data.api.request.RobotCommandCreateRequest;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;

public record RobotCommandRecord(
        UUID commandId,
        UUID robotId,
        String mqttId,
        CommandRequestPayload.CommandType commandType,
        RobotCommandStatus commandStatus,
        RobotCommandCreateRequest.Parameters parameters,
        String requestedByType,
        UUID requestedByUserId,
        String requestedByUserCode,
        Instant issuedAt,
        Instant expiresAt,
        Instant sentAt,
        Instant acceptedAt,
        Instant completedAt,
        int publishAttemptCount,
        String lastErrorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
