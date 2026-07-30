package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gilbom.common.error.BusinessException;
import com.gilbom.report.generation.ReportLlmInput.DefectEvidence;

class ReportLlmInputTest {

    @Test
    void rejectsMoreThanTwentyEvidenceItems() {
        ReportLlmInput source = ReportLlmTestFixtures.input();
        List<DefectEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            DefectEvidence original =
                    source.defectEvidence().getFirst();
            evidence.add(new DefectEvidence(
                    UUID.randomUUID(),
                    "INC-20260715-%06d".formatted(index),
                    original.defectType(),
                    original.riskLevel(),
                    original.workflowStatus(),
                    original.lastSeenAt(),
                    original.locationLabel(),
                    original.minimalDescription()));
        }

        assertThatThrownBy(() -> new ReportLlmInput(
                source.period(),
                source.statistics(),
                evidence))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsDistributionThatDoesNotMatchAggregate() {
        ReportLlmInput source = ReportLlmTestFixtures.input();

        assertThatThrownBy(() -> new ReportLlmInput.Statistics(
                source.statistics().aggregate(),
                List.of(new ReportLlmInput.DefectStatusCount(
                        com.gilbom.statistics.StatisticsDtos
                                .DefectWorkflowStatus.NEW,
                        0)),
                source.statistics().defectsByType(),
                source.statistics().defectsByRiskLevel()))
                .isInstanceOf(BusinessException.class);
    }
}
