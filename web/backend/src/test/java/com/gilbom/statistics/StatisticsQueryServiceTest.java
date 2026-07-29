package com.gilbom.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
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
}
