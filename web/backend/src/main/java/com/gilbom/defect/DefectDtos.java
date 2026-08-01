package com.gilbom.defect;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Swagger 21.10 Defects의 요청·응답 계약이다. 필드 이름과 enum 값은
 * 23.7 DefectListItem, 23.8 DefectStatusUpdateRequest를 그대로 따른다.
 */
public final class DefectDtos {

    private DefectDtos() {
    }

    private static <K, V> Map<K, V> unmodifiableOrNull(Map<K, V> values) {
        return values == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    // ---------------------------------------------------------------- 공통

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {

        public PageResponse {
            content = List.copyOf(content);
        }
    }

    /**
     * 20장 공통 규칙에 따라 좌표 순서는 [longitude, latitude]다.
     */
    public record GeoPoint(
            double longitude,
            double latitude
    ) {
    }

    // ---------------------------------------------------------------- 조회 응답

    public record DefectListItemResponse(
            UUID defectId,
            String incidentCode,
            String assetType,
            String blockType,
            String defectType,
            String riskLevel,
            BigDecimal riskScore,
            String workflowStatus,
            String resolution,
            GeoPoint location,
            Double locationAccuracyM,
            String zoneId,
            UUID patrolAreaId,
            UUID patrolRouteId,
            String locationLabel,
            String representativeImageUrl,
            Instant firstSeenAt,
            Instant lastSeenAt,
            int observationCount,
            BigDecimal latestConfidence,
            boolean reviewRequired
    ) {
    }

    public record DefectDetailResponse(
            DefectListItemResponse defect,
            String resolutionNote,
            BigDecimal riskScore,
            String riskRuleVersion,
            Instant riskCalculatedAt,
            UUID mergedIntoDefectId,
            List<UUID> mergedDefectIds,
            Instant createdAt,
            Instant updatedAt
    ) {

        public DefectDetailResponse {
            mergedDefectIds = List.copyOf(mergedDefectIds);
        }
    }

    public record DefectSummaryResponse(
            long totalDefects,
            long newDefects,
            long acceptedDefects,
            long inProgressDefects,
            long resolvedDefects,
            long falsePositiveDefects,
            long rejectedDefects,
            long criticalDefects,
            long highDefects,
            long reviewRequiredDefects
    ) {
    }

    /**
     * 22.5 예시와 같은 GeoJSON FeatureCollection이다.
     */
    public record DefectMapResponse(
            String type,
            List<DefectMapFeature> features
    ) {

        public DefectMapResponse(List<DefectMapFeature> features) {
            this("FeatureCollection", features);
        }

        public DefectMapResponse {
            features = List.copyOf(features);
        }
    }

    public record DefectMapFeature(
            String type,
            DefectMapGeometry geometry,
            DefectMapProperties properties
    ) {

        public DefectMapFeature(
                DefectMapGeometry geometry,
                DefectMapProperties properties
        ) {
            this("Feature", geometry, properties);
        }
    }

    public record DefectMapGeometry(
            String type,
            List<Double> coordinates
    ) {

        public static DefectMapGeometry point(
                double longitude,
                double latitude
        ) {
            return new DefectMapGeometry(
                    "Point",
                    List.of(longitude, latitude));
        }

        public DefectMapGeometry {
            coordinates = List.copyOf(coordinates);
        }
    }

    public record DefectMapProperties(
            UUID defectId,
            String incidentCode,
            String defectType,
            String riskLevel,
            String workflowStatus,
            BigDecimal latestConfidence
    ) {
    }

    public record DefectObservationResponse(
            UUID eventId,
            UUID defectId,
            UUID robotId,
            /** robot.robot에서 가져온 표시용 이름. 로봇 행이 없으면 null이다. */
            String robotName,
            boolean locationValid,
            GeoPoint location,
            Double locationAccuracyM,
            String zoneId,
            String assetType,
            String blockType,
            String detectedDefectType,
            BigDecimal confidence,
            BigDecimal damageRatio,
            UUID imageId,
            Instant evidenceCapturedAt,
            Integer imageWidth,
            Integer imageHeight,
            List<Double> bboxNorm,
            String modelName,
            String modelVersion,
            boolean representative,
            Instant createdAt
    ) {

        public DefectObservationResponse {
            bboxNorm = bboxNorm == null ? null : List.copyOf(bboxNorm);
        }
    }

    public record DefectHistoryItemResponse(
            long historyId,
            UUID defectId,
            UUID changedBy,
            String changeType,
            String beforeStatus,
            String afterStatus,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues,
            String reason,
            Instant createdAt
    ) {

        public DefectHistoryItemResponse {
            // snapshot JSON은 null 값을 가질 수 있어 Map.copyOf를 쓸 수 없다.
            beforeValues = unmodifiableOrNull(beforeValues);
            afterValues = unmodifiableOrNull(afterValues);
        }
    }

    public record DefectImageUrlResponse(
            UUID defectId,
            UUID eventId,
            UUID imageId,
            String imageUrl,
            Instant expiresAt,
            Integer imageWidth,
            Integer imageHeight,
            List<Double> bboxNorm
    ) {

        public DefectImageUrlResponse {
            bboxNorm = bboxNorm == null ? null : List.copyOf(bboxNorm);
        }
    }

    public record DefectMergeResponse(
            UUID targetDefectId,
            List<UUID> mergedDefectIds,
            int observationCount,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {

        public DefectMergeResponse {
            mergedDefectIds = List.copyOf(mergedDefectIds);
        }
    }

    public record DefectExportAcceptedResponse(
            UUID exportJobId,
            String jobStatus,
            String exportFormat,
            Instant requestedAt
    ) {
    }

    // ---------------------------------------------------------------- 요청

    /**
     * PATCH /api/v1/defects/{defectId} — 결함 유형·심각도·위치 수정.
     * 모든 항목은 부분 수정이므로 선택이며, 변경 이력에 남길 reason만 필수다.
     */
    public record DefectUpdateRequest(
            String defectType,
            String riskLevel,
            String blockType,

            @DecimalMin("-180.0")
            @DecimalMax("180.0")
            Double longitude,

            @DecimalMin("-90.0")
            @DecimalMax("90.0")
            Double latitude,

            @DecimalMin("0.0")
            Double locationAccuracyM,

            @Pattern(regexp = "^[A-Z]-[0-9]{2}$")
            String zoneId,

            @Size(max = 200)
            String locationLabel,

            Boolean reviewRequired,

            @NotBlank
            @Size(max = 1000)
            String reason
    ) {
    }

    /**
     * PATCH /api/v1/defects/{defectId}/status — 23.8 계약을 그대로 따른다.
     */
    public record DefectStatusUpdateRequest(
            @NotBlank
            String workflowStatus,

            String resolution,

            @Size(max = 4000)
            String resolutionNote,

            @NotBlank
            @Size(max = 1000)
            String reason
    ) {
    }

    public record DefectMergeRequest(
            @NotNull
            UUID targetDefectId,

            @NotEmpty
            @Size(max = 50)
            List<@NotNull UUID> sourceDefectIds,

            @NotBlank
            @Size(max = 1000)
            String reason
    ) {
    }

    public record DefectExportRequest(
            @NotBlank
            String format,

            @NotNull
            @Valid
            DefectFilterRequest filter,

            @Size(max = 1000)
            String reason
    ) {
    }

    /**
     * 목록·지도와 동일한 필터를 내보내기 본문에서도 받는다.
     * Swagger의 query 파라미터 이름을 그대로 사용한다.
     */
    public record DefectFilterRequest(
            @Size(max = 100)
            String q,
            Instant from,
            Instant to,
            @Size(max = 10) List<String> workflowStatuses,
            @Size(max = 10) List<String> categories,
            @Size(max = 10) List<String> defectTypes,
            @Size(max = 10) List<String> severities,
            UUID patrolAreaId,
            UUID patrolRouteId,
            UUID robotId,
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minConfidence,
            @Size(max = 100) String bbox
    ) {
    }
}
