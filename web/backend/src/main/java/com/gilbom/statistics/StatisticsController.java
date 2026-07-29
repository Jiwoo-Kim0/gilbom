package com.gilbom.statistics;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통계 API의 공통 base path를 소유한다.
 *
 * <p>각 {@code @GetMapping}은 해당 집계 SQL과 계약 테스트가 구현되는 작업에서
 * 함께 추가한다. 아직 계산하지 않는 값을 빈 성공 응답으로 노출하지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private final StatisticsQueryService queryService;

    public StatisticsController(StatisticsQueryService queryService) {
        this.queryService = queryService;
    }
}
