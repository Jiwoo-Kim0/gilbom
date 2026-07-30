package com.gilbom.report.generation;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.gilbom.report.ReportContent;
import com.gilbom.report.ReportGenerationMethod;

@Component
public class TemplateReportGenerator {

    public ReportContent generate(TemplateReportRequest request) {
        String summary = request.summary() == null
                || request.summary().isBlank()
                ? "선택한 기간의 순찰·결함 통계를 정리한 보고서입니다."
                : request.summary().trim();
        String body = """
                # %s

                ## 요약
                %s

                ## 통계
                통계 snapshot은 이 보고서 버전에 함께 보존됩니다.

                ## 주요 결함
                근거 결함 %d건을 기준으로 상세 검토가 필요합니다.

                ## 조치 권고
                원본 결함과 순찰 기록을 확인한 뒤 담당자가 조치 우선순위를 확정하십시오.
                """.formatted(
                        request.title(),
                        summary,
                        evidenceCount(
                                request.evidenceSnapshot(),
                                "defectIds"));
        return new ReportContent(
                request.title(),
                summary,
                request.periodFrom(),
                request.periodTo(),
                body,
                request.statistics(),
                request.evidenceSnapshot(),
                null,
                null,
                ReportGenerationMethod.TEMPLATE,
                null);
    }

    private static int evidenceCount(
            Map<String, Object> evidence,
            String field
    ) {
        Object value = evidence.get(field);
        return value instanceof List<?> list
                ? list.size()
                : 0;
    }
}
