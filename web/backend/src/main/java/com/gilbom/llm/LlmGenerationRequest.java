package com.gilbom.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LlmGenerationRequest(
        Map<String, Object> input,
        List<LlmImageContent> images
) {

    public static final int MAXIMUM_IMAGES = 3;

    public LlmGenerationRequest {
        if (input == null) {
            throw new IllegalArgumentException(
                    "LLM input must be a JSON object");
        }
        input = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(input));
        images = images == null
                ? List.of()
                : List.copyOf(images);
        if (images.size() > MAXIMUM_IMAGES
                || images.stream().anyMatch(
                        java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "LLM images must contain at most three items");
        }
    }

    public LlmGenerationRequest(Map<String, Object> input) {
        this(input, List.of());
    }

    @Override
    public String toString() {
        return "LlmGenerationRequest{"
                + "input=[REDACTED]"
                + ", imageCount=" + images.size()
                + '}';
    }
}
