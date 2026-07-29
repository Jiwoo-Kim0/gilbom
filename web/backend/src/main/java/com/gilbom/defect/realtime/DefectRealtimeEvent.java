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
                changedFields,
                occurredAt);
    }
}
