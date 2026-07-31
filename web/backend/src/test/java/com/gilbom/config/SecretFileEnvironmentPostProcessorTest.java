package com.gilbom.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.mock.env.MockEnvironment;

class SecretFileEnvironmentPostProcessorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsAllowlistedSecretFiles()
            throws IOException {
        Path llmSecret = writeSecret(
                "llm-api-key",
                "test-only-llm-secret\n");
        Path signingSecret = writeSecret(
                "storage-signing-key",
                "test-only-signing-key\r\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "GILBOM_LLM_API_KEY_FILE",
                        llmSecret.toString())
                .withProperty(
                        "GILBOM_STORAGE_URL_SIGNING_KEY_BASE64_FILE",
                        signingSecret.toString());

        new SecretFileEnvironmentPostProcessor()
                .postProcessEnvironment(
                        environment,
                        new SpringApplication());

        assertThat(environment.getProperty("gilbom.llm.api-key"))
                .isEqualTo("test-only-llm-secret");
        assertThat(environment.getProperty(
                "gilbom.storage.url-signing-key-base64"))
                .isEqualTo("test-only-signing-key");
        assertThat(environment.getPropertySources().get(
                SecretFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isNotNull();
    }

    @Test
    void rejectsDirectSecretTogetherWithFile() throws IOException {
        Path llmSecret = writeSecret(
                "llm-api-key",
                "test-only-file-secret");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "GILBOM_LLM_API_KEY_FILE",
                        llmSecret.toString())
                .withProperty(
                        "GILBOM_LLM_API_KEY",
                        "test-only-direct-secret");

        assertThatThrownBy(
                () -> new SecretFileEnvironmentPostProcessor()
                        .postProcessEnvironment(
                                environment,
                                new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gilbom.llm.api-key")
                .hasMessageNotContaining("test-only-direct-secret")
                .hasMessageNotContaining("test-only-file-secret");
    }

    @Test
    void loadsDefaultDockerSecretPathWhenItExists()
            throws IOException {
        Path defaultPath = writeSecret(
                "postgres-app-password",
                "test-only-database-password");
        SecretFileEnvironmentPostProcessor.SecretFileSpec spec =
                new SecretFileEnvironmentPostProcessor.SecretFileSpec(
                        "spring.datasource.password",
                        "SPRING_DATASOURCE_PASSWORD_FILE",
                        defaultPath);
        MockEnvironment environment = new MockEnvironment();

        new SecretFileEnvironmentPostProcessor(List.of(spec))
                .postProcessEnvironment(
                        environment,
                        new SpringApplication());

        assertThat(environment.getProperty(
                "spring.datasource.password"))
                .isEqualTo("test-only-database-password");
    }

    @Test
    void ignoresAbsentOptionalDefaultSecretFile() {
        Path absent = temporaryDirectory.resolve("absent-secret");
        SecretFileEnvironmentPostProcessor.SecretFileSpec spec =
                new SecretFileEnvironmentPostProcessor.SecretFileSpec(
                        "gilbom.llm.api-key",
                        "GILBOM_LLM_API_KEY_FILE",
                        absent);
        MockEnvironment environment = new MockEnvironment();

        new SecretFileEnvironmentPostProcessor(List.of(spec))
                .postProcessEnvironment(
                        environment,
                        new SpringApplication());

        assertThat(environment.getPropertySources().get(
                SecretFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isNull();
    }

    @Test
    void treatsEmptyLlmSecretAsUnsetOnlyWhenLlmIsDisabled()
            throws IOException {
        Path emptyLlmSecret = writeSecret(
                "empty-llm-api-key",
                "");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("gilbom.llm.enabled", "false")
                .withProperty(
                        "GILBOM_LLM_API_KEY_FILE",
                        emptyLlmSecret.toString());

        new SecretFileEnvironmentPostProcessor()
                .postProcessEnvironment(
                        environment,
                        new SpringApplication());

        assertThat(environment.getProperty("gilbom.llm.api-key"))
                .isNull();
        assertThat(environment.getPropertySources().get(
                SecretFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isNull();
    }

    @Test
    void rejectsEmptyLlmSecretWhenLlmIsEnabled()
            throws IOException {
        Path emptyLlmSecret = writeSecret(
                "empty-enabled-llm-api-key",
                "");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("gilbom.llm.enabled", "true")
                .withProperty(
                        "GILBOM_LLM_API_KEY_FILE",
                        emptyLlmSecret.toString());

        assertThatThrownBy(
                () -> new SecretFileEnvironmentPostProcessor()
                        .postProcessEnvironment(
                                environment,
                                new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gilbom.llm.api-key");
    }

    @Test
    void rejectsEmptyRequiredSecretEvenWhenLlmIsDisabled()
            throws IOException {
        Path emptyDatabaseSecret = writeSecret(
                "empty-database-password",
                "");
        SecretFileEnvironmentPostProcessor.SecretFileSpec spec =
                new SecretFileEnvironmentPostProcessor.SecretFileSpec(
                        "spring.datasource.password",
                        "SPRING_DATASOURCE_PASSWORD_FILE",
                        emptyDatabaseSecret);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("gilbom.llm.enabled", "false");

        assertThatThrownBy(
                () -> new SecretFileEnvironmentPostProcessor(
                        List.of(spec))
                        .postProcessEnvironment(
                                environment,
                                new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "spring.datasource.password");
    }

    @Test
    void treatsEmptyInitialAdminSecretAsUnsetOnlyWhenBootstrapIsDisabled()
            throws IOException {
        Path emptyInitialPassword = writeSecret(
                "empty-initial-admin-password",
                "");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "gilbom.security.initial-admin.enabled",
                        "false")
                .withProperty(
                        "INITIAL_ADMIN_PASSWORD_FILE",
                        emptyInitialPassword.toString());

        new SecretFileEnvironmentPostProcessor()
                .postProcessEnvironment(
                        environment,
                        new SpringApplication());

        assertThat(environment.getProperty(
                "gilbom.security.initial-admin.password"))
                .isNull();
    }

    @Test
    void rejectsEmptyInitialAdminSecretWhenBootstrapIsEnabled()
            throws IOException {
        Path emptyInitialPassword = writeSecret(
                "empty-enabled-initial-admin-password",
                "");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "gilbom.security.initial-admin.enabled",
                        "true")
                .withProperty(
                        "INITIAL_ADMIN_PASSWORD_FILE",
                        emptyInitialPassword.toString());

        assertThatThrownBy(
                () -> new SecretFileEnvironmentPostProcessor()
                        .postProcessEnvironment(
                                environment,
                                new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "gilbom.security.initial-admin.password");
    }

    @Test
    void rejectsRelativeExplicitPath() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "GILBOM_LLM_API_KEY_FILE",
                        "relative/secret");

        assertThatThrownBy(
                () -> new SecretFileEnvironmentPostProcessor()
                        .postProcessEnvironment(
                                environment,
                                new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gilbom.llm.api-key")
                .hasMessageNotContaining("relative/secret");
    }

    @Test
    void rejectsMultilineAndOversizedSecretWithoutLeakingContent()
            throws IOException {
        Path multiline = writeSecret(
                "multiline-secret",
                "first-secret-line\nsecond-secret-line\n");
        MockEnvironment multilineEnvironment = new MockEnvironment()
                .withProperty(
                        "GILBOM_LLM_API_KEY_FILE",
                        multiline.toString());

        assertThatThrownBy(
                () -> new SecretFileEnvironmentPostProcessor()
                        .postProcessEnvironment(
                                multilineEnvironment,
                                new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("first-secret-line")
                .hasMessageNotContaining("second-secret-line");

        Path oversized = temporaryDirectory.resolve("oversized-secret");
        Files.write(
                oversized,
                new byte[
                        SecretFileEnvironmentPostProcessor
                                .MAX_SECRET_BYTES + 1]);
        MockEnvironment oversizedEnvironment = new MockEnvironment()
                .withProperty(
                        "GILBOM_LLM_API_KEY_FILE",
                        oversized.toString());

        assertThatThrownBy(
                () -> new SecretFileEnvironmentPostProcessor()
                        .postProcessEnvironment(
                                oversizedEnvironment,
                                new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gilbom.llm.api-key");
    }

    @Test
    void registersThroughSpringFactories() throws IOException {
        Properties factories = new Properties();
        try (InputStream resource = getClass()
                .getClassLoader()
                .getResourceAsStream("META-INF/spring.factories")) {
            assertThat(resource).isNotNull();
            factories.load(resource);
        }

        assertThat(factories.getProperty(
                EnvironmentPostProcessor.class.getName()))
                .contains(
                        SecretFileEnvironmentPostProcessor.class.getName());
    }

    @Test
    void fixesRequiredDockerSecretNamesAndPaths() {
        assertThat(
                SecretFileEnvironmentPostProcessor
                        .defaultSecretFileSpecs())
                .extracting(
                        SecretFileEnvironmentPostProcessor
                                .SecretFileSpec::defaultPath)
                .contains(
                        Path.of(
                                "/run/secrets/gilbom_llm_api_key"),
                        Path.of(
                                "/run/secrets/"
                                        + "gilbom_storage_signing_key"));
    }

    private Path writeSecret(
            String fileName,
            String content
    ) throws IOException {
        Path secret = temporaryDirectory.resolve(fileName);
        return Files.writeString(secret, content);
    }
}
