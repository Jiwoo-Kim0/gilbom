package com.gilbom.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class LlmPolicyGateTest {

    private final LlmPolicyGate gate = new LlmPolicyGate();

    @Test
    void disabledFeatureDoesNotRequireOperationalSecrets() {
        LlmPolicyDecision decision =
                gate.evaluate(new LlmProperties());

        assertThat(decision.enabled()).isFalse();
        assertThat(decision.violations())
                .containsExactly(
                        LlmPolicyViolation.FEATURE_DISABLED);
    }

    @Test
    void enabledFeatureCollectsEveryMissingPolicyCondition() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://unapproved.example/v1");

        LlmPolicyDecision decision = gate.evaluate(properties);

        assertThat(decision.enabled()).isFalse();
        assertThat(decision.violations()).contains(
                LlmPolicyViolation.API_KEY_MISSING,
                LlmPolicyViolation.PROVIDER_NOT_ALLOWED,
                LlmPolicyViolation.BASE_URL_NOT_ALLOWED,
                LlmPolicyViolation.MODEL_NOT_ALLOWED,
                LlmPolicyViolation
                        .TRAINING_EXCLUSION_NOT_APPROVED,
                LlmPolicyViolation
                        .DATA_RETENTION_NOT_APPROVED,
                LlmPolicyViolation
                        .DAILY_REQUEST_LIMIT_MISSING,
                LlmPolicyViolation.DAILY_BUDGET_MISSING,
                LlmPolicyViolation
                        .OUTBOUND_ALLOWLIST_NOT_APPLIED,
                LlmPolicyViolation.PROMPT_VERSION_MISSING,
                LlmPolicyViolation.POLICY_NOT_APPROVED);
    }

    @Test
    void enablesOnlyTheApprovedGmsConfiguration() {
        LlmPolicyDecision decision =
                gate.evaluate(approvedProperties());

        assertThat(decision.enabled()).isTrue();
        assertThat(decision.violations()).isEmpty();
    }

    @Test
    void allowsCurrentReportV3AndRejectsUnknownPromptVersion() {
        LlmProperties properties = approvedProperties();
        properties.setPromptVersion("report-v3");

        assertThat(gate.evaluate(properties).enabled()).isTrue();

        properties.setPromptVersion("report-v4");
        assertThat(gate.evaluate(properties).violations())
                .contains(
                        LlmPolicyViolation
                                .PROMPT_VERSION_NOT_ALLOWED);
    }

    @Test
    void rejectsUnapprovedModelTimeoutAndOutputLimit() {
        LlmProperties properties = approvedProperties();
        properties.setModel("gpt-5.5-pro");
        properties.setConnectTimeout(Duration.ofSeconds(11));
        properties.setMaxOutputTokens(1_501);
        properties.setMaxConcurrentRequests(21);

        assertThat(gate.evaluate(properties).violations())
                .contains(
                        LlmPolicyViolation.MODEL_NOT_ALLOWED,
                        LlmPolicyViolation.TIMEOUT_INVALID,
                        LlmPolicyViolation
                                .MAX_OUTPUT_TOKENS_INVALID,
                        LlmPolicyViolation
                                .MAX_CONCURRENT_REQUESTS_INVALID);
    }

    @Test
    void baseUrlAllowsOnlyTheExactGmsHttpsEndpoint() {
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                LlmPolicyGate.GMS_BASE_URL)).isTrue();
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                LlmPolicyGate.GMS_BASE_URL + "/")).isTrue();
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                "http://gms.ssafy.io/gmsapi/api.openai.com/v1"))
                .isFalse();
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                "https://gms.ssafy.io:444/gmsapi/api.openai.com/v1"))
                .isFalse();
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                "https://gms.ssafy.io/other/v1")).isFalse();
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                "https://gms.ssafy.io/gmsapi%2Fapi.openai.com/v1"))
                .isFalse();
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                "https://gms.ssafy.io/gmsapi/api.openai.com/v1?next=x"))
                .isFalse();
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                "https://user@gms.ssafy.io/gmsapi/api.openai.com/v1"))
                .isFalse();
        assertThat(LlmPolicyGate.isAllowedBaseUrl(
                "https://gms.ssafy.io.evil.example/gmsapi/api.openai.com/v1"))
                .isFalse();
    }

    static LlmProperties approvedProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setProvider("GMS");
        properties.setBaseUrl(LlmPolicyGate.GMS_BASE_URL);
        properties.setModel("gpt-5.4-mini");
        properties.setApiKey("test-only-api-key");
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(30));
        properties.setMaxOutputTokens(1_500);
        properties.setDailyRequestLimit(20);
        properties.setDailyBudget(
                new BigDecimal("2250"));
        properties.setPromptVersion("report-v1");
        properties.setPolicyApproved(true);
        properties.setTrainingExclusionApproved(true);
        properties.setDataRetentionApproved(true);
        properties.setOutboundAllowlistApplied(true);
        return properties;
    }
}
