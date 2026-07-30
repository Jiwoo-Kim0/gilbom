package com.gilbom.report.generation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.report.ReportContent;
import com.gilbom.report.ReportGenerationMethod;
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
            Map<String, Object> evidenceSnapshot
    ) {
        StringBuilder body = new StringBuilder();
        body.append("# ").append(inline(result.title()))
                .append("\n\n## 요약\n")
                .append(inline(result.summary()))
                .append("\n\n## 통계\n")
                .append("검증된 통계 snapshot은 이 보고서 버전에 함께 보존됩니다.")
                .append("\n\n## 주요 결함\n");
        if (result.keyDefects().isEmpty()) {
            body.append("- 기간 내 보고 가능한 주요 결함이 없습니다.\n");
        } else {
            for (ReportLlmResult.KeyDefect defect
                    : result.keyDefects()) {
                body.append("- ")
                        .append(inline(defect.incidentCode()))
                        .append(" · ")
                        .append(defect.defectType())
                        .append(" · ")
                        .append(defect.riskLevel())
                        .append(": ")
                        .append(inline(defect.rationale()))
                        .append('\n');
            }
        }
        body.append("\n## 조치 권고\n");
        if (result.actionRecommendations().isEmpty()) {
            body.append("- 담당자 검토 후 조치 여부를 결정하십시오.\n");
        } else {
            for (ReportLlmResult.ActionRecommendation action
                    : result.actionRecommendations()) {
                body.append("- [")
                        .append(action.priority())
                        .append("] ")
                        .append(inline(action.recommendation()))
                        .append(" — ")
                        .append(inline(action.rationale()))
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
                result.title(),
                result.summary(),
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

    private static String inline(String value) {
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }
}
