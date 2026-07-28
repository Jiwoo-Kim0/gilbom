package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.UUID;

import com.gilbom.robot.data.api.request.RobotCommandCreateRequest;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;

public record RobotCommandResponse(
        UUID commandId,
        UUID robotId,
        String mqttId,
        CommandRequestPayload.CommandType commandType,
        String commandStatus,
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
