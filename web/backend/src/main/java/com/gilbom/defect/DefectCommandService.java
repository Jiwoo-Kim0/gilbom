package com.gilbom.defect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.audit.AuditAction;
import com.gilbom.audit.AuditActorType;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditSnapshot;
import com.gilbom.audit.AuditTargetType;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.defect.DefectDtos.DefectDetailResponse;
import com.gilbom.defect.DefectDtos.DefectExportAcceptedResponse;
import com.gilbom.defect.DefectDtos.DefectExportRequest;
import com.gilbom.defect.DefectDtos.DefectMergeRequest;
import com.gilbom.defect.DefectDtos.DefectMergeResponse;
import com.gilbom.defect.DefectDtos.DefectStatusUpdateRequest;
import com.gilbom.defect.DefectDtos.DefectUpdateRequest;
import com.gilbom.defect.DefectRepository.DefectAggregate;
import com.gilbom.defect.DefectRepository.DefectAttributeChange;
import com.gilbom.defect.DefectRepository.DefectHistoryEntry;
import com.gilbom.defect.DefectRepository.DefectState;

/**
 * Swagger 21.10의 결함 변경 계약을 구현한다.
 *
 * <p>변경마다 defect_status_history(전후 값)와 audit_log(누가·왜)를 같은
 * 트랜잭션에서 남긴다. 22.6이 정의한 것과 같은 순서다.
 */
@Service
public class DefectCommandService {

    private static final Set<String> EXPORT_FORMATS = Set.of("CSV", "XLSX");

    private final DefectRepository repository;
    private final DefectQueryService queryService;
    private final AuditLogService auditLogService;

    public DefectCommandService(
            DefectRepository repository,
            DefectQueryService queryService,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.queryService = queryService;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------ 유형·심각도·위치 수정

    @Transactional
    public DefectDetailResponse updateDefect(
            UUID userId,
            UUID defectId,
            DefectUpdateRequest request,
            AuditRequestContext context
    ) {
        DefectState before = requireOperableDefect(userId, defectId);
        requireChangeable(before);

        DefectAttributeChange change = toChange(request, before);
        if (isNoop(change)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "변경할 항목이 없습니다.");
        }
        if (repository.updateAttributes(defectId, change) != 1) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "결함이 변경되어 처리할 수 없습니다.");
        }

        // 유형·위험도·위치는 서로 다른 change_type이므로 바뀐 축마다 이력을 남긴다.
        appendAttributeHistories(userId, defectId, before, change, request.reason());

        auditLogService.appendRequired(new AuditLogRecord(
                context,
                userId,
                AuditActorType.USER,
                AuditAction.DEFECT_UPDATED.databaseValue(),
                AuditTargetType.DEFECT,
                defectId,
                snapshot(attributeValues(
                        before.blockType(),
                        before.defectType(),
                        before.riskLevel(),
                        before.zoneId(),
                        before.locationLabel(),
                        before.reviewRequired(),
                        before.longitude(),
                        before.latitude())),
                snapshot(attributeValues(
                        firstNonNull(change.blockType(), before.blockType()),
                        firstNonNull(change.defectType(), before.defectType()),
                        firstNonNull(change.riskLevel(), before.riskLevel()),
                        firstNonNull(change.zoneId(), before.zoneId()),
                        firstNonNull(change.locationLabel(), before.locationLabel()),
                        firstNonNull(change.reviewRequired(), before.reviewRequired()),
                        firstNonNull(change.longitude(), before.longitude()),
                        firstNonNull(change.latitude(), before.latitude()))),
                request.reason(),
                AuditResult.SUCCESS));

        return queryService.findDefect(userId, defectId);
    }

    // -------------------------------------------------------- 처리 상태 변경

    @Transactional
    public DefectDetailResponse changeStatus(
            UUID userId,
            UUID defectId,
            DefectStatusUpdateRequest request,
            AuditRequestContext context
    ) {
        String afterStatus =
                DefectWorkflow.requireKnownStatus(request.workflowStatus());
        String resolution =
                DefectWorkflow.requireKnownResolution(request.resolution());
        DefectWorkflow.requireResolutionConsistent(afterStatus, resolution);

        DefectState before = requireOperableDefect(userId, defectId);
        if (before.isMergedAway()) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "병합된 결함은 대표 결함에서 처리해야 합니다.");
        }
        DefectWorkflow.requireTransitionAllowed(
                before.workflowStatus(),
                afterStatus);

        String resolutionNote = trimToNull(request.resolutionNote());
        if (repository.updateWorkflowStatus(
                defectId,
                before.workflowStatus(),
                afterStatus,
                resolution,
                resolutionNote) != 1) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "결함 처리 상태가 변경되어 처리할 수 없습니다.");
        }

        Map<String, Object> beforeValues = statusValues(
                before.workflowStatus(),
                before.resolution(),
                before.resolutionNote());
        Map<String, Object> afterValues = statusValues(
                afterStatus,
                resolution,
                resolutionNote);

        repository.appendStatusHistory(new DefectHistoryEntry(
                defectId,
                userId,
                "STATUS_CHANGED",
                before.workflowStatus(),
                afterStatus,
                beforeValues,
                afterValues,
                request.reason()));
        // 처리 결과가 함께 바뀌면 RESOLUTION_CHANGED 이력도 별도로 남긴다.
        if (!java.util.Objects.equals(before.resolution(), resolution)) {
            repository.appendStatusHistory(new DefectHistoryEntry(
                    defectId,
                    userId,
                    "RESOLUTION_CHANGED",
                    before.workflowStatus(),
                    afterStatus,
                    Map.of("resolution", nullable(before.resolution())),
                    Map.of("resolution", nullable(resolution)),
                    request.reason()));
        }

        auditLogService.appendRequired(new AuditLogRecord(
                context,
                userId,
                AuditActorType.USER,
                AuditAction.DEFECT_STATUS_CHANGED.databaseValue(),
                AuditTargetType.DEFECT,
                defectId,
                snapshot(beforeValues),
                snapshot(afterValues),
                request.reason(),
                AuditResult.SUCCESS));

        return queryService.findDefect(userId, defectId);
    }

    // ------------------------------------------------------------- 병합

    @Transactional
    public DefectMergeResponse merge(
            UUID userId,
            DefectMergeRequest request,
            AuditRequestContext context
    ) {
        UUID targetDefectId = request.targetDefectId();
        Set<UUID> sourceIds = new LinkedHashSet<>(request.sourceDefectIds());
        if (sourceIds.contains(targetDefectId)) {
            throw new BusinessException(
                    ErrorCode.DEFECT_MERGE_NOT_ALLOWED,
                    "대표 결함은 병합 원본이 될 수 없습니다.");
        }

        // 교착을 피하려고 항상 같은 순서(정렬된 결함 id)로 잠근다.
        List<UUID> lockOrder = new ArrayList<>(sourceIds);
        lockOrder.add(targetDefectId);
        lockOrder.sort(java.util.Comparator.naturalOrder());
        Map<UUID, DefectState> locked = new LinkedHashMap<>();
        for (UUID defectId : lockOrder) {
            locked.put(defectId, requireOperableDefect(userId, defectId));
        }

        DefectState target = locked.get(targetDefectId);
        if (target.isMergedAway()) {
            throw new BusinessException(
                    ErrorCode.DEFECT_MERGE_NOT_ALLOWED,
                    "이미 다른 결함에 병합된 결함은 대표가 될 수 없습니다.");
        }
        for (UUID sourceId : sourceIds) {
            DefectState source = locked.get(sourceId);
            if (source.isMergedAway()) {
                throw new BusinessException(
                        ErrorCode.DEFECT_MERGE_NOT_ALLOWED,
                        "이미 병합된 결함은 다시 병합할 수 없습니다.");
            }
            if (DefectWorkflow.isTerminal(source.workflowStatus())) {
                throw new BusinessException(
                        ErrorCode.DEFECT_MERGE_NOT_ALLOWED,
                        "종결된 결함은 병합할 수 없습니다.");
            }
            // 대표 결함이 자신에게 병합된 결함을 다시 원본으로 삼으면 순환이 생긴다.
            if (targetDefectId.equals(source.mergedIntoDefectId())) {
                throw new BusinessException(
                        ErrorCode.DEFECT_MERGE_NOT_ALLOWED,
                        "순환 병합은 허용되지 않습니다.");
            }
        }

        for (UUID sourceId : sourceIds) {
            if (repository.markMergedInto(sourceId, targetDefectId) != 1) {
                throw new BusinessException(
                        ErrorCode.DEFECT_MERGE_NOT_ALLOWED,
                        "결함이 변경되어 병합할 수 없습니다.");
            }
            repository.appendMergeHistory(
                    sourceId,
                    targetDefectId,
                    userId,
                    request.reason());
            repository.appendStatusHistory(new DefectHistoryEntry(
                    sourceId,
                    userId,
                    "MERGED",
                    locked.get(sourceId).workflowStatus(),
                    locked.get(sourceId).workflowStatus(),
                    Map.of("mergedIntoDefectId", "NONE"),
                    Map.of("mergedIntoDefectId", targetDefectId.toString()),
                    request.reason()));
        }

        DefectAggregate aggregate =
                repository.recalculateAggregate(targetDefectId);
        repository.appendStatusHistory(new DefectHistoryEntry(
                targetDefectId,
                userId,
                "MERGED",
                target.workflowStatus(),
                target.workflowStatus(),
                Map.of("observationCount", target.observationCount()),
                Map.of("observationCount", aggregate.observationCount()),
                request.reason()));

        auditLogService.appendRequired(new AuditLogRecord(
                context,
                userId,
                AuditActorType.USER,
                AuditAction.DEFECT_MERGED.databaseValue(),
                AuditTargetType.DEFECT,
                targetDefectId,
                snapshot(Map.of(
                        "observationCount", target.observationCount())),
                snapshot(Map.of(
                        "observationCount", aggregate.observationCount(),
                        "mergedDefectIds", sourceIds.stream()
                                .map(UUID::toString)
                                .toList())),
                request.reason(),
                AuditResult.SUCCESS));

        return new DefectMergeResponse(
                targetDefectId,
                List.copyOf(sourceIds),
                aggregate.observationCount(),
                aggregate.firstSeenAt(),
                aggregate.lastSeenAt());
    }

    // ---------------------------------------------------------- 내보내기

    /**
     * 접수만 기록하고 202로 응답한다. 실제 파일 생성 worker는 아직 없다.
     */
    @Transactional
    public DefectExportAcceptedResponse requestExport(
            UUID userId,
            DefectExportRequest request,
            AuditRequestContext context
    ) {
        String format = request.format() == null
                ? ""
                : request.format().trim().toUpperCase(Locale.ROOT);
        if (!EXPORT_FORMATS.contains(format)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "지원하지 않는 내보내기 형식입니다.");
        }

        DefectFilter filter = DefectFilter.from(request.filter());
        Map<String, Object> criteria = filter.toSnapshot();
        UUID exportJobId =
                repository.insertExportJob(userId, format, criteria);

        auditLogService.appendRequired(new AuditLogRecord(
                context,
                userId,
                AuditActorType.USER,
                AuditAction.DEFECT_EXPORT_REQUESTED.databaseValue(),
                AuditTargetType.DEFECT,
                exportJobId,
                null,
                snapshot(Map.of(
                        "exportFormat", format,
                        "filterCriteria", criteria)),
                request.reason(),
                AuditResult.SUCCESS));

        return new DefectExportAcceptedResponse(
                exportJobId,
                "QUEUED",
                format,
                java.time.Instant.now());
    }

    // ------------------------------------------------------------ helpers

    /**
     * 변경 대상은 OPERATOR 이상 배정이 있어야 하며, 없거나 범위 밖이면
     * 존재 여부를 숨기려고 모두 DEFECT_NOT_FOUND로 응답한다.
     */
    private DefectState requireOperableDefect(UUID userId, UUID defectId) {
        return repository.lockForUpdate(
                        userId,
                        defectId,
                        DefectScopeService.OPERABLE_DEFECT_PREDICATE)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
    }

    /**
     * Swagger 21.10의 "409 병합·완료 상태 충돌"이다.
     */
    private static void requireChangeable(DefectState before) {
        if (before.isMergedAway()) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "병합된 결함은 대표 결함에서 수정해야 합니다.");
        }
        if (DefectWorkflow.isTerminal(before.workflowStatus())) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "종결된 결함은 수정할 수 없습니다.");
        }
    }

    private static DefectAttributeChange toChange(
            DefectUpdateRequest request,
            DefectState before
    ) {
        String defectType = normalizedEnum(
                request.defectType(),
                DefectFilter.DEFECT_TYPES,
                "defectType");
        String riskLevel = normalizedEnum(
                request.riskLevel(),
                DefectFilter.RISK_LEVELS,
                "riskLevel");
        String blockType = normalizedEnum(
                request.blockType(),
                DefectFilter.BLOCK_TYPES,
                "blockType");
        if ((request.longitude() == null) != (request.latitude() == null)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "위치는 longitude와 latitude를 함께 보내야 합니다.");
        }
        // 좌표는 한 쌍으로 판정한다. 한쪽만 바뀌었다고 다른 쪽을 null로 두면
        // 갱신 SQL이 위치를 통째로 건너뛴다.
        Double longitude = null;
        Double latitude = null;
        if (request.longitude() != null
                && (Double.compare(request.longitude(), before.longitude()) != 0
                    || Double.compare(request.latitude(), before.latitude()) != 0)) {
            longitude = request.longitude();
            latitude = request.latitude();
        }
        return new DefectAttributeChange(
                changedOrNull(blockType, before.blockType()),
                changedOrNull(defectType, before.defectType()),
                changedOrNull(riskLevel, before.riskLevel()),
                changedOrNull(trimToNull(request.zoneId()), before.zoneId()),
                changedOrNull(
                        trimToNull(request.locationLabel()),
                        before.locationLabel()),
                changedOrNull(
                        request.locationAccuracyM(),
                        before.locationAccuracyM()),
                changedOrNull(request.reviewRequired(), before.reviewRequired()),
                longitude,
                latitude);
    }

    private static boolean isNoop(DefectAttributeChange change) {
        return change.blockType() == null
                && change.defectType() == null
                && change.riskLevel() == null
                && change.zoneId() == null
                && change.locationLabel() == null
                && change.locationAccuracyM() == null
                && change.reviewRequired() == null
                && change.longitude() == null
                && change.latitude() == null;
    }

    private void appendAttributeHistories(
            UUID userId,
            UUID defectId,
            DefectState before,
            DefectAttributeChange change,
            String reason
    ) {
        if (change.defectType() != null || change.blockType() != null) {
            repository.appendStatusHistory(DefectHistoryEntry.of(
                    defectId,
                    userId,
                    "TYPE_CHANGED",
                    Map.of(
                            "defectType", before.defectType(),
                            "blockType", before.blockType()),
                    Map.of(
                            "defectType", firstNonNull(
                                    change.defectType(), before.defectType()),
                            "blockType", firstNonNull(
                                    change.blockType(), before.blockType())),
                    reason));
        }
        if (change.riskLevel() != null) {
            repository.appendStatusHistory(DefectHistoryEntry.of(
                    defectId,
                    userId,
                    "RISK_CHANGED",
                    Map.of("riskLevel", before.riskLevel()),
                    Map.of("riskLevel", change.riskLevel()),
                    reason));
        }
        boolean locationChanged = change.longitude() != null
                || change.latitude() != null
                || change.zoneId() != null
                || change.locationLabel() != null
                || change.locationAccuracyM() != null;
        if (locationChanged) {
            repository.appendStatusHistory(DefectHistoryEntry.of(
                    defectId,
                    userId,
                    "LOCATION_CHANGED",
                    Map.of(
                            "longitude", before.longitude(),
                            "latitude", before.latitude(),
                            "zoneId", before.zoneId(),
                            "locationLabel", nullable(before.locationLabel())),
                    Map.of(
                            "longitude", firstNonNull(
                                    change.longitude(), before.longitude()),
                            "latitude", firstNonNull(
                                    change.latitude(), before.latitude()),
                            "zoneId", firstNonNull(
                                    change.zoneId(), before.zoneId()),
                            "locationLabel", nullable(firstNonNull(
                                    change.locationLabel(),
                                    before.locationLabel()))),
                    reason));
        }
    }

    private static Map<String, Object> attributeValues(
            String blockType,
            String defectType,
            String riskLevel,
            String zoneId,
            String locationLabel,
            boolean reviewRequired,
            double longitude,
            double latitude
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("blockType", blockType);
        values.put("defectType", defectType);
        values.put("riskLevel", riskLevel);
        values.put("zoneId", zoneId);
        values.put("locationLabel", nullable(locationLabel));
        values.put("reviewRequired", reviewRequired);
        values.put("longitude", longitude);
        values.put("latitude", latitude);
        return values;
    }

    private static Map<String, Object> statusValues(
            String workflowStatus,
            String resolution,
            String resolutionNote
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workflowStatus", workflowStatus);
        values.put("resolution", nullable(resolution));
        values.put("resolutionNote", nullable(resolutionNote));
        return values;
    }

    private static String normalizedEnum(
            String value,
            Set<String> allowed,
            String field
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(candidate)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "지원하지 않는 " + field + " 값입니다.");
        }
        return candidate;
    }

    private static <T> T changedOrNull(T requested, T current) {
        if (requested == null || requested.equals(current)) {
            return null;
        }
        return requested;
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Object nullable(Object value) {
        return value == null ? "NONE" : value;
    }

    private static AuditSnapshot snapshot(Map<String, ?> values) {
        return AuditSnapshot.allow(values.keySet(), values);
    }
}
