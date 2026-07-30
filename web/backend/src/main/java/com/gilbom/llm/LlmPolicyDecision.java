package com.gilbom.llm;

import java.util.List;

public record LlmPolicyDecision(
        boolean enabled,
        List<LlmPolicyViolation> violations
) {

    public LlmPolicyDecision {
        violations = List.copyOf(violations);
        if (enabled != violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Enabled policy decision must have no violations");
        }
    }
}
