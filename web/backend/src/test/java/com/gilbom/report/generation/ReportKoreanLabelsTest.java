package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.gilbom.report.generation.ReportLlmInput.RiskLevel;
import com.gilbom.report.generation.ReportLlmResult.Priority;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

class ReportKoreanLabelsTest {

    @Test
    void mapsRequestedInternalCodesToReaderFacingKorean() {
        assertThat(ReportKoreanLabels.defectType(
                DefectType.MISSING)).isEqualTo("미설치");
        assertThat(ReportKoreanLabels.riskLevel(
                RiskLevel.MEDIUM)).isEqualTo("보통");
        assertThat(ReportKoreanLabels.workflowStatus(
                DefectWorkflowStatus.NEW)).isEqualTo("신규");
        assertThat(ReportKoreanLabels.priority(
                Priority.NORMAL)).isEqualTo("일반");
    }

    @Test
    void translatesStandaloneCodesWithoutChangingIncidentCode() {
        assertThat(ReportKoreanLabels.translateNarrative(
                "INC-NEW-MISSING의 분류는 MISSING, 위험도는 MEDIUM, "
                        + "상태는 NEW, 우선순위는 NORMAL입니다."))
                .isEqualTo(
                        "INC-NEW-MISSING의 분류는 미설치, 위험도는 보통, "
                                + "상태는 신규, 우선순위는 일반입니다.");
    }
}
