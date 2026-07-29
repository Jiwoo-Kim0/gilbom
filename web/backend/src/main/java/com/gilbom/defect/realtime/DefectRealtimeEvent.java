package com.gilbom.defect.realtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Swagger 24장 SSE 이벤트 계약의 결함 이벤트다.
 *
 * <p>이벤트명은 계약에 적힌 {@code defect.created}·{@code defect.updated}를 그대로
 * 사용한다. 프론트가 {@code EventSource.addEventListener(name)}으로 구독하는 값이므로
 * 계약과 달라지면 화면이 조용히 갱신되지 않는다.
 */
public record DefectRealtimeEvent(
        String eventType,
        UUID defectId,
        String incidentCode,
        UUID patrolAreaId,
        String defectType,
        String riskLevel,
        String workflowStatus,
        Double longitude,
        Double latitude,
        BigDecimal latestConfidence,
        String beforeStatus,
        String afterStatus,
        UUID mergedIntoDefectId,
        List<String> changedFields,
        Instant occurredAt
) {

    public static final String CREATED = "defect.created";
    public static final String UPDATED = "defect.updated";

    public DefectRealtimeEvent {
        changedFields = changedFields == null
                ? List.of()
                : List.copyOf(changedFields);
    }

    /**
     * 점자블록 사건 저장·결함 생성 후. 계약의 핵심 payload는
     * defectId, incidentCode, location, defectType, riskLevel이다.
     */
    public static DefectRealtimeEvent created(
            UUID defectId,
            String incidentCode,
            UUID patrolAreaId,
            String defectType,
            String riskLevel,
            String workflowStatus,
            Double longitude,
            Double latitude,
            BigDecimal latestConfidence,
            Instant occurredAt
    ) {
        return new DefectRealtimeEvent(
                CREATED,
                defectId,
                incidentCode,
                patrolAreaId,
                defectType,
                riskLevel,
                workflowStatus,
                longitude,
                latitude,
                latestConfidence,
                null,
                null,
                null,
                List.of(),
                occurredAt);
    }

    /**
     * 행정 상태·유형·위치 변경 후. 계약의 핵심 payload는
     * defectId, workflowStatus, before/after 요약이다.
     */
    public static DefectRealtimeEvent updated(
            UUID defectId,
            String incidentCode,
            UUID patrolAreaId,
            String defectType,
            String riskLevel,
            String workflowStatus,
            Double longitude,
            Double latitude,
            String beforeStatus,
            String afterStatus,
            List<String> changedFields,
            Instant occurredAt
    ) {
        return new DefectRealtimeEvent(
                UPDATED,
                defectId,
                incidentCode,
                patrolAreaId,
                defectType,
                riskLevel,
                workflowStatus,
                longitude,
                latitude,
                null,
                beforeStatus,
                afterStatus,
                null,
                changedFields,
                occurredAt);
    }

    /**
     * 병합으로 흡수된 결함. 목록·지도에서는 대표 결함만 노출하므로 구독자는 이
     * 이벤트를 받으면 해당 결함을 화면에서 제거하고 {@code mergedIntoDefectId}의
     * 대표 결함을 다시 조회해야 한다.
     *
     * <p>계약 24장이 defect.updated의 조건으로 병합을 명시하지는 않지만, 이벤트가
     * 없으면 다른 관리자 화면이 사라진 결함을 계속 표시한다.
     */
    public static DefectRealtimeEvent mergedInto(
            UUID defectId,
            String incidentCode,
            UUID patrolAreaId,
            String defectType,
            String riskLevel,
            String workflowStatus,
            Double longitude,
            Double latitude,
            UUID mergedIntoDefectId,
            Instant occurredAt
    ) {
        return new DefectRealtimeEvent(
                UPDATED,
                defectId,
                incidentCode,
                patrolAreaId,
                defectType,
                riskLevel,
                workflowStatus,
                longitude,
                latitude,
                null,
                null,
                null,
                mergedIntoDefectId,
                List.of("mergedIntoDefectId"),
                occurredAt);
    }
}
