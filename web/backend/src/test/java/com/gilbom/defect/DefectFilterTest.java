package com.gilbom.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class DefectFilterTest {

    @Test
    void acceptsCommaJoinedEnumValuesLikeTheSwaggerExample() {
        // 22.5의 workflowStatuses=NEW,IN_PROGRESS 형태
        DefectFilter filter = DefectFilter.of(
                null, null, null,
                List.of("NEW,IN_PROGRESS"),
                null, null, null,
                null, null, null, null, null);

        assertThat(filter.workflowStatuses())
                .containsExactlyInAnyOrder("NEW", "IN_PROGRESS");
    }

    @Test
    void normalizesEnumCaseAndWhitespace() {
        DefectFilter filter = DefectFilter.of(
                null, null, null,
                null, null,
                List.of(" damaged ", "unknown_anomaly"),
                List.of("high"),
                null, null, null, null, null);

        assertThat(filter.defectTypes())
                .containsExactlyInAnyOrder("DAMAGED", "UNKNOWN_ANOMALY");
        assertThat(filter.severities()).containsExactly("HIGH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARCHIVED", "NEW; DROP TABLE defect.defect", "'"})
    void rejectsUnknownWorkflowStatusInsteadOfInliningIt(String value) {
        assertThatThrownBy(() -> DefectFilter.of(
                null, null, null,
                List.of(value),
                null, null, null,
                null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    void parsesBoundingBoxInLongitudeLatitudeOrder() {
        DefectFilter filter = DefectFilter.of(
                null, null, null, null, null, null, null,
                null, null, null, null,
                "126.9,37.4,127.1,37.6");

        assertThat(filter.bbox()).isEqualTo(new DefectFilter.BoundingBox(
                126.9, 37.4, 127.1, 37.6));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "126.9,37.4,127.1",
            "126.9,37.4,127.1,37.6,1",
            "a,b,c,d",
            // 최소값이 최대값보다 큰 경우
            "127.1,37.4,126.9,37.6",
            "126.9,37.6,127.1,37.4",
            // 좌표 범위를 벗어난 경우
            "-181,37.4,127.1,37.6",
            "126.9,-91,127.1,37.6"
    })
    void rejectsMalformedBoundingBox(String bbox) {
        assertThatThrownBy(() -> DefectFilter.of(
                null, null, null, null, null, null, null,
                null, null, null, null, bbox))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    void rejectsInvertedTimeRange() {
        assertThatThrownBy(() -> DefectFilter.of(
                null,
                Instant.parse("2026-07-31T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                null, null, null, null,
                null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("종료 시각");
    }

    @Test
    void buildsEmptyPredicateWhenNoFilterIsGiven() {
        assertThat(DefectFilter.empty().toPredicate()).isEmpty();
    }

    @Test
    void mapsOnlyAllowlistedSortFieldsToColumns() {
        assertThat(DefectFilter.toOrderBy("riskScore,asc"))
                .isEqualTo("ORDER BY d.risk_score ASC, d.defect_id ASC\n");
        // 정렬 기준이 없으면 최근 관측 순서가 기본값이다.
        assertThat(DefectFilter.toOrderBy(null))
                .isEqualTo("ORDER BY d.last_seen_at DESC, d.defect_id ASC\n");
        // 방향을 생략하면 내림차순이다.
        assertThat(DefectFilter.toOrderBy("incidentCode"))
                .isEqualTo("ORDER BY d.incident_code DESC, d.defect_id ASC\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "defect_id",
            "riskScore; DROP TABLE defect.defect",
            "(SELECT 1)",
            "riskScore,sideways"
    })
    void rejectsSortInputThatIsNotAnAllowlistedColumn(String sort) {
        assertThatThrownBy(() -> DefectFilter.toOrderBy(sort))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    void keepsFilterSnapshotForExportJob() {
        DefectFilter filter = DefectFilter.of(
                "A-01",
                Instant.parse("2026-07-01T00:00:00Z"),
                null,
                List.of("NEW"),
                null, null, null,
                null, null, null, null,
                "126.9,37.4,127.1,37.6");

        assertThat(filter.toSnapshot())
                .containsEntry("q", "A-01")
                .containsEntry("from", "2026-07-01T00:00:00Z")
                .containsEntry("workflowStatuses", List.of("NEW"))
                .containsEntry("bbox", List.of(126.9, 37.4, 127.1, 37.6))
                .doesNotContainKey("to")
                .doesNotContainKey("robotId");
    }
}
