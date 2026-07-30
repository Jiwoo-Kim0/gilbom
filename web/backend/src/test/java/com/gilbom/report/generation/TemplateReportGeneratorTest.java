package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gilbom.llm.DisabledLlmClient;
import com.gilbom.llm.FakeLlmClient;
import com.gilbom.llm.LlmPolicyDecision;
import com.gilbom.llm.LlmPolicyViolation;
import com.gilbom.report.ReportContent;
import com.gilbom.report.ReportGenerationMethod;
import com.gilbom.report.ReportType;

class TemplateReportGeneratorTest {

    @Test
    void createsDeterministicTemplateWithoutLlm() {
        UUID defectId = UUID.randomUUID();
        TemplateReportRequest request =
                new TemplateReportRequest(
                        ReportType.PERIODIC,
                        "7월 순찰 보고서",
                        "총 3건의 결함을 확인했습니다.",
                        Instant.parse(
                                "2026-07-01T00:00:00Z"),
                        Instant.parse(
                                "2026-07-31T23:59:59Z"),
                        Map.of("defectCount", 3),
                        Map.of(
                                "defectIds",
                                List.of(defectId),
                                "missionIds",
                                List.of(),
                                "patrolAreaIds",
                                List.of()));

        ReportContent content =
                new TemplateReportGenerator().generate(request);

        assertThat(content.generationMethod())
                .isEqualTo(ReportGenerationMethod.TEMPLATE);
        assertThat(content.llmModel()).isNull();
        assertThat(content.promptVersion()).isNull();
        assertThat(content.body())
                .contains("# 7월 순찰 보고서")
                .contains("근거 결함 1건")
                .contains("담당자가 조치 우선순위를 확정");
        assertThat(content.statistics())
                .containsEntry("defectCount", 3);
    }

    @Test
    void disabledPolicyDowngradesOnlyLlmToTemplate() {
        DisabledLlmClient disabled =
                new DisabledLlmClient(
                        new LlmPolicyDecision(
                                false,
                                List.of(
                                        LlmPolicyViolation
                                                .API_KEY_MISSING)));
        ReportGenerationModeResolver resolver =
                new ReportGenerationModeResolver(disabled);

        assertThat(resolver.resolve(
                ReportGenerationMethod.LLM))
                .isEqualTo(ReportGenerationMethod.TEMPLATE);
        assertThat(resolver.resolve(
                ReportGenerationMethod.MANUAL))
                .isEqualTo(ReportGenerationMethod.MANUAL);
        assertThat(resolver.resolve(
                ReportGenerationMethod.TEMPLATE))
                .isEqualTo(ReportGenerationMethod.TEMPLATE);
    }

    @Test
    void fakeClientKeepsLlmModeForDevelopment() {
        FakeLlmClient fake = new FakeLlmClient();
        ReportGenerationModeResolver resolver =
                new ReportGenerationModeResolver(fake);

        assertThat(resolver.resolve(
                ReportGenerationMethod.LLM))
                .isEqualTo(ReportGenerationMethod.LLM);
    }
}
