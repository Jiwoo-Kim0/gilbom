package com.gilbom.llm;

public record LlmGenerationResponse(
        String content,
        String modelIdentifier,
        String promptVersion,
        LlmUsage usage
) {

    public LlmGenerationResponse {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "LLM content must not be blank");
        }
        if (modelIdentifier == null
                || modelIdentifier.isBlank()
                || promptVersion == null
                || promptVersion.isBlank()
                || usage == null) {
            throw new IllegalArgumentException(
                    "LLM response metadata is required");
        }
    }

    @Override
    public String toString() {
        return "LlmGenerationResponse{"
                + "content=[REDACTED]"
                + ", modelIdentifier='" + modelIdentifier + '\''
                + ", promptVersion='" + promptVersion + '\''
                + ", usage=" + usage
                + '}';
    }
}
