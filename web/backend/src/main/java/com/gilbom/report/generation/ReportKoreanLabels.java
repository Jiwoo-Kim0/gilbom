package com.gilbom.report.generation;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gilbom.report.generation.ReportLlmInput.RiskLevel;
import com.gilbom.report.generation.ReportLlmResult.Priority;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

/**
 * 보고서 독자에게 내부 enum 대신 표시할 한국어 용어를 한곳에서 관리한다.
 */
final class ReportKoreanLabels {

    private static final List<CodeLabel> NARRATIVE_LABELS = List.of(
            new CodeLabel("INSTALLATION_INVALID", "설치 이상"),
            new CodeLabel("UNKNOWN_ANOMALY", "기타 이상"),
            new CodeLabel("FALSE_POSITIVE", "오탐"),
            new CodeLabel("IN_PROGRESS", "처리 중"),
            new CodeLabel("IMMEDIATE", "즉시"),
            new CodeLabel("CRITICAL", "심각"),
            new CodeLabel("ACCEPTED", "접수"),
            new CodeLabel("RESOLVED", "완료"),
            new CodeLabel("REJECTED", "반려"),
            new CodeLabel("DAMAGED", "파손"),
            new CodeLabel("MISSING", "미설치"),
            new CodeLabel("MEDIUM", "보통"),
            new CodeLabel("NORMAL", "일반"),
            new CodeLabel("HIGH", "높음"),
            new CodeLabel("LOW", "낮음"),
            new CodeLabel("NEW", "신규"));

    private ReportKoreanLabels() {
    }

    static String defectType(DefectType value) {
        return switch (value) {
            case DAMAGED -> "파손";
            case MISSING -> "미설치";
            case INSTALLATION_INVALID -> "설치 이상";
            case UNKNOWN_ANOMALY -> "기타 이상";
        };
    }

    static String riskLevel(RiskLevel value) {
        return switch (value) {
            case LOW -> "낮음";
            case MEDIUM -> "보통";
            case HIGH -> "높음";
            case CRITICAL -> "심각";
        };
    }

    static String workflowStatus(DefectWorkflowStatus value) {
        return switch (value) {
            case NEW -> "신규";
            case ACCEPTED -> "접수";
            case IN_PROGRESS -> "처리 중";
            case RESOLVED -> "완료";
            case FALSE_POSITIVE -> "오탐";
            case REJECTED -> "반려";
        };
    }

    static String priority(Priority value) {
        return switch (value) {
            case IMMEDIATE -> "즉시";
            case HIGH -> "높음";
            case NORMAL -> "일반";
            case LOW -> "낮음";
        };
    }

    /**
     * 구형 Prompt나 Provider 편차로 자연어에 enum이 남아도 사용자 본문에는
     * 내부 코드를 노출하지 않는다. 하이픈으로 이어진 사건 코드는 변경하지 않는다.
     */
    static String translateNarrative(String value) {
        String translated = value;
        for (CodeLabel label : NARRATIVE_LABELS) {
            translated = label.pattern().matcher(translated)
                    .replaceAll(Matcher.quoteReplacement(
                            label.label()));
        }
        return translated;
    }

    private record CodeLabel(
            Pattern pattern,
            String label
    ) {

        CodeLabel(String code, String label) {
            this(
                    Pattern.compile(
                            "(?i)(?<![A-Z0-9_-])"
                                    + Pattern.quote(code)
                                    + "(?![A-Z0-9_-])"),
                    label);
        }
    }
}
