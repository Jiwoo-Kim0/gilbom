package com.gilbom.llm;

public record LlmUsage(
        int inputTokens,
        int outputTokens,
        int totalTokens
) {

    public LlmUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || totalTokens < 0
                || (long) totalTokens
                        < (long) inputTokens + outputTokens) {
            throw new IllegalArgumentException(
                    "LLM token usage must be non-negative");
        }
    }

    public static LlmUsage unknown() {
        return new LlmUsage(0, 0, 0);
    }
}
