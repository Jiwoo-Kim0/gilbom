package com.gilbom.statistics;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

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

    public StatisticsQuery.ByPeriod requirePeriodQuery(
            Instant from,
            Instant to,
            String granularity
    ) {
        return StatisticsQuery.byPeriod(
                requirePeriod(from, to),
                granularity);
    }
}
