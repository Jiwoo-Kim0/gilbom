package com.gilbom.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FakeLlmClient implements LlmClient {

    private final List<LlmGenerationRequest> requests =
            new ArrayList<>();
    private LlmGenerationResponse response =
            new LlmGenerationResponse(
                    "{\"title\":\"fake\"}",
                    "fake-model",
                    "report-v1",
                    LlmUsage.unknown());
    private RuntimeException failure;
    private boolean available = true;

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public LlmGenerationResponse generate(
            LlmGenerationRequest request
    ) {
        requests.add(Objects.requireNonNull(request));
        if (failure != null) {
            throw failure;
        }
        return response;
    }

    public FakeLlmClient withResponse(
            LlmGenerationResponse value
    ) {
        response = Objects.requireNonNull(value);
        failure = null;
        return this;
    }

    public FakeLlmClient withFailure(
            RuntimeException value
    ) {
        failure = Objects.requireNonNull(value);
        return this;
    }

    public FakeLlmClient available(boolean value) {
        available = value;
        return this;
    }

    public List<LlmGenerationRequest> requests() {
        return List.copyOf(requests);
    }
}
