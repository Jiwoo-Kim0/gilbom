package com.gilbom.security.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gilbom.security.cors")
public class GilbomCorsProperties implements InitializingBean {

    private boolean enabled;
    private boolean allowCredentials = true;
    private List<String> allowedOrigins = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    public void setAllowCredentials(boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
    }

    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedOrigins);
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            allowedOrigins = new ArrayList<>();
            return;
        }

        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "CORS is enabled but no allowed origin is configured"
            );
        }

        Set<String> validatedOrigins = new LinkedHashSet<>();
        for (String origin : allowedOrigins) {
            validateAndAdd(origin, validatedOrigins);
        }
        allowedOrigins = new ArrayList<>(validatedOrigins);
    }

    private void validateAndAdd(String origin, Set<String> validatedOrigins) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException(
                    "CORS allowed origins must not contain a blank value"
            );
        }
        if (!origin.equals(origin.trim())) {
            throw new IllegalStateException(
                    "CORS allowed origins must not contain surrounding whitespace"
            );
        }
        if (origin.contains("*")) {
            throw new IllegalStateException(
                    "CORS wildcard origins are not allowed"
            );
        }

        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    "CORS allowed origin is not a valid URI: " + origin,
                    exception
            );
        }

        String scheme = uri.getScheme();
        boolean isHttpOrigin = scheme != null
                && Set.of("http", "https").contains(
                        scheme.toLowerCase(Locale.ROOT)
                );
        boolean hasOnlyOriginComponents = uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
        if (!isHttpOrigin || !hasOnlyOriginComponents) {
            throw new IllegalStateException(
                    "CORS allowed origin must be an exact http(s) origin: "
                            + origin
            );
        }

        if (!validatedOrigins.add(origin)) {
            throw new IllegalStateException(
                    "CORS allowed origins must not contain duplicates: " + origin
            );
        }
    }
}
