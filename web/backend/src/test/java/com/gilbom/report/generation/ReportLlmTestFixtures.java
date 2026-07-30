package com.gilbom.report.generation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.gilbom.report.generation.ReportLlmInput.Aggregate;
import com.gilbom.report.generation.ReportLlmInput.DefectEvidence;
import com.gilbom.report.generation.ReportLlmInput.DefectRiskCount;
import com.gilbom.report.generation.ReportLlmInput.DefectStatusCount;
import com.gilbom.report.generation.ReportLlmInput.DefectTypeCount;
import com.gilbom.report.generation.ReportLlmInput.Period;
import com.gilbom.report.generation.ReportLlmInput.RiskLevel;
import com.gilbom.report.generation.ReportLlmInput.Statistics;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

final class ReportLlmTestFixtures {

    static final Instant FROM =
            Instant.parse("2026-07-01T00:00:00Z");
    static final Instant TO =
            Instant.parse("2026-07-31T00:00:00Z");
    static final UUID DEFECT_ID =
            UUID.fromString("d2146db0-d408-4f76-9999-d28b5272ac12");

    private ReportLlmTestFixtures() {
    }

    static ReportLlmInput input() {
        return input("점자블록 표면 파손이 확인되었습니다.");
    }

    static ReportLlmInput input(String description) {
        return new ReportLlmInput(
                new Period(FROM, TO),
                new Statistics(
                        new Aggregate(
                                3,
                                2,
                                1,
                                0,
                                new BigDecimal("66.67"),
                                3_600,
                                2,
                                1,
                                2),
                        List.of(new DefectStatusCount(
                                DefectWorkflowStatus.NEW,
                                1)),
                        List.of(new DefectTypeCount(
                                DefectType.DAMAGED,
                                1)),
                        List.of(new DefectRiskCount(
                                RiskLevel.HIGH,
                                1))),
                List.of(new DefectEvidence(
                        DEFECT_ID,
                        "INC-20260715-000001",
                        DefectType.DAMAGED,
                        RiskLevel.HIGH,
                        DefectWorkflowStatus.NEW,
                        Instant.parse(
                                "2026-07-15T10:00:00Z"),
                        "A-01 구역",
                        description)));
    }

    static String validResultJson() {
        return """
                {
                  "title": "7월 점자블록 결함 보고서",
                  "summary": "기간 내 주요 결함 1건을 확인했습니다.",
                  "statistics": {
                    "aggregate": {
                      "missionCount": 3,
                      "completedMissionCount": 2,
                      "cancelledMissionCount": 1,
                      "failedMissionCount": 0,
                      "completionRatePercent": 66.67,
                      "totalMissionDurationSeconds": 3600,
                      "incidentEventCount": 2,
                      "defectCount": 1,
                      "observationCount": 2
                    },
                    "defectsByStatus": [
                      {"workflowStatus": "NEW", "defectCount": 1}
                    ],
                    "defectsByType": [
                      {"defectType": "DAMAGED", "defectCount": 1}
                    ]
                  },
                  "keyDefects": [
                    {
                      "defectId": "%s",
                      "incidentCode": "INC-20260715-000001",
                      "defectType": "DAMAGED",
                      "riskLevel": "HIGH",
                      "locationLabel": "A-01 구역",
                      "lastSeenAt": "2026-07-15T10:00:00Z",
                      "rationale": "동일 기간의 서버 근거에서 높은 위험도를 확인했습니다."
                    }
                  ],
                  "actionRecommendations": [
                    {
                      "priority": "HIGH",
                      "recommendation": "현장 상태를 우선 확인하십시오.",
                      "rationale": "보행 안전 영향을 검토해야 합니다.",
                      "relatedDefectIds": ["%s"]
                    }
                  ],
                  "period": {
                    "from": "2026-07-01T00:00:00Z",
                    "to": "2026-07-31T00:00:00Z"
                  },
                  "promptVersion": "report-v1",
                  "modelIdentifier": "gpt-5.4-mini"
                }
                """.formatted(DEFECT_ID, DEFECT_ID);
    }
}
