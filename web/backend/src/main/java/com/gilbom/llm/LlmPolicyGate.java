package com.gilbom.llm;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LlmPolicyGate {

    public static final String GMS_PROVIDER = "GMS";
    public static final String GMS_BASE_URL =
            "https://gms.ssafy.io/gmsapi/api.openai.com/v1";
    public static final int MAXIMUM_OUTPUT_TOKENS = 1_500;
    public static final Set<String> ALLOWED_REPORT_MODELS =
            Set.of("gpt-5.4-mini", "gpt-5.4");
    public static final Set<String> ALLOWED_PROMPT_VERSIONS =
            Set.of("report-v1", "report-v2");

    public LlmPolicyDecision evaluate(LlmProperties properties) {
        if (!properties.isEnabled()) {
            return new LlmPolicyDecision(
                    false,
                    List.of(LlmPolicyViolation.FEATURE_DISABLED));
        }

        List<LlmPolicyViolation> violations =
                new ArrayList<>();
        if (properties.apiKeyForAuthorization().isBlank()) {
            violations.add(
                    LlmPolicyViolation.API_KEY_MISSING);
        }
        if (!GMS_PROVIDER.equals(normalizedUpper(
                properties.getProvider()))) {
            violations.add(
                    LlmPolicyViolation.PROVIDER_NOT_ALLOWED);
        }
        if (!isAllowedBaseUrl(properties.getBaseUrl())) {
            violations.add(
                    LlmPolicyViolation.BASE_URL_NOT_ALLOWED);
        }
        if (!ALLOWED_REPORT_MODELS.contains(
                properties.getModel().trim())) {
            violations.add(
                    LlmPolicyViolation.MODEL_NOT_ALLOWED);
        }
        if (!properties.isTrainingExclusionApproved()) {
            violations.add(
                    LlmPolicyViolation
                            .TRAINING_EXCLUSION_NOT_APPROVED);
        }
        if (!properties.isDataRetentionApproved()) {
            violations.add(
                    LlmPolicyViolation
                            .DATA_RETENTION_NOT_APPROVED);
        }
        if (properties.getDailyRequestLimit() <= 0) {
            violations.add(
                    LlmPolicyViolation
                            .DAILY_REQUEST_LIMIT_MISSING);
        }
        if (!isPositive(properties.getDailyBudget())) {
            violations.add(
                    LlmPolicyViolation.DAILY_BUDGET_MISSING);
        }
        if (!properties.isOutboundAllowlistApplied()) {
            violations.add(
                    LlmPolicyViolation
                            .OUTBOUND_ALLOWLIST_NOT_APPLIED);
        }
        String promptVersion =
                properties.getPromptVersion().trim();
        if (promptVersion.isEmpty()) {
            violations.add(
                    LlmPolicyViolation.PROMPT_VERSION_MISSING);
        } else if (!ALLOWED_PROMPT_VERSIONS.contains(
                promptVersion)) {
            violations.add(
                    LlmPolicyViolation
                            .PROMPT_VERSION_NOT_ALLOWED);
        }
        if (!properties.isPolicyApproved()) {
            violations.add(
                    LlmPolicyViolation.POLICY_NOT_APPROVED);
        }
        if (!validTimeout(
                properties.getConnectTimeout(),
                Duration.ofSeconds(10))
                || !validTimeout(
                        properties.getReadTimeout(),
                        Duration.ofSeconds(60))) {
            violations.add(
                    LlmPolicyViolation.TIMEOUT_INVALID);
        }
        if (properties.getMaxOutputTokens() < 1
                || properties.getMaxOutputTokens()
                        > MAXIMUM_OUTPUT_TOKENS) {
            violations.add(
                    LlmPolicyViolation
                            .MAX_OUTPUT_TOKENS_INVALID);
        }
        if (properties.getMaxConcurrentRequests() < 1
                || properties.getMaxConcurrentRequests() > 20) {
            violations.add(
                    LlmPolicyViolation
                            .MAX_CONCURRENT_REQUESTS_INVALID);
        }
        return new LlmPolicyDecision(
                violations.isEmpty(),
                violations);
    }

    public static boolean isAllowedBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null
                    ? ""
                    : value.trim());
            URI approved = URI.create(GMS_BASE_URL);
            int port = uri.getPort() == -1
                    ? 443
                    : uri.getPort();
            String normalizedPath = trimTrailingSlash(
                    uri.getRawPath());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "gms.ssafy.io".equalsIgnoreCase(
                            uri.getHost())
                    && port == 443
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && trimTrailingSlash(approved.getRawPath())
                            .equals(normalizedPath);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null
                && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private static boolean validTimeout(
            Duration value,
            Duration maximum
    ) {
        return value != null
                && !value.isNegative()
                && !value.isZero()
                && value.compareTo(maximum) <= 0;
    }

    private static String normalizedUpper(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
