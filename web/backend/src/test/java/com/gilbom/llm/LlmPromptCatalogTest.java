package com.gilbom.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class LlmPromptCatalogTest {

    private final LlmPromptCatalog catalog =
            new LlmPromptCatalog();

    @Test
    void reportV2DefinesSummaryImageJudgmentAndActionOwnership() {
        String prompt = catalog.systemPrompt("report-v2");

        assertThat(prompt)
                .contains(
                        "Summarize the report itself",
                        "Copy the supplied statistics exactly",
                        "combining its metadata",
                        "matching image",
                        "supports, qualifies, or conflicts",
                        "담당: <관련 기능 부서 또는 역할>",
                        "조치: <구체적인 연락·현장 통제·점검·수리·후속 확인 방법>",
                        "verification or closure method");
    }

    @Test
    void keepsReportV1ForRollbackAndRejectsUnknownVersion() {
        assertThat(catalog.systemPrompt("report-v1"))
                .contains("return exactly these top-level fields");
        assertThatThrownBy(
                () -> catalog.systemPrompt("report-v3"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception)
                                .errorCode())
                        .isEqualTo(ErrorCode.LLM_UNAVAILABLE));
    }
}
