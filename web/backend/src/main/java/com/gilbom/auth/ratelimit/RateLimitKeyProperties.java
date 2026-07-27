package com.gilbom.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gilbom.security.rate-limit.key")
public class RateLimitKeyProperties {

    private int version = 1;
    private String base64 = "";
    private boolean externalRequired;

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getBase64() {
        return base64;
    }

    public void setBase64(String base64) {
        this.base64 = base64 == null ? "" : base64;
    }

    public boolean isExternalRequired() {
        return externalRequired;
    }

    public void setExternalRequired(boolean externalRequired) {
        this.externalRequired = externalRequired;
    }
}
