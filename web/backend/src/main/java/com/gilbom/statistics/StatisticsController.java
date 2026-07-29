package com.gilbom.statistics;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.statistics.StatisticsDtos.StatisticsByAreaResponse;
import com.gilbom.statistics.StatisticsDtos.StatisticsByDefectTypeResponse;
import com.gilbom.statistics.StatisticsDtos.StatisticsByStatusResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 통계 API의 공통 base path를 소유한다.
 *
 * <p>각 {@code @GetMapping}은 해당 집계 SQL과 계약 테스트가 구현되는 작업에서
 * 함께 추가한다. 미구현 집계는 빈 성공 응답으로 노출하지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private static final Set<String> PERIOD_ONLY_PARAMETERS =
            Set.of("from", "to");
    private static final Set<String> AREA_PARAMETERS =
            Set.of("from", "to", "patrolAreaId", "page", "size");

    private final StatisticsQueryService queryService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public StatisticsController(
            StatisticsQueryService queryService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.queryService = queryService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping("/by-status")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public StatisticsByStatusResponse findByStatus(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            Authentication authentication,
            HttpServletRequest request
    ) {
        StatisticsQuery.requireOnlyParameters(
                request.getParameterMap().keySet(),
                PERIOD_ONLY_PARAMETERS);
        return queryService.findByStatus(
                userIdResolver.require(authentication),
                from,
                to);
    }

    @GetMapping("/by-defect-type")
    @PreAuthorize("hasAuthority('DEFECT_STATUS_VIEW')")
    public StatisticsByDefectTypeResponse findByDefectType(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            Authentication authentication,
            HttpServletRequest request
    ) {
        StatisticsQuery.requireOnlyParameters(
                request.getParameterMap().keySet(),
                PERIOD_ONLY_PARAMETERS);
        return queryService.findByDefectType(
                userIdResolver.require(authentication),
                from,
                to);
    }

    @GetMapping("/by-area")
    @PreAuthorize(
            "hasAuthority('MISSION_VIEW')"
                    + " and hasAuthority('DEFECT_STATUS_VIEW')")
    public StatisticsByAreaResponse findByArea(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(required = false) UUID patrolAreaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication,
            HttpServletRequest request
    ) {
        StatisticsQuery.requireOnlyParameters(
                request.getParameterMap().keySet(),
                AREA_PARAMETERS);
        return queryService.findByArea(
                userIdResolver.require(authentication),
                from,
                to,
                patrolAreaId,
                page,
                size);
    }
}
