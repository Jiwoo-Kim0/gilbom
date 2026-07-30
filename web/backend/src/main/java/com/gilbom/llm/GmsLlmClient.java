package com.gilbom.llm;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

public final class GmsLlmClient implements LlmClient {

    private static final int MAXIMUM_INPUT_JSON_CHARACTERS =
            LlmOutboundInputGuard.MAXIMUM_JSON_BYTES;
    private static final int MAXIMUM_OUTPUT_CHARACTERS =
            100_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int maxOutputTokens;
    private final String promptVersion;
    private final String systemPrompt;
    private final boolean imageInputAllowed;
    private final LlmOutboundInputGuard outboundInputGuard;

    GmsLlmClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            LlmProperties properties,
            LlmPromptCatalog promptCatalog
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        Objects.requireNonNull(properties);
        Objects.requireNonNull(promptCatalog);
        if (!LlmPolicyGate.isAllowedBaseUrl(
                properties.getBaseUrl())
                || !LlmPolicyGate.ALLOWED_REPORT_MODELS
                        .contains(properties.getModel().trim())
                || properties.apiKeyForAuthorization()
                        .isBlank()) {
            throw new IllegalArgumentException(
                    "GMS client requires approved server settings");
        }
        apiKey = properties.apiKeyForAuthorization();
        model = properties.getModel().trim();
        maxOutputTokens = properties.getMaxOutputTokens();
        promptVersion =
                properties.getPromptVersion().trim();
        imageInputAllowed = properties.isImageInputAllowed();
        outboundInputGuard =
                new LlmOutboundInputGuard(objectMapper);
        systemPrompt = promptCatalog.systemPrompt(
                promptVersion)
                + "\nThe response fields promptVersion and "
                + "modelIdentifier must be exactly \""
                + promptVersion
                + "\" and \""
                + model
                + "\".";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public LlmGenerationResponse generate(
            LlmGenerationRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        List<LlmImageContent> images = imageInputAllowed
                ? request.images()
                : List.of();
        String inputJson = serializeInput(request, images);
        GmsChatResponse response;
        try {
            response = restClient.post()
                    .uri("chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + apiKey)
                    .body(new GmsChatRequest(
                            model,
                            List.of(
                                    new GmsMessage(
                                            "system",
                                            systemPrompt),
                                    new GmsMessage(
                                            "user",
                                            userContent(
                                                    inputJson,
                                                    images))),
                            maxOutputTokens,
                            false,
                            Map.of("type", "json_object")))
                    .retrieve()
                    .body(GmsChatResponse.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 429) {
                throw new LlmTransportException(
                        LlmTransportException.FailureKind
                                .RATE_LIMITED);
            }
            if (status >= 500) {
                throw new LlmTransportException(
                        LlmTransportException.FailureKind
                                .PROVIDER_5XX);
            }
            throw new LlmTransportException(
                    LlmTransportException.FailureKind
                            .PROVIDER_4XX);
        } catch (ResourceAccessException exception) {
            throw new LlmTransportException(
                    LlmTransportException.FailureKind
                            .NETWORK_TIMEOUT);
        } catch (RestClientException exception) {
            // Provider body, URL, prompt와 Authorization을 cause로
            // 보존하지 않는다.
            throw new LlmTransportException(
                    LlmTransportException.FailureKind
                            .CLIENT_FAILURE);
        }
        return validated(response);
    }

    private String serializeInput(
            LlmGenerationRequest request,
            List<LlmImageContent> images
    ) {
        outboundInputGuard.validate(request.input());
        try {
            Map<String, Object> promptInput =
                    new LinkedHashMap<>(request.input());
            if (!images.isEmpty()) {
                if (promptInput.containsKey("imageEvidence")) {
                    throw new BusinessException(
                            ErrorCode.REPORT_VALIDATION_FAILED);
                }
                List<ImageEvidenceMetadata> metadata =
                        new ArrayList<>();
                for (int index = 0;
                        index < images.size();
                        index++) {
                    LlmImageContent image = images.get(index);
                    metadata.add(new ImageEvidenceMetadata(
                            index + 1,
                            image.defectId(),
                            image.incidentCode(),
                            image.contentType(),
                            image.width(),
                            image.height()));
                }
                promptInput.put("imageEvidence", metadata);
            }
            String json = objectMapper.writeValueAsString(
                    promptInput);
            if (json.length()
                    > MAXIMUM_INPUT_JSON_CHARACTERS) {
                throw new BusinessException(
                        ErrorCode.REPORT_VALIDATION_FAILED);
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
    }

    private static Object userContent(
            String inputJson,
            List<LlmImageContent> images
    ) {
        if (images.isEmpty()) {
            return inputJson;
        }
        List<Object> content = new ArrayList<>();
        content.add(new GmsTextContent("text", inputJson));
        for (LlmImageContent image : images) {
            String dataUrl = "data:"
                    + image.contentType()
                    + ";base64,"
                    + Base64.getEncoder().encodeToString(
                            image.bytes());
            content.add(new GmsImageContent(
                    "image_url",
                    new GmsImageUrl(dataUrl, "auto")));
        }
        return List.copyOf(content);
    }

    private LlmGenerationResponse validated(
            GmsChatResponse response
    ) {
        if (response == null
                || !model.equals(response.model())
                || response.choices() == null
                || response.choices().size() != 1
                || response.choices().getFirst() == null
                || response.choices().getFirst().message() == null
                || response.choices().getFirst().message()
                        .content() == null
                || response.choices().getFirst().message()
                        .content().isBlank()) {
            throw new BusinessException(
                    ErrorCode.LLM_RESPONSE_INVALID);
        }
        String content = response.choices().getFirst().message()
                .content();
        if (content.length() > MAXIMUM_OUTPUT_CHARACTERS) {
            throw new BusinessException(
                    ErrorCode.LLM_RESPONSE_INVALID);
        }
        validateJsonObject(content);
        return new LlmGenerationResponse(
                content,
                model,
                promptVersion,
                usage(response.usage()));
    }

    private void validateJsonObject(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node == null || !node.isObject()) {
                throw new BusinessException(
                        ErrorCode.LLM_RESPONSE_INVALID);
            }
        } catch (JsonProcessingException exception) {
            // 응답 원문과 parser 예외 메시지를 API 예외에 보존하지 않는다.
            throw new BusinessException(
                    ErrorCode.LLM_RESPONSE_INVALID);
        }
    }

    private static LlmUsage usage(GmsUsage usage) {
        if (usage == null) {
            return LlmUsage.unknown();
        }
        try {
            return new LlmUsage(
                    nonNegative(usage.promptTokens()),
                    nonNegative(usage.completionTokens()),
                    nonNegative(usage.totalTokens()));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.LLM_RESPONSE_INVALID);
        }
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : value;
    }

    @Override
    public String toString() {
        return "GmsLlmClient{"
                + "model='" + model + '\''
                + ", maxOutputTokens=" + maxOutputTokens
                + ", promptVersion='" + promptVersion + '\''
                + ", imageInputAllowed=" + imageInputAllowed
                + ", apiKey='[REDACTED]'}";
    }

    private record GmsChatRequest(
            String model,
            List<GmsMessage> messages,
            @JsonProperty("max_completion_tokens")
            int maxCompletionTokens,
            boolean store,
            @JsonProperty("response_format")
            Map<String, String> responseFormat
    ) {

        @Override
        public String toString() {
            return "GmsChatRequest{"
                    + "model='" + model + '\''
                    + ", messages=[REDACTED]"
                    + ", maxCompletionTokens="
                    + maxCompletionTokens
                    + ", store=" + store
                    + ", responseFormat=" + responseFormat
                    + '}';
        }
    }

    private record GmsMessage(
            String role,
            Object content
    ) {

        @Override
        public String toString() {
            return "GmsMessage{role='" + role
                    + "', content=[REDACTED]}";
        }
    }

    private record GmsTextContent(
            String type,
            String text
    ) {

        @Override
        public String toString() {
            return "GmsTextContent{type='" + type
                    + "', text=[REDACTED]}";
        }
    }

    private record GmsImageContent(
            String type,
            @JsonProperty("image_url")
            GmsImageUrl imageUrl
    ) {
    }

    private record GmsImageUrl(
            String url,
            String detail
    ) {

        @Override
        public String toString() {
            return "GmsImageUrl{url=[REDACTED], detail='"
                    + detail + "'}";
        }
    }

    private record ImageEvidenceMetadata(
            int imageIndex,
            java.util.UUID defectId,
            String incidentCode,
            String contentType,
            int width,
            int height
    ) {
    }

    private record GmsChatResponse(
            String model,
            List<GmsChoice> choices,
            GmsUsage usage
    ) {
    }

    private record GmsChoice(
            GmsResponseMessage message
    ) {
    }

    private record GmsResponseMessage(
            String role,
            String content
    ) {
    }

    private record GmsUsage(
            @JsonProperty("prompt_tokens")
            Integer promptTokens,
            @JsonProperty("completion_tokens")
            Integer completionTokens,
            @JsonProperty("total_tokens")
            Integer totalTokens
    ) {
    }
}
