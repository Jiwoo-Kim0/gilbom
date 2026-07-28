package com.gilbom.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class GilbomCorsPropertiesTest {

    @Test
    void disabledCorsKeepsCrossOriginApiClosedWithoutAnAllowlist() {
        GilbomCorsProperties properties = new GilbomCorsProperties();

        properties.afterPropertiesSet();

        CorsConfigurationSource source =
                new SecurityConfig().corsConfigurationSource(properties);
        assertThat(source.getCorsConfiguration(apiRequest())).isNull();
    }

    @Test
    void enabledCorsRequiresAtLeastOneExactOrigin() {
        GilbomCorsProperties properties = new GilbomCorsProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no allowed origin");
    }

    @Test
    void credentialedCorsRejectsWildcardOriginsAtStartup() {
        GilbomCorsProperties properties = new GilbomCorsProperties();
        properties.setEnabled(true);
        properties.setAllowCredentials(true);
        properties.setAllowedOrigins(List.of("*"));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void allowedOriginsMustNotContainAPathOrDuplicate() {
        GilbomCorsProperties pathProperties = new GilbomCorsProperties();
        pathProperties.setEnabled(true);
        pathProperties.setAllowedOrigins(List.of("https://admin.example.com/api"));

        assertThatThrownBy(pathProperties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact http(s) origin");

        GilbomCorsProperties duplicateProperties =
                new GilbomCorsProperties();
        duplicateProperties.setEnabled(true);
        duplicateProperties.setAllowedOrigins(List.of(
                "https://admin.example.com",
                "https://admin.example.com"
        ));

        assertThatThrownBy(duplicateProperties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void enabledCorsExposesOnlyTheConfiguredOriginAndApiContract() {
        GilbomCorsProperties properties = new GilbomCorsProperties();
        properties.setEnabled(true);
        properties.setAllowedOrigins(List.of(
                "https://admin.example.com",
                "http://localhost:5173"
        ));
        properties.afterPropertiesSet();

        CorsConfigurationSource source =
                new SecurityConfig().corsConfigurationSource(properties);
        CorsConfiguration configuration =
                source.getCorsConfiguration(apiRequest());

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.checkOrigin("https://admin.example.com"))
                .isEqualTo("https://admin.example.com");
        assertThat(configuration.checkOrigin("https://attacker.example.com"))
                .isNull();
        assertThat(configuration.getAllowedMethods()).containsExactly(
                "GET",
                "HEAD",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        );
        assertThat(configuration.getAllowedHeaders()).containsExactly(
                "Accept",
                "Content-Type",
                "X-CSRF-TOKEN",
                "Idempotency-Key",
                "X-Request-Id"
        );
    }

    private static MockHttpServletRequest apiRequest() {
        return new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
    }
}
