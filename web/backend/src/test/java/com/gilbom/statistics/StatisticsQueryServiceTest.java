package com.gilbom.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;
import com.gilbom.statistics.StatisticsDtos.StatisticsGranularity;

@ExtendWith(MockitoExtension.class)
class StatisticsQueryServiceTest {

    private static final Instant FROM =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO =
            Instant.parse("2026-07-05T00:00:00Z");
    private static final UUID PATROL_AREA_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Mock
    private StatisticsRepository repository;

    private StatisticsQueryService service;

    @BeforeEach
    void setUp() {
        service = new StatisticsQueryService(repository);
    }

    @Test
    void acceptsExistingPatrolAreaAfterPureInputValidation() {
        when(repository.patrolAreaExists(PATROL_AREA_ID))
                .thenReturn(true);

        StatisticsQuery.ByArea query = service.requireAreaQuery(
                FROM,
                TO,
                PATROL_AREA_ID,
                0,
                20);

        assertThat(query.period())
                .isEqualTo(new StatisticsPeriod(FROM, TO));
        assertThat(query.patrolAreaId()).isEqualTo(PATROL_AREA_ID);
        assertThat(query.page().offset()).isZero();
        verify(repository).patrolAreaExists(PATROL_AREA_ID);
    }

    @Test
    void allowsUnfilteredAreaQueryWithoutExistenceLookup() {
        StatisticsQuery.ByArea query = service.requireAreaQuery(
                FROM,
                TO,
                null,
                0,
                20);

        assertThat(query.patrolAreaId()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnknownPatrolAreaWithDomainError() {
        when(repository.patrolAreaExists(PATROL_AREA_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> service.requireAreaQuery(
                FROM,
                TO,
                PATROL_AREA_ID,
                0,
                20))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PATROL_AREA_NOT_FOUND);
    }

    @Test
    void doesNotQueryDatabaseWhenPeriodOrPageIsInvalid() {
        assertThatThrownBy(() -> service.requireAreaQuery(
                TO,
                FROM,
                PATROL_AREA_ID,
                0,
                20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireAreaQuery(
                FROM,
                TO,
                PATROL_AREA_ID,
                -1,
                20))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void validatesPeriodGranularityInTheCommonService() {
        StatisticsQuery.ByPeriod query =
                service.requirePeriodQuery(FROM, TO, "DAY");

        assertThat(query.granularity())
                .isEqualTo(StatisticsGranularity.DAY);
        assertThatThrownBy(
                () -> service.requirePeriodQuery(FROM, TO, "MONTH"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
        verifyNoInteractions(repository);
    }

    @Test
    void zeroFillsAndOrdersWorkflowStatusCountsByPublicEnum() {
        when(repository.countDefectsByWorkflowStatus(
                eq(PATROL_AREA_ID),
                any(StatisticsPeriod.class)))
                .thenReturn(List.of(
                        new StatisticsRepository.WorkflowStatusCount(
                                DefectWorkflowStatus.IN_PROGRESS,
                                1),
                        new StatisticsRepository.WorkflowStatusCount(
                                DefectWorkflowStatus.NEW,
                                2)));

        var response = service.findByStatus(
                PATROL_AREA_ID,
                FROM,
                TO);

        assertThat(response.totalDefectCount()).isEqualTo(3);
        assertThat(response.items())
                .extracting(
                        item -> item.workflowStatus().name(),
                        item -> item.defectCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("NEW", 2L),
                        org.assertj.core.groups.Tuple.tuple("ACCEPTED", 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                "IN_PROGRESS", 1L),
                        org.assertj.core.groups.Tuple.tuple("RESOLVED", 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                "FALSE_POSITIVE", 0L),
                        org.assertj.core.groups.Tuple.tuple("REJECTED", 0L));
    }

    @Test
    void returnsEveryWorkflowStatusWhenRepositoryIsEmpty() {
        when(repository.countDefectsByWorkflowStatus(
                eq(PATROL_AREA_ID),
                any(StatisticsPeriod.class)))
                .thenReturn(List.of());

        var response = service.findByStatus(
                PATROL_AREA_ID,
                FROM,
                TO);

        assertThat(response.totalDefectCount()).isZero();
        assertThat(response.items())
                .extracting(item -> item.workflowStatus())
                .containsExactly(DefectWorkflowStatus.values());
        assertThat(response.items())
                .allMatch(item -> item.defectCount() == 0);
    }

    @Test
    void rejectsInvalidStatusPeriodBeforeRepositoryQuery() {
        assertThatThrownBy(() -> service.findByStatus(
                PATROL_AREA_ID,
                TO,
                FROM))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.STATISTICS_PERIOD_INVALID);

        verifyNoInteractions(repository);
    }

    @Test
    void zeroFillsAndOrdersDefectTypeCountsByPublicEnum() {
        when(repository.countDefectsByType(
                eq(PATROL_AREA_ID),
                any(StatisticsPeriod.class)))
                .thenReturn(List.of(
                        new StatisticsRepository.DefectTypeCount(
                                DefectType.BLOCKED,
                                1),
                        new StatisticsRepository.DefectTypeCount(
                                DefectType.DAMAGED,
                                2)));

        var response = service.findByDefectType(
                PATROL_AREA_ID,
                FROM,
                TO);

        assertThat(response.totalDefectCount()).isEqualTo(3);
        assertThat(response.items())
                .extracting(
                        item -> item.defectType(),
                        item -> item.defectCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.DAMAGED, 2L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.CONTAMINATED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.UPLIFTED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.BLOCKED, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.MISSING_SUSPECTED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.INSTALLATION_INVALID, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.UNKNOWN_ANOMALY, 0L));
    }

    @Test
    void returnsEveryDefectTypeWhenRepositoryIsEmpty() {
        when(repository.countDefectsByType(
                eq(PATROL_AREA_ID),
                any(StatisticsPeriod.class)))
                .thenReturn(List.of());

        var response = service.findByDefectType(
                PATROL_AREA_ID,
                FROM,
                TO);

        assertThat(response.totalDefectCount()).isZero();
        assertThat(response.items())
                .extracting(item -> item.defectType())
                .containsExactly(DefectType.values());
        assertThat(response.items())
                .allMatch(item -> item.defectCount() == 0);
    }

    @Test
    void rejectsInvalidDefectTypePeriodBeforeRepositoryQuery() {
        assertThatThrownBy(() -> service.findByDefectType(
                PATROL_AREA_ID,
                TO,
                FROM))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.STATISTICS_PERIOD_INVALID);

        verifyNoInteractions(repository);
    }

    @Test
    void buildsAreaPageWithoutCombiningRawSourceRows() {
        when(repository.countPatrolAreas(null)).thenReturn(2L);
        when(repository.findAreaStatistics(
                eq(PATROL_AREA_ID),
                any(StatisticsQuery.ByArea.class)))
                .thenReturn(List.of(
                        areaRow(
                                "30000000-0000-4000-8000-000000000001",
                                "A-11",
                                2, 1, 1, 0,
                                5_400, 3, 2, 3),
                        areaRow(
                                "30000000-0000-4000-8000-000000000002",
                                "B-22",
                                2, 1, 0, 1,
                                6_600, 3, 2, 3)));

        var response = service.findByArea(
                PATROL_AREA_ID,
                FROM,
                TO,
                null,
                0,
                20);

        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.items())
                .extracting(item -> item.areaCode())
                .containsExactly("A-11", "B-22");
        assertThat(response.items().getFirst().metrics())
                .satisfies(metrics -> {
                    assertThat(metrics.missionCount()).isEqualTo(2);
                    assertThat(metrics.completionRatePercent())
                            .isEqualByComparingTo("50.00");
                    assertThat(metrics.incidentEventCount()).isEqualTo(3);
                    assertThat(metrics.defectCount()).isEqualTo(2);
                    assertThat(metrics.observationCount()).isEqualTo(3);
                });
    }

    @Test
    void returnsExistingAreaWithZeroMetricsWhenNoSourceIsVisible() {
        when(repository.patrolAreaExists(PATROL_AREA_ID))
                .thenReturn(true);
        when(repository.countPatrolAreas(PATROL_AREA_ID))
                .thenReturn(1L);
        when(repository.findAreaStatistics(
                eq(PATROL_AREA_ID),
                any(StatisticsQuery.ByArea.class)))
                .thenReturn(List.of(areaRow(
                        PATROL_AREA_ID.toString(),
                        "A-01",
                        0, 0, 0, 0,
                        0, 0, 0, 0)));

        var response = service.findByArea(
                PATROL_AREA_ID,
                FROM,
                TO,
                PATROL_AREA_ID,
                0,
                20);

        assertThat(response.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.patrolAreaId())
                            .isEqualTo(PATROL_AREA_ID);
                    assertThat(item.metrics().missionCount()).isZero();
                    assertThat(item.metrics().completionRatePercent())
                            .isEqualByComparingTo("0.00");
                    assertThat(item.metrics().incidentEventCount()).isZero();
                    assertThat(item.metrics().defectCount()).isZero();
                    assertThat(item.metrics().observationCount()).isZero();
                });
    }

    private static StatisticsRepository.AreaStatisticsRow areaRow(
            String patrolAreaId,
            String areaCode,
            long missionCount,
            long completedMissionCount,
            long cancelledMissionCount,
            long failedMissionCount,
            long totalMissionDurationSeconds,
            long incidentEventCount,
            long defectCount,
            long observationCount
    ) {
        return new StatisticsRepository.AreaStatisticsRow(
                UUID.fromString(patrolAreaId),
                areaCode,
                "Fixture " + areaCode,
                missionCount,
                completedMissionCount,
                cancelledMissionCount,
                failedMissionCount,
                totalMissionDurationSeconds,
                incidentEventCount,
                defectCount,
                observationCount);
    }
}
