package com.gilbom.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.statistics.StatisticsDtos.StatisticsGranularity;

class StatisticsQueryTest {

    private static final StatisticsPeriod PERIOD =
            new StatisticsPeriod(
                    Instant.parse("2026-07-01T00:00:00Z"),
                    Instant.parse("2026-07-02T00:00:00Z"));

    @Test
    void calculatesPageOffsetWithoutIntOverflow() {
        StatisticsQuery.Page page = StatisticsQuery.page(
                Integer.MAX_VALUE,
                StatisticsQuery.MAXIMUM_SIZE);

        assertThat(page.offset())
                .isEqualTo(Math.multiplyExact(
                        (long) Integer.MAX_VALUE,
                        StatisticsQuery.MAXIMUM_SIZE));
    }

    @Test
    void rejectsInvalidPageAndSize() {
        assertInvalidRequest(() -> StatisticsQuery.page(-1, 20));
        assertInvalidRequest(() -> StatisticsQuery.page(0, 0));
        assertInvalidRequest(() -> StatisticsQuery.page(0, 101));
    }

    @Test
    void defaultsGranularityToDayAndUsesAllowlistedSqlUnit() {
        StatisticsQuery.ByPeriod query =
                StatisticsQuery.byPeriod(PERIOD, null);

        assertThat(query.granularity())
                .isEqualTo(StatisticsGranularity.DAY);
        assertThat(query.granularity().sqlDateTruncUnit())
                .isEqualTo("day");
    }

    @Test
    void rejectsEveryGranularityExceptExactDay() {
        assertInvalidRequest(
                () -> StatisticsQuery.byPeriod(PERIOD, "HOUR"));
        assertInvalidRequest(
                () -> StatisticsQuery.byPeriod(PERIOD, "day"));
        assertInvalidRequest(
                () -> StatisticsQuery.byPeriod(
                        PERIOD,
                        "DAY; DROP TABLE robot.mission"));
    }

    @Test
    void rejectsMissingValidatedPeriod() {
        assertInvalidRequest(
                () -> StatisticsQuery.byArea(null, null, 0, 20));
        assertInvalidRequest(
                () -> StatisticsQuery.byPeriod(null, "DAY"));
    }

    @Test
    void keepsPeriodAndAreaPageTogether() {
        StatisticsQuery.ByArea query = StatisticsQuery.byArea(
                PERIOD,
                null,
                2,
                25);

        assertThat(query.period()).isSameAs(PERIOD);
        assertThat(query.patrolAreaId()).isNull();
        assertThat(query.page())
                .isEqualTo(new StatisticsQuery.Page(2, 25, 50));
    }

    @Test
    void maximumDurationConstantIsStable() {
        assertThat(StatisticsPeriod.MAXIMUM_DURATION)
                .isEqualTo(Duration.ofDays(366));
    }

    private static void assertInvalidRequest(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
    }
}
