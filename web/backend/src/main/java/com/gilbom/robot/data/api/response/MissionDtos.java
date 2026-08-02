package com.gilbom.robot.data.api.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class MissionDtos {

    private MissionDtos() {
    }

    public enum MissionStatus {
        CREATED,
        READY,
        RUNNING,
        PAUSED,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public enum MissionListScope {
        ACTIVE,
        TERMINAL,
        ALL
    }

    public record MissionCreateRequest(
            @NotNull UUID robotId,
            @NotNull UUID patrolAreaId,
            @NotNull UUID patrolRouteId,
            @NotNull @Positive Integer plannedDurationSec,
            Instant scheduledStartAt
    ) {
    }

    public record MissionUpdateRequest(
            UUID patrolAreaId,
            UUID patrolRouteId,
            @Positive Integer plannedDurationSec,
            Instant scheduledStartAt,
            MissionStatus status,
            @Size(max = 100) String completionReason
    ) {

        @AssertTrue(message = "at least one mission field must be provided")
        @JsonIgnore
        public boolean isPatchPresent() {
            return patrolAreaId != null
                    || patrolRouteId != null
                    || plannedDurationSec != null
                    || scheduledStartAt != null
                    || status != null
                    || completionReason != null;
        }

        @AssertTrue(message = "completionReason is required only for terminal status")
        @JsonIgnore
        public boolean isCompletionReasonValid() {
            if (status == MissionStatus.CANCELLED
                    || status == MissionStatus.FAILED) {
                return completionReason != null
                        && !completionReason.isBlank();
            }
            return completionReason == null;
        }
    }

    public record MissionResponse(
            UUID missionId,
            String missionCode,
            UUID robotId,
            String mqttId,
            String robotName,
            UUID patrolAreaId,
            String patrolAreaCode,
            String patrolAreaName,
            UUID patrolRouteId,
            String patrolRouteCode,
            String patrolRouteName,
            int patrolRouteVersion,
            int plannedDurationSec,
            Integer elapsedSec,
            Integer remainingDurationSec,
            Instant progressObservedAt,
            boolean progressStale,
            MissionStatus status,
            Instant scheduledStartAt,
            Instant startedAt,
            Instant endedAt,
            BigDecimal progressPercent,
            Instant estimatedCompletionAt,
            String completionReason,
            Integer resultDurationSec,
            Integer resultIncidentCount,
            Integer resultNormalDetectionCount,
            JsonNode resultPayload,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record MissionListResponse(
            List<MissionResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {

        public MissionListResponse {
            content = List.copyOf(content);
        }
    }

    public record MissionTrajectoryPoint(
            UUID messageId,
            Instant sampledAt,
            Instant receivedAt,
            double latitude,
            double longitude,
            Double headingDeg,
            boolean moving,
            Double speedMps
    ) {
    }

    public record MissionTrajectoryResponse(
            UUID missionId,
            List<MissionTrajectoryPoint> points
    ) {

        public MissionTrajectoryResponse {
            points = List.copyOf(points);
        }
    }

    public record MissionMapCurrentPosition(
            double latitude,
            double longitude,
            Double headingDeg,
            Boolean moving,
            Double speedMps,
            String connectivityStatus,
            Instant observedAt
    ) {
    }

    public record MissionMapResponse(
            UUID missionId,
            UUID patrolAreaId,
            JsonNode patrolAreaBoundary,
            List<MissionTrajectoryPoint> trajectory,
            MissionMapCurrentPosition currentPosition
    ) {

        public MissionMapResponse {
            trajectory = List.copyOf(trajectory);
        }
    }

    public record MissionStatusHistoryItem(
            UUID historyId,
            String transitionSource,
            MissionStatus beforeStatus,
            MissionStatus afterStatus,
            String transitionReason,
            UUID changedBy,
            String changedByUserCode,
            UUID sourceEventId,
            Instant occurredAt,
            Instant receivedAt,
            Instant createdAt
    ) {
    }

    public record MissionStatusHistoryResponse(
            UUID missionId,
            List<MissionStatusHistoryItem> history
    ) {

        public MissionStatusHistoryResponse {
            history = List.copyOf(history);
        }
    }
}
