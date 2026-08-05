package com.gilbom.report.generation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.report.ReportContent;
import com.gilbom.report.ReportGenerationMethod;
import com.gilbom.report.generation.ReportLlmInput.DefectEvidence;
import com.gilbom.report.generation.ReportLlmInputPreparer.PreparedReportLlmInput;

/**
 * 검증이 끝난 구조화 결과만 실행 불가능한 Markdown text로 렌더링한다.
 */
@Component
public class ReportLlmResultRenderer {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    public ReportLlmResultRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReportContent render(
            ReportLlmResult result,
            PreparedReportLlmInput prepared,
            String reportTitle,
            Map<String, Object> evidenceSnapshot
    ) {
        String renderedSummary =
                narrative(result.summary());
        StringBuilder body = new StringBuilder();
        body.append("# ").append(inline(reportTitle))
                .append("\n\n## 요약\n")
                .append(renderedSummary)
                .append("\n\n## 통계\n");
        appendStatistics(body, result.statistics());
        body.append("\n## 주요 결함\n");
        if (result.keyDefects().isEmpty()) {
            body.append("- 기간 내 보고 가능한 주요 결함이 없습니다.\n");
        } else {
            for (ReportLlmResult.KeyDefect defect
                    : result.keyDefects()) {
                DefectEvidence evidence = prepared.evidenceById()
                        .get(defect.defectId());
                if (evidence == null) {
                    throw new IllegalStateException(
                            "Validated defect evidence is missing");
                }
                body.append("- 결함 ID: ")
                        .append(evidence.defectId())
                        .append(" · 사건 코드: ")
                        .append(inline(defect.incidentCode()))
                        .append(" · 결함 유형: ")
                        .append(ReportKoreanLabels.defectType(
                                defect.defectType()))
                        .append(" · 위험도: ")
                        .append(ReportKoreanLabels.riskLevel(
                                defect.riskLevel()))
                        .append(" · 처리 상태: ")
                        .append(ReportKoreanLabels.workflowStatus(
                                evidence.workflowStatus()))
                        .append(" — ")
                        .append(narrative(defect.rationale()))
                        .append('\n');
            }
        }
        body.append("\n## 조치 권고\n");
        if (result.actionRecommendations().isEmpty()) {
            body.append("- 담당자 검토 후 조치 여부를 결정하십시오.\n");
        } else {
            for (ReportLlmResult.ActionRecommendation action
                    : result.actionRecommendations()) {
                body.append("- [조치 우선순위: ")
                        .append(ReportKoreanLabels.priority(
                                action.priority()))
                        .append("] ")
                        .append(narrative(
                                action.recommendation()));
                if (!action.relatedDefectIds().isEmpty()) {
                    body.append(" — 관련 결함 ID: ")
                            .append(action.relatedDefectIds()
                                    .stream()
                                    .map(UUID::toString)
                                    .collect(java.util.stream.Collectors
                                            .joining(", ")));
                }
                body.append(" — ")
                        .append(narrative(action.rationale()))
                        .append('\n');
            }
        }

        Object sourceStatistics =
                prepared.promptInput().get("statistics");
        Map<String, Object> statistics =
                objectMapper.convertValue(
                        sourceStatistics,
                        JSON_OBJECT);
        return new ReportContent(
                reportTitle,
                renderedSummary,
                prepared.source().period().from(),
                prepared.source().period().to(),
                body.toString(),
                java.util.Collections.unmodifiableMap(
                        new LinkedHashMap<>(statistics)),
                evidenceSnapshot,
                result.modelIdentifier(),
                result.promptVersion(),
                ReportGenerationMethod.LLM,
                null);
    }

    private static void appendStatistics(
            StringBuilder body,
            ReportLlmResult.Statistics statistics
    ) {
        ReportLlmInput.Aggregate aggregate =
                statistics.aggregate();
        body.append("- 임무: 전체 ")
                .append(aggregate.missionCount())
                .append("건, 완료 ")
                .append(aggregate.completedMissionCount())
                .append("건, 취소 ")
                .append(aggregate.cancelledMissionCount())
                .append("건, 실패 ")
                .append(aggregate.failedMissionCount())
                .append("건\n")
                .append("- 완료율: ")
                .append(aggregate.completionRatePercent()
                        .stripTrailingZeros()
                        .toPlainString())
                .append("%\n")
                .append("- 총 임무 시간: ")
                .append(aggregate.totalMissionDurationSeconds())
                .append("초\n")
                .append("- 사건 이벤트: ")
                .append(aggregate.incidentEventCount())
                .append("건, 결함: ")
                .append(aggregate.defectCount())
                .append("건, 관측: ")
                .append(aggregate.observationCount())
                .append("건\n")
                .append("- 상태별 결함: ");
        appendDistribution(
                body,
                statistics.defectsByStatus().stream()
                        .map(value -> ReportKoreanLabels
                                .workflowStatus(
                                        value.workflowStatus())
                                + " " + value.defectCount()
                                + "건")
                        .toList());
        body.append("- 유형별 결함: ");
        appendDistribution(
                body,
                statistics.defectsByType().stream()
                        .map(value -> ReportKoreanLabels
                                .defectType(value.defectType())
                                + " " + value.defectCount()
                                + "건")
                        .toList());
    }

    private static void appendDistribution(
            StringBuilder body,
            java.util.List<String> values
    ) {
        body.append(values.isEmpty()
                        ? "없음"
                        : String.join(", ", values))
                .append('\n');
    }

    private static String inline(String value) {
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private static String narrative(String value) {
        return inline(
                ReportKoreanLabels.translateNarrative(value));
    }
}
