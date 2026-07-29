package com.gilbom.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class StatisticsPeriodTest {

    private static final Instant FROM =
            Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void requiresBothBoundaries() {
        assertError(
                () -> new StatisticsPeriod(null, FROM),
                ErrorCode.COMMON_INVALID_REQUEST);
        assertError(
                () -> new StatisticsPeriod(FROM, null),
                ErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    void requiresStrictHalfOpenRange() {
        assertError(
                () -> new StatisticsPeriod(FROM, FROM),
                ErrorCode.STATISTICS_PERIOD_INVALID);
        assertError(
                () -> new StatisticsPeriod(
                        FROM.plusSeconds(1),
                        FROM),
                ErrorCode.STATISTICS_PERIOD_INVALID);
    }

    @Test
    void acceptsExactly366DaysAndRejectsAnythingLonger() {
        StatisticsPeriod maximum = new StatisticsPeriod(
                FROM,
                FROM.plus(StatisticsPeriod.MAXIMUM_DURATION));

        assertThat(Duration.between(maximum.from(), maximum.to()))
                .isEqualTo(Duration.ofDays(366));
        assertError(
                () -> new StatisticsPeriod(
                        FROM,
                        FROM.plus(StatisticsPeriod.MAXIMUM_DURATION)
                                .plusNanos(1)),
                ErrorCode.STATISTICS_PERIOD_INVALID);
    }

    @Test
    void normalizesOffsetDateTimesToTheSameUtcInstants() {
        StatisticsPeriod period = new StatisticsPeriod(
                OffsetDateTime.parse(
                        "2026-07-01T09:00:00+09:00").toInstant(),
                OffsetDateTime.parse(
                        "2026-07-02T09:00:00+09:00").toInstant());

        assertThat(period.from())
                .isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(period.to())
                .isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
        assertThat(period.from().toString()).endsWith("Z");
        assertThat(period.to().toString()).endsWith("Z");
    }

    @Test
    void bindsBothBoundariesAsNamedParameters() {
        StatisticsPeriod period = new StatisticsPeriod(
                FROM,
                FROM.plus(Duration.ofDays(1)));

        MapSqlParameterSource parameters =
                period.bind(new MapSqlParameterSource());

        assertThat(parameters.getValue("from")).isEqualTo(period.from());
        assertThat(parameters.getValue("to")).isEqualTo(period.to());
    }

    private static void assertError(
            Runnable action,
            ErrorCode expectedError
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(expectedError);
    }
}
