package com.gilbom.statistics;

import java.util.Set;
import java.util.UUID;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.statistics.StatisticsDtos.StatisticsGranularity;

/**
 * endpoint별 공통 query 입력을 검증된 값으로 변환한다.
 */
public final class StatisticsQuery {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAXIMUM_SIZE = 100;

    private StatisticsQuery() {
    }

    /**
     * endpoint 계약에 없는 query parameter를 조용히 무시하지 않는다.
     *
     * <p>통계 차원은 고정 path와 enum으로만 선택한다. 따라서 사용자 입력이
     * GROUP BY 컬럼이나 SQL identifier로 해석될 여지를 차단한다.</p>
     */
    public static void requireOnlyParameters(
            Set<String> actualParameters,
            Set<String> allowedParameters
    ) {
        if (actualParameters == null
                || allowedParameters == null
                || !allowedParameters.containsAll(actualParameters)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "지원하지 않는 통계 요청 파라미터입니다.");
        }
    }

    public static Page page(int page, int size) {
        if (page < 0 || size < 1 || size > MAXIMUM_SIZE) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        try {
            return new Page(
                    page,
                    size,
                    Math.multiplyExact((long) page, (long) size));
        } catch (ArithmeticException exception) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "통계 페이지 범위가 너무 큽니다.",
                    exception);
        }
    }

    public static ByArea byArea(
            StatisticsPeriod period,
            UUID patrolAreaId,
            int page,
            int size
    ) {
        if (period == null) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        return new ByArea(
                period,
                patrolAreaId,
                page(page, size));
    }

    public static ByPeriod byPeriod(
            StatisticsPeriod period,
            String rawGranularity
    ) {
        if (period == null) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        return new ByPeriod(
                period,
                StatisticsGranularity.from(rawGranularity));
    }

    public record Page(
            int page,
            int size,
            long offset
    ) {
    }

    public record ByArea(
            StatisticsPeriod period,
            UUID patrolAreaId,
            Page page
    ) {
    }

    public record ByPeriod(
            StatisticsPeriod period,
            StatisticsGranularity granularity
    ) {
    }
}
