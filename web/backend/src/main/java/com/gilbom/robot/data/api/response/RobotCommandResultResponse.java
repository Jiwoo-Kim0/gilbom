package com.gilbom.robot.data.api.response;

import java.time.Instant;
import java.util.UUID;

public record RobotCommandResultResponse(
        UUID resultId,
        UUID commandId,
        UUID robotId,
        String resultStatus,
        Instant robotReceivedAt,
        Instant robotCompletedAt,
        String reasonCode,
        String resultingMode,
        String resultingReasonCode,
        Instant serverReceivedAt
) {
}
