package com.gilbom.llm;

import java.util.Objects;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

public final class DisabledLlmClient implements LlmClient {

    private final LlmPolicyDecision policyDecision;

    public DisabledLlmClient(
            LlmPolicyDecision policyDecision
    ) {
        this.policyDecision = Objects.requireNonNull(
                policyDecision,
                "policyDecision must not be null");
        if (policyDecision.enabled()) {
            throw new IllegalArgumentException(
                    "Disabled client requires a disabled decision");
        }
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public LlmGenerationResponse generate(
            LlmGenerationRequest request
    ) {
        throw new BusinessException(ErrorCode.LLM_UNAVAILABLE);
    }

    public LlmPolicyDecision policyDecision() {
        return policyDecision;
    }
}
