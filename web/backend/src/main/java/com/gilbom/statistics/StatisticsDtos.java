package com.gilbom.statistics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * {@code docs/openapi-v1.yaml}의 통계 응답 DTO 계약이다.
 */
public final class StatisticsDtos {

    private StatisticsDtos() {
    }

    public enum DefectWorkflowStatus {
        NEW,
        ACCEPTED,
        IN_PROGRESS,
        RESOLVED,
        FALSE_POSITIVE,
        REJECTED
    }

    public enum DefectType {
        DAMAGED,
        CONTAMINATED,
        UPLIFTED,
        BLOCKED,
        MISSING_SUSPECTED,
        INSTALLATION_INVALID,
        UNKNOWN_ANOMALY
    }

    public enum StatisticsGranularity {
        DAY;

        public static StatisticsGranularity from(String rawGranularity) {
            if (rawGranularity == null) {
                return DAY;
            }
            try {
                return valueOf(rawGranularity);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(
                        ErrorCode.COMMON_INVALID_REQUEST,
                        "지원하지 않는 통계 집계 단위입니다.");
            }
        }

        /**
         * SQL fragment에 사용할 수 있는 값은 enum switch의 상수만 반환한다.
         */
        String sqlDateTruncUnit() {
            return switch (this) {
                case DAY -> "day";
            };
        }
    }

    public record StatisticsAggregateMetrics(
            long missionCount,
            long completedMissionCount,
            long cancelledMissionCount,
            long failedMissionCount,
            BigDecimal completionRatePercent,
            long totalMissionDurationSeconds,
            long incidentEventCount,
            long defectCount,
            long observationCount
    ) {
    }

    public record StatisticsByStatusItem(
            DefectWorkflowStatus workflowStatus,
            long defectCount
    ) {
    }

    public record StatisticsByStatusResponse(
            Instant from,
            Instant to,
            long totalDefectCount,
            List<StatisticsByStatusItem> items
    ) {
        public StatisticsByStatusResponse {
            items = List.copyOf(items);
        }
    }

    public record StatisticsByDefectTypeItem(
            DefectType defectType,
            long defectCount
    ) {
    }

    public record StatisticsByDefectTypeResponse(
            Instant from,
            Instant to,
            long totalDefectCount,
            List<StatisticsByDefectTypeItem> items
    ) {
        public StatisticsByDefectTypeResponse {
            items = List.copyOf(items);
        }
    }

    public record StatisticsByAreaItem(
            UUID patrolAreaId,
            String areaCode,
            String areaName,
            StatisticsAggregateMetrics metrics
    ) {
    }

    public record StatisticsByAreaResponse(
            Instant from,
            Instant to,
            List<StatisticsByAreaItem> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        public StatisticsByAreaResponse {
            items = List.copyOf(items);
        }
    }

    public record StatisticsByPeriodItem(
            LocalDate date,
            StatisticsAggregateMetrics metrics
    ) {
    }

    public record StatisticsByPeriodResponse(
            Instant from,
            Instant to,
            StatisticsGranularity granularity,
            List<StatisticsByPeriodItem> items
    ) {
        public StatisticsByPeriodResponse {
            items = List.copyOf(items);
        }
    }
}
