package com.gilbom.llm;

public interface LlmClient {

    boolean isAvailable();

    LlmGenerationResponse generate(
            LlmGenerationRequest request);
}
