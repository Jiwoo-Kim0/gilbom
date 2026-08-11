package com.gilbom.llm;

import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(
            GmsLlmClient.class);

    private static final int MAXIMUM_INPUT_JSON_CHARACTERS =
            LlmOutboundInputGuard.MAXIMUM_JSON_BYTES;
    private static final int MAXIMUM_OUTPUT_CHARACTERS =
            100_000;
    private static final String REPORT_SCHEMA_RESOURCE =
            "/llm/report-structured-output.schema.json";
    private static final String REPORT_SCHEMA_NAME =
            "gilbom_report_v1";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int maxOutputTokens;
    private final int maxRequestBytes;
    private final String promptVersion;
    private final String systemPrompt;
    private final boolean imageInputAllowed;
    private final LlmOutboundInputGuard outboundInputGuard;
    private final JsonNode reportOutputSchema;

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
        maxRequestBytes = properties.getMaxRequestBytes();
        promptVersion =
                properties.getPromptVersion().trim();
        imageInputAllowed = properties.isImageInputAllowed();
        outboundInputGuard =
                new LlmOutboundInputGuard(objectMapper);
        reportOutputSchema = loadReportOutputSchema(
                objectMapper);
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
        SerializedGmsRequest serialized = serializeRequest(
                request,
                images);
        if (serialized.body().length > maxRequestBytes
                && !images.isEmpty()) {
            LOGGER.warn(
                    "LLM image evidence omitted before provider call: "
                            + "reason=REQUEST_TOO_LARGE, requestBytes={}, "
                            + "maximumBytes={}, imageCount={}",
                    serialized.body().length,
                    maxRequestBytes,
                    images.size());
            images = List.of();
            serialized = serializeRequest(request, images);
        }
        if (serialized.body().length > maxRequestBytes) {
            throw new LlmTransportException(
                    LlmTransportException.FailureKind
                            .REQUEST_TOO_LARGE);
        }
        return execute(serialized);
    }

    private LlmGenerationResponse execute(
            SerializedGmsRequest request
    ) {
        GmsChatResponse response;
        try {
            response = restClient.post()
                    .uri("chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + apiKey)
                    .body(request.body())
                    .retrieve()
                    .body(GmsChatResponse.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            LlmTransportException.FailureKind failureKind;
            if (status == 429) {
                failureKind = LlmTransportException.FailureKind
                        .RATE_LIMITED;
            } else if (status >= 500) {
                failureKind = LlmTransportException.FailureKind
                        .PROVIDER_5XX;
            } else {
                failureKind = LlmTransportException.FailureKind
                        .PROVIDER_4XX;
            }
            LOGGER.warn(
                    "LLM provider request rejected: failureKind={}, "
                            + "providerStatus={}, requestBytes={}, "
                            + "imageCount={}",
                    failureKind,
                    status,
                    request.body().length,
                    request.imageCount());
            throw new LlmTransportException(
                    failureKind,
                    status);
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

    private SerializedGmsRequest serializeRequest(
            LlmGenerationRequest request,
            List<LlmImageContent> images
    ) {
        String inputJson = serializeInput(request, images);
        GmsChatRequest providerRequest = new GmsChatRequest(
                model,
                List.of(
                        new GmsMessage(
                                "system",
                                systemPrompt),
                        new GmsMessage(
                                "user",
                                userContent(inputJson, images))),
                maxOutputTokens,
                false,
                new GmsResponseFormat(
                        "json_schema",
                        new GmsJsonSchema(
                                REPORT_SCHEMA_NAME,
                                true,
                                reportOutputSchema)));
        try {
            return new SerializedGmsRequest(
                    objectMapper.writeValueAsBytes(providerRequest),
                    images.size());
        } catch (JsonProcessingException exception) {
            throw new LlmTransportException(
                    LlmTransportException.FailureKind
                            .CLIENT_FAILURE);
        }
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
                || !isExpectedProviderModel(response.model())
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

    private boolean isExpectedProviderModel(
            String providerModel
    ) {
        if (model.equals(providerModel)) {
            return true;
        }
        if (providerModel == null) {
            return false;
        }
        String datedSnapshotPrefix = model + "-";
        if (!providerModel.startsWith(
                datedSnapshotPrefix)) {
            return false;
        }
        try {
            LocalDate.parse(providerModel.substring(
                    datedSnapshotPrefix.length()));
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private static JsonNode loadReportOutputSchema(
            ObjectMapper objectMapper
    ) {
        try (InputStream input = GmsLlmClient.class
                .getResourceAsStream(
                        REPORT_SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "LLM report output schema is missing");
            }
            JsonNode schema = objectMapper.readTree(input);
            if (schema == null || !schema.isObject()) {
                throw new IllegalStateException(
                        "LLM report output schema is invalid");
            }
            return schema;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "LLM report output schema cannot be read",
                    exception);
        }
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
                + ", maxRequestBytes=" + maxRequestBytes
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
            GmsResponseFormat responseFormat
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

    private record SerializedGmsRequest(
            byte[] body,
            int imageCount
    ) {

        @Override
        public String toString() {
            return "SerializedGmsRequest{body=[REDACTED], "
                    + "requestBytes=" + body.length
                    + ", imageCount=" + imageCount + '}';
        }
    }

    private record GmsResponseFormat(
            String type,
            @JsonProperty("json_schema")
            GmsJsonSchema jsonSchema
    ) {

        @Override
        public String toString() {
            return "GmsResponseFormat{type='" + type
                    + "', jsonSchema=" + jsonSchema + '}';
        }
    }

    private record GmsJsonSchema(
            String name,
            boolean strict,
            JsonNode schema
    ) {

        @Override
        public String toString() {
            return "GmsJsonSchema{name='" + name
                    + "', strict=" + strict
                    + ", schema=[REDACTED]}";
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
