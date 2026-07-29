package com.gilbom.statistics;

import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * 모든 통계 조회가 공유하는 UTC 반열림 구간이다.
 *
 * <p>{@link Instant}는 timezone이나 offset을 보존하지 않는 UTC timeline 값이므로,
 * offset이 다른 ISO-8601 입력도 생성 시점에 같은 UTC Instant로 정규화된다.</p>
 */
public record StatisticsPeriod(
        Instant from,
        Instant to
) {

    public static final Duration MAXIMUM_DURATION = Duration.ofDays(366);

    public StatisticsPeriod {
        if (from == null || to == null) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "통계 조회 시작 시각과 종료 시각은 필수입니다.");
        }
        if (!from.isBefore(to)
                || Duration.between(from, to)
                        .compareTo(MAXIMUM_DURATION) > 0) {
            throw new BusinessException(
                    ErrorCode.STATISTICS_PERIOD_INVALID);
        }

        // Instant 자체가 UTC 정규형이다. 방어적으로 canonical Instant를 다시 만든다.
        from = Instant.ofEpochSecond(from.getEpochSecond(), from.getNano());
        to = Instant.ofEpochSecond(to.getEpochSecond(), to.getNano());
    }

    /**
     * SQL 문자열을 만들지 않고 공통 named parameter에 기간을 결합한다.
     */
    MapSqlParameterSource bind(MapSqlParameterSource parameters) {
        return parameters
                .addValue("from", from)
                .addValue("to", to);
    }
}
