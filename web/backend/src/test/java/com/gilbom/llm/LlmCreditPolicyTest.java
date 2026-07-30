package com.gilbom.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmCreditPolicyTest {

    private final LlmCreditPolicy policy =
            new LlmCreditPolicy();

    @Test
    void usesTheApprovedModelRates() {
        assertThat(policy.actualCredits(
                "gpt-5.4-mini",
                new LlmUsage(5_000, 1_500, 6_500)))
                .isEqualByComparingTo("176.250000");
        assertThat(policy.actualCredits(
                "gpt-5.4",
                new LlmUsage(5_000, 1_500, 6_500)))
                .isEqualByComparingTo("587.500000");
    }

    @Test
    void reservesTheMaximumApprovedInputBeforeCalling() {
        assertThat(policy.maximumAttemptCredits(
                "gpt-5.4-mini",
                1_500))
                .isEqualByComparingTo("221.250000");
    }
}
