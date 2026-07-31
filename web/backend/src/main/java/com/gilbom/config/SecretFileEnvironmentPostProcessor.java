package com.gilbom.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Docker Secret 파일을 Spring property로 변환한다.
 *
 * <p>Secret 원문을 image의 ARG/ENV나 Compose environment 값에 넣지 않고
 * {@code /run/secrets}에 runtime mount한 파일에서만 읽는다. 임의 파일 읽기를 막기
 * 위해 지원하는 Secret과 property mapping을 allowlist로 고정한다.
 */
public final class SecretFileEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "dockerSecretFiles";
    static final int MAX_SECRET_BYTES = 16 * 1024;

    private final List<SecretFileSpec> secretFileSpecs;

    public SecretFileEnvironmentPostProcessor() {
        this(defaultSecretFileSpecs());
    }

    SecretFileEnvironmentPostProcessor(
            List<SecretFileSpec> secretFileSpecs
    ) {
        this.secretFileSpecs = List.copyOf(secretFileSpecs);
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (SecretFileSpec spec : secretFileSpecs) {
            Path secretFile = resolveSecretFile(environment, spec);
            if (secretFile == null) {
                continue;
            }
            if (StringUtils.hasText(
                    environment.getProperty(spec.directProperty()))) {
                throw new IllegalStateException(
                        "Set only the Secret file property for "
                                + spec.targetProperty());
            }
            properties.put(
                    spec.targetProperty(),
                    readSecret(secretFile, spec.targetProperty()));
        }

        if (!properties.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource(
                            PROPERTY_SOURCE_NAME,
                            properties));
        }
    }

    @Override
    public int getOrder() {
        // application.yml과 spring-dotenv를 포함한 file path 설정을 모두 읽은 뒤
        // Secret 값을 가장 높은 property precedence로 추가한다.
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static Path resolveSecretFile(
            ConfigurableEnvironment environment,
            SecretFileSpec spec
    ) {
        String configuredPath =
                environment.getProperty(spec.fileProperty());
        if (StringUtils.hasText(configuredPath)) {
            Path path = Path.of(configuredPath);
            if (!path.isAbsolute()) {
                throw invalidSecretFile(spec.targetProperty());
            }
            return path.normalize();
        }

        if (Files.exists(spec.defaultPath())) {
            return spec.defaultPath();
        }
        return null;
    }

    private static String readSecret(
            Path secretFile,
            String targetProperty
    ) {
        try {
            if (!Files.isRegularFile(secretFile)
                    || !Files.isReadable(secretFile)) {
                throw invalidSecretFile(targetProperty);
            }
            byte[] bytes = Files.readAllBytes(secretFile);
            if (bytes.length > MAX_SECRET_BYTES) {
                throw invalidSecretFile(targetProperty);
            }

            String value = decodeUtf8(bytes, targetProperty);
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
            } else if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\0') >= 0) {
                throw invalidSecretFile(targetProperty);
            }
            return value;
        } catch (IOException exception) {
            throw invalidSecretFile(targetProperty);
        }
    }

    private static String decodeUtf8(
            byte[] bytes,
            String targetProperty
    ) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalidSecretFile(targetProperty);
        }
    }

    private static IllegalStateException invalidSecretFile(
            String targetProperty
    ) {
        return new IllegalStateException(
                "Secret file is invalid or unreadable for property "
                        + targetProperty);
    }

    static List<SecretFileSpec> defaultSecretFileSpecs() {
        return List.of(
                new SecretFileSpec(
                        "gilbom.llm.api-key",
                        "GILBOM_LLM_API_KEY_FILE",
                        Path.of("/run/secrets/gilbom_llm_api_key")),
                new SecretFileSpec(
                        "gilbom.storage.url-signing-key-base64",
                        "GILBOM_STORAGE_URL_SIGNING_KEY_BASE64_FILE",
                        Path.of(
                                "/run/secrets/"
                                        + "gilbom_storage_signing_key")),
                new SecretFileSpec(
                        "gilbom.security.rate-limit.key.base64",
                        "AUTH_RATE_LIMIT_HMAC_KEY_BASE64_FILE",
                        Path.of(
                                "/run/secrets/"
                                        + "gilbom_auth_rate_limit_hmac_key")),
                new SecretFileSpec(
                        "gilbom.security.mfa.totp-secret-encryption.keyring",
                        "MFA_TOTP_SECRET_KEYRING_FILE",
                        Path.of(
                                "/run/secrets/"
                                        + "gilbom_mfa_totp_keyring")),
                new SecretFileSpec(
                        "gilbom.security.initial-admin.password",
                        "INITIAL_ADMIN_PASSWORD_FILE",
                        Path.of(
                                "/run/secrets/"
                                        + "gilbom_initial_admin_password")),
                new SecretFileSpec(
                        "spring.datasource.password",
                        "SPRING_DATASOURCE_PASSWORD_FILE",
                        Path.of("/run/secrets/postgres_app_password")),
                new SecretFileSpec(
                        "spring.flyway.password",
                        "SPRING_FLYWAY_PASSWORD_FILE",
                        Path.of("/run/secrets/postgres_flyway_password")),
                new SecretFileSpec(
                        "mqtt.password",
                        "MQTT_PASSWORD_FILE",
                        Path.of("/run/secrets/mqtt_backend_password")));
    }

    record SecretFileSpec(
            String targetProperty,
            String fileProperty,
            Path defaultPath
    ) {
        String directProperty() {
            return fileProperty.substring(
                    0,
                    fileProperty.length() - "_FILE".length());
        }
    }
}
