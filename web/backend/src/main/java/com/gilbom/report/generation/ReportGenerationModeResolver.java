package com.gilbom.report.generation;

import org.springframework.stereotype.Component;

import com.gilbom.llm.LlmClient;
import com.gilbom.report.ReportGenerationMethod;

/**
 * 정책상 LLM을 사용할 수 없는 환경에서는 LLM 요청을 결정적 템플릿 생성으로 낮춘다.
 */
@Component
public class ReportGenerationModeResolver {

    private final LlmClient llmClient;

    public ReportGenerationModeResolver(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public ReportGenerationMethod resolve(
            ReportGenerationMethod requested
    ) {
        if (requested == null) {
            throw new IllegalArgumentException(
                    "Generation method is required");
        }
        if (requested == ReportGenerationMethod.LLM
                && !llmClient.isAvailable()) {
            return ReportGenerationMethod.TEMPLATE;
        }
        return requested;
    }
}
