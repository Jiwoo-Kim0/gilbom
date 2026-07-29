package com.gilbom.statistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;
import com.gilbom.statistics.StatisticsDtos.StatisticsAggregateMetrics;
import com.gilbom.statistics.StatisticsDtos.StatisticsByAreaItem;
import com.gilbom.statistics.StatisticsDtos.StatisticsByAreaResponse;
import com.gilbom.statistics.StatisticsDtos.StatisticsByDefectTypeItem;
import com.gilbom.statistics.StatisticsDtos.StatisticsByDefectTypeResponse;
import com.gilbom.statistics.StatisticsDtos.StatisticsByPeriodItem;
import com.gilbom.statistics.StatisticsDtos.StatisticsByPeriodResponse;
import com.gilbom.statistics.StatisticsDtos.StatisticsByStatusItem;
import com.gilbom.statistics.StatisticsDtos.StatisticsByStatusResponse;

@Service
public class StatisticsQueryService {

    private final StatisticsRepository repository;

    public StatisticsQueryService(StatisticsRepository repository) {
        this.repository = repository;
    }

    public StatisticsPeriod requirePeriod(Instant from, Instant to) {
        return new StatisticsPeriod(from, to);
    }

    @Transactional(readOnly = true)
    public StatisticsByStatusResponse findByStatus(
            UUID userId,
            Instant from,
            Instant to
    ) {
        StatisticsPeriod period = requirePeriod(from, to);
        Map<DefectWorkflowStatus, Long> counts =
                new EnumMap<>(DefectWorkflowStatus.class);
        repository.countDefectsByWorkflowStatus(userId, period)
                .forEach(count -> counts.put(
                        count.workflowStatus(),
                        count.defectCount()));

        List<StatisticsByStatusItem> items =
                Arrays.stream(DefectWorkflowStatus.values())
                        .map(status -> new StatisticsByStatusItem(
                                status,
                                counts.getOrDefault(status, 0L)))
                        .toList();
        long totalDefectCount = items.stream()
                .mapToLong(StatisticsByStatusItem::defectCount)
                .reduce(0L, Math::addExact);
        return new StatisticsByStatusResponse(
                period.from(),
                period.to(),
                totalDefectCount,
                items);
    }

    @Transactional(readOnly = true)
    public StatisticsByDefectTypeResponse findByDefectType(
            UUID userId,
            Instant from,
            Instant to
    ) {
        StatisticsPeriod period = requirePeriod(from, to);
        Map<DefectType, Long> counts = new EnumMap<>(DefectType.class);
        repository.countDefectsByType(userId, period)
                .forEach(count -> counts.put(
                        count.defectType(),
                        count.defectCount()));

        List<StatisticsByDefectTypeItem> items =
                Arrays.stream(DefectType.values())
                        .map(defectType -> new StatisticsByDefectTypeItem(
                                defectType,
                                counts.getOrDefault(defectType, 0L)))
                        .toList();
        long totalDefectCount = items.stream()
                .mapToLong(StatisticsByDefectTypeItem::defectCount)
                .reduce(0L, Math::addExact);
        return new StatisticsByDefectTypeResponse(
                period.from(),
                period.to(),
                totalDefectCount,
                items);
    }

    @Transactional(readOnly = true)
    public StatisticsQuery.ByArea requireAreaQuery(
            Instant from,
            Instant to,
            UUID patrolAreaId,
            int page,
            int size
    ) {
        StatisticsPeriod period = requirePeriod(from, to);
        StatisticsQuery.ByArea query = StatisticsQuery.byArea(
                period,
                patrolAreaId,
                page,
                size);

        // 입력 자체를 모두 검증한 뒤에만 DB를 조회한다.
        if (patrolAreaId != null
                && !repository.patrolAreaExists(patrolAreaId)) {
            throw new BusinessException(
                    ErrorCode.PATROL_AREA_NOT_FOUND);
        }
        return query;
    }

    @Transactional(readOnly = true)
    public StatisticsByAreaResponse findByArea(
            UUID userId,
            Instant from,
            Instant to,
            UUID patrolAreaId,
            int page,
            int size
    ) {
        StatisticsQuery.ByArea query = requireAreaQuery(
                from,
                to,
                patrolAreaId,
                page,
                size);
        long totalElements = repository.countPatrolAreas(patrolAreaId);
        List<StatisticsByAreaItem> items =
                repository.findAreaStatistics(userId, query).stream()
                        .map(StatisticsQueryService::toAreaItem)
                        .toList();
        int totalPages = Math.toIntExact(
                Math.ceilDiv(totalElements, query.page().size()));
        return new StatisticsByAreaResponse(
                query.period().from(),
                query.period().to(),
                items,
                query.page().page(),
                query.page().size(),
                totalElements,
                totalPages);
    }

    public StatisticsQuery.ByPeriod requirePeriodQuery(
            Instant from,
            Instant to,
            String granularity
    ) {
        return StatisticsQuery.byPeriod(
                requirePeriod(from, to),
                granularity);
    }

    @Transactional(readOnly = true)
    public StatisticsByPeriodResponse findByPeriod(
            UUID userId,
            Instant from,
            Instant to,
            String granularity
    ) {
        StatisticsQuery.ByPeriod query = requirePeriodQuery(
                from,
                to,
                granularity);
        List<StatisticsByPeriodItem> items =
                repository.findPeriodStatistics(userId, query).stream()
                        .map(StatisticsQueryService::toPeriodItem)
                        .toList();
        return new StatisticsByPeriodResponse(
                query.period().from(),
                query.period().to(),
                query.granularity(),
                items);
    }

    private static StatisticsByAreaItem toAreaItem(
            StatisticsRepository.AreaStatisticsRow row
    ) {
        return new StatisticsByAreaItem(
                row.patrolAreaId(),
                row.areaCode(),
                row.areaName(),
                new StatisticsAggregateMetrics(
                        row.missionCount(),
                        row.completedMissionCount(),
                        row.cancelledMissionCount(),
                        row.failedMissionCount(),
                        completionRate(
                                row.completedMissionCount(),
                                row.missionCount()),
                        row.totalMissionDurationSeconds(),
                        row.incidentEventCount(),
                        row.defectCount(),
                        row.observationCount()));
    }

    private static StatisticsByPeriodItem toPeriodItem(
            StatisticsRepository.PeriodStatisticsRow row
    ) {
        return new StatisticsByPeriodItem(
                row.date(),
                new StatisticsAggregateMetrics(
                        row.missionCount(),
                        row.completedMissionCount(),
                        row.cancelledMissionCount(),
                        row.failedMissionCount(),
                        completionRate(
                                row.completedMissionCount(),
                                row.missionCount()),
                        row.totalMissionDurationSeconds(),
                        row.incidentEventCount(),
                        row.defectCount(),
                        row.observationCount()));
    }

    private static BigDecimal completionRate(
            long completedMissionCount,
            long missionCount
    ) {
        if (missionCount == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(completedMissionCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        BigDecimal.valueOf(missionCount),
                        2,
                        RoundingMode.HALF_UP);
    }
}
