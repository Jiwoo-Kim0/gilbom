package com.gilbom.defect.realtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.gilbom.defect.DefectDtos.GeoPoint;

/**
 * SSE로 내보내는 결함 이벤트 payload다. 좌표는 20장 공통 규칙대로
 * {@link GeoPoint}(longitude, latitude) 순서를 따른다.
 */
public record DefectSseEventResponse(
        String eventType,
        UUID defectId,
        String incidentCode,
        UUID patrolAreaId,
        String defectType,
        String riskLevel,
        String workflowStatus,
        GeoPoint location,
        BigDecimal latestConfidence,
        String beforeStatus,
        String afterStatus,
        List<String> changedFields,
        Instant occurredAt
) {

    static DefectSseEventResponse from(DefectRealtimeEvent event) {
        GeoPoint location =
                event.longitude() == null || event.latitude() == null
                        ? null
                        : new GeoPoint(event.longitude(), event.latitude());
        return new DefectSseEventResponse(
                event.eventType(),
                event.defectId(),
                event.incidentCode(),
                event.patrolAreaId(),
                event.defectType(),
                event.riskLevel(),
                event.workflowStatus(),
                location,
                event.latestConfidence(),
                event.beforeStatus(),
                event.afterStatus(),
                event.changedFields(),
                event.occurredAt());
    }
}
