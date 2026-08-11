package com.gilbom.llm;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 설정은 서버 환경에서만 바인딩한다. API key getter를 노출하지 않아
 * 일반 bean introspection과 관리 endpoint의 속성 응답에 원문이 나타나지 않게 한다.
 */
@ConfigurationProperties(prefix = "gilbom.llm")
public class LlmProperties {

    private boolean enabled;
    private String provider = "";
    private String baseUrl =
            "https://gms.ssafy.io/gmsapi/api.openai.com/v1";
    private String model = "";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(30);
    private int maxOutputTokens = 1_500;
    private int maxRequestBytes = 96 * 1024;
    private int dailyRequestLimit;
    private BigDecimal dailyBudget = BigDecimal.ZERO;
    private int maxConcurrentRequests = 5;
    private String promptVersion = "";
    private boolean policyApproved;
    private boolean trainingExclusionApproved;
    private boolean dataRetentionApproved;
    private boolean outboundAllowlistApplied;
    private boolean imageInputEnabled;
    private boolean imageDeidentificationApproved;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = valueOrEmpty(provider);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = valueOrEmpty(baseUrl);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = valueOrEmpty(model);
    }

    public void setApiKey(String apiKey) {
        this.apiKey = valueOrEmpty(apiKey);
    }

    String apiKeyForAuthorization() {
        return apiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(int maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public int getDailyRequestLimit() {
        return dailyRequestLimit;
    }

    public void setDailyRequestLimit(int dailyRequestLimit) {
        this.dailyRequestLimit = dailyRequestLimit;
    }

    public BigDecimal getDailyBudget() {
        return dailyBudget;
    }

    public void setDailyBudget(BigDecimal dailyBudget) {
        this.dailyBudget = dailyBudget;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(
            int maxConcurrentRequests
    ) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = valueOrEmpty(promptVersion);
    }

    public boolean isPolicyApproved() {
        return policyApproved;
    }

    public void setPolicyApproved(boolean policyApproved) {
        this.policyApproved = policyApproved;
    }

    public boolean isTrainingExclusionApproved() {
        return trainingExclusionApproved;
    }

    public void setTrainingExclusionApproved(
            boolean trainingExclusionApproved
    ) {
        this.trainingExclusionApproved =
                trainingExclusionApproved;
    }

    public boolean isDataRetentionApproved() {
        return dataRetentionApproved;
    }

    public void setDataRetentionApproved(
            boolean dataRetentionApproved
    ) {
        this.dataRetentionApproved = dataRetentionApproved;
    }

    public boolean isOutboundAllowlistApplied() {
        return outboundAllowlistApplied;
    }

    public void setOutboundAllowlistApplied(
            boolean outboundAllowlistApplied
    ) {
        this.outboundAllowlistApplied =
                outboundAllowlistApplied;
    }

    public boolean isImageInputEnabled() {
        return imageInputEnabled;
    }

    public void setImageInputEnabled(
            boolean imageInputEnabled
    ) {
        this.imageInputEnabled = imageInputEnabled;
    }

    public boolean isImageDeidentificationApproved() {
        return imageDeidentificationApproved;
    }

    public void setImageDeidentificationApproved(
            boolean imageDeidentificationApproved
    ) {
        this.imageDeidentificationApproved =
                imageDeidentificationApproved;
    }

    public boolean isImageInputAllowed() {
        return imageInputEnabled
                && imageDeidentificationApproved;
    }

    @Override
    public String toString() {
        return "LlmProperties{"
                + "enabled=" + enabled
                + ", provider='" + provider + '\''
                + ", baseUrl='" + baseUrl + '\''
                + ", model='" + model + '\''
                + ", apiKey='[REDACTED]'"
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", maxOutputTokens=" + maxOutputTokens
                + ", maxRequestBytes=" + maxRequestBytes
                + ", dailyRequestLimit=" + dailyRequestLimit
                + ", dailyBudget=" + dailyBudget
                + ", maxConcurrentRequests="
                + maxConcurrentRequests
                + ", promptVersion='" + promptVersion + '\''
                + ", policyApproved=" + policyApproved
                + ", trainingExclusionApproved="
                + trainingExclusionApproved
                + ", dataRetentionApproved="
                + dataRetentionApproved
                + ", outboundAllowlistApplied="
                + outboundAllowlistApplied
                + ", imageInputEnabled=" + imageInputEnabled
                + ", imageDeidentificationApproved="
                + imageDeidentificationApproved
                + '}';
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
