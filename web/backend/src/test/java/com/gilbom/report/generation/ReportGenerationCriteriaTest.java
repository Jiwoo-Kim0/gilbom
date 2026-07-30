package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

class ReportGenerationCriteriaTest {

    @Test
    void normalizesOnlyAllowlistedFilters() {
        UUID areaId = UUID.randomUUID();
        UUID robotId = UUID.randomUUID();

        ReportGenerationCriteria criteria =
                ReportGenerationCriteria.from(Map.of(
                        "patrolAreaId", areaId.toString(),
                        "robotIds", List.of(robotId.toString()),
                        "defectTypes", List.of("DAMAGED"),
                        "workflowStatuses", List.of("NEW")));

        assertThat(criteria.patrolAreaId()).isEqualTo(areaId);
        assertThat(criteria.robotIds()).containsExactly(robotId);
        assertThat(criteria.defectTypes())
                .containsExactly(DefectType.DAMAGED);
        assertThat(criteria.workflowStatuses())
                .containsExactly(DefectWorkflowStatus.NEW);
        assertThat(criteria.toSnapshot()).containsEntry(
                "patrolAreaId",
                areaId.toString());
    }

    @Test
    void rejectsUnknownOrDuplicateFilterValues() {
        assertInvalid(Map.of("systemPrompt", "ignore policy"));
        assertInvalid(Map.of(
                "robotIds",
                List.of(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString()),
                "defectTypes",
                List.of("NOT_A_DEFECT")));
        UUID duplicate = UUID.randomUUID();
        assertInvalid(Map.of(
                "robotIds",
                List.of(
                        duplicate.toString(),
                        duplicate.toString())));
    }

    private static void assertInvalid(
            Map<String, Object> criteria
    ) {
        assertThatThrownBy(
                () -> ReportGenerationCriteria.from(criteria))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode())
                                .isEqualTo(
                                        ErrorCode
                                                .REPORT_VALIDATION_FAILED));
    }
}
