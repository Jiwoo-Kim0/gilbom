package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.llm.LlmGenerationResponse;
import com.gilbom.llm.LlmUsage;
import com.gilbom.report.ReportContent;
import com.gilbom.report.ReportGenerationMethod;
import com.gilbom.report.generation.ReportLlmInputPreparer.PreparedReportLlmInput;

class ReportLlmResultValidatorTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();
    private final ReportLlmInputPreparer preparer =
            new ReportLlmInputPreparer(objectMapper);
    private final ReportLlmResultValidator validator =
            new ReportLlmResultValidator(
                    objectMapper,
                    new GeneratedReportTextGuard(objectMapper));

    @Test
    void validatesSchemaAndOriginalEvidenceBeforeRendering() {
        PreparedReportLlmInput prepared =
                preparer.prepare(ReportLlmTestFixtures.input());
        ReportLlmResult result = validator.validate(
                response(ReportLlmTestFixtures.validResultJson()),
                prepared);
        ReportContent content =
                new ReportLlmResultRenderer(objectMapper)
                        .render(
                                result,
                                prepared,
                                "INC-20260715-000001 보고서",
                                Map.of(
                                        "defectIds",
                                        java.util.List.of(
                                                ReportLlmTestFixtures
                                                        .DEFECT_ID),
                                        "missionIds",
                                        java.util.List.of(),
                                        "patrolAreaIds",
                                        java.util.List.of()));

        assertThat(content.generationMethod())
                .isEqualTo(ReportGenerationMethod.LLM);
        assertThat(content.llmModel())
                .isEqualTo("gpt-5.4-mini");
        assertThat(content.promptVersion())
                .isEqualTo("report-v1");
        assertThat(content.title())
                .isEqualTo("INC-20260715-000001 보고서");
        assertThat(content.body())
                .startsWith("# INC-20260715-000001 보고서")
                .contains("## 통계")
                .contains("임무: 전체 3건, 완료 2건, 취소 1건, 실패 0건")
                .contains("완료율: 66.67%")
                .contains("상태별 결함: 신규 1건")
                .contains("유형별 결함: 파손 1건")
                .contains("## 주요 결함")
                .contains(
                        "결함 ID: "
                                + ReportLlmTestFixtures.DEFECT_ID)
                .contains("결함 유형: 파손")
                .contains("위험도: 높음")
                .contains("처리 상태: 신규")
                .contains("## 조치 권고")
                .contains("[조치 우선순위: 높음]")
                .contains(
                        "관련 결함 ID: "
                                + ReportLlmTestFixtures.DEFECT_ID)
                .doesNotContain("DAMAGED")
                .doesNotContain("HIGH")
                .doesNotContain("NEW")
                .doesNotContain("검증된 통계 snapshot")
                .doesNotContain("<script");
    }

    @Test
    void rejectsUnknownFieldsAndChangedStatistics() {
        String unknown = ReportLlmTestFixtures
                .validResultJson()
                .replace(
                        "\"modelIdentifier\": \"gpt-5.4-mini\"",
                        "\"modelIdentifier\": \"gpt-5.4-mini\","
                                + "\"rawLog\": \"hidden\"");
        assertInvalid(unknown);

        String changed = ReportLlmTestFixtures
                .validResultJson()
                .replace("\"defectCount\": 1",
                        "\"defectCount\": 9");
        assertInvalid(changed);
    }

    @Test
    void rejectsMissingOrUnlistedDefectAndExecutableOutput() {
        String missingDefect = ReportLlmTestFixtures
                .validResultJson()
                .replaceFirst(
                        "(?s)\"keyDefects\"\\s*:\\s*\\[.*?\\],"
                                + "\\s*\"actionRecommendations\"",
                        "\"keyDefects\": [],"
                                + "\n  \"actionRecommendations\"");
        assertInvalid(missingDefect);

        String otherDefect = ReportLlmTestFixtures
                .validResultJson()
                .replace(
                        ReportLlmTestFixtures.DEFECT_ID.toString(),
                        "39a01de5-1dcc-45c2-b35a-c314586565b3");
        assertInvalid(otherDefect);

        String script = ReportLlmTestFixtures
                .validResultJson()
                .replace(
                        "현장 상태를 우선 확인하십시오.",
                        "<script>alert(1)</script>");
        assertInvalid(script);
    }

    @Test
    void rejectsPromptVersionAndPeriodMismatch() {
        assertInvalid(ReportLlmTestFixtures.validResultJson()
                .replace("report-v1", "report-v2"));
        assertInvalid(ReportLlmTestFixtures.validResultJson()
                .replace(
                        "2026-07-31T00:00:00Z",
                        "2026-07-30T00:00:00Z"));
    }

    @Test
    void rejectsUnknownOrMalformedDefectIdInNarrative() {
        String unknown = ReportLlmTestFixtures
                .validResultJson()
                .replace(
                        "보행 안전 영향을 검토해야 합니다.",
                        "defectId "
                                + "25a284ad-dfd4-45e0-b326-affe01309b5c"
                                + "는 현장 확인이 필요합니다.");
        assertInvalid(unknown);

        String malformed = ReportLlmTestFixtures
                .validResultJson()
                .replace(
                        "보행 안전 영향을 검토해야 합니다.",
                        "defectId not-a-valid-uuid는 "
                                + "현장 확인이 필요합니다.");
        assertInvalid(malformed);
    }

    @Test
    void acceptsKnownDefectIdAndTranslatesEnumCodesInNarrative() {
        String responseJson = ReportLlmTestFixtures
                .validResultJson()
                .replace(
                        "보행 안전 영향을 검토해야 합니다.",
                        "defectId "
                                + ReportLlmTestFixtures.DEFECT_ID
                                + "는 DAMAGED, HIGH, NEW 상태이며 "
                                + "NORMAL 수준의 후속 확인이 필요합니다.");
        PreparedReportLlmInput prepared =
                preparer.prepare(ReportLlmTestFixtures.input());
        ReportLlmResult result = validator.validate(
                response(responseJson),
                prepared);
        ReportContent content =
                new ReportLlmResultRenderer(objectMapper)
                        .render(
                                result,
                                prepared,
                                "INC-20260715-000001 보고서",
                                Map.of(
                                        "defectIds",
                                        java.util.List.of(
                                                ReportLlmTestFixtures
                                                        .DEFECT_ID),
                                        "missionIds",
                                        java.util.List.of(),
                                        "patrolAreaIds",
                                        java.util.List.of()));

        assertThat(content.body())
                .contains(
                        "defectId "
                                + ReportLlmTestFixtures.DEFECT_ID
                                + "는 파손, 높음, 신규 상태이며 "
                                + "일반 수준")
                .doesNotContain("DAMAGED")
                .doesNotContain("HIGH")
                .doesNotContain("NEW")
                .doesNotContain("NORMAL");
    }

    private void assertInvalid(String content) {
        PreparedReportLlmInput prepared =
                preparer.prepare(ReportLlmTestFixtures.input());
        assertThatThrownBy(() -> validator.validate(
                response(content),
                prepared))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode())
                                .isEqualTo(
                                        ErrorCode
                                                .LLM_RESPONSE_INVALID));
    }

    private static LlmGenerationResponse response(
            String content
    ) {
        return new LlmGenerationResponse(
                content,
                "gpt-5.4-mini",
                "report-v1",
                new LlmUsage(500, 300, 800));
    }
}
