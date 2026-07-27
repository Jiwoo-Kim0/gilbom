package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.api.MqttCredentialIssuanceController;
import com.gilbom.robot.credential.MqttCredentialIssuanceService;
import com.gilbom.robot.credential.MqttCredentialProvisioner;
import com.gilbom.robot.credential.MqttCredentialProvisioningProperties;
import com.gilbom.robot.credential.MqttCredentialSecretGenerator;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = MqttCredentialIssuanceIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none",
                "mqtt.credential-provisioning.public-broker-uri="
                        + "ssl://125.137.58.131:8883"
        })
@AutoConfigureMockMvc
class MqttCredentialIssuanceIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID ADMIN_USER_ID =
            UUID.fromString("f854f421-f550-40c3-bbe7-6369a17bc994");
    private static final UUID ROBOT_ID =
            UUID.fromString("f59c163e-f080-40ee-81ca-94b9d8f235bf");
    private static final String ROBOT_CODE = "RBT-777";
    private static final SimpleGrantedAuthority ROBOT_REGISTER =
            new SimpleGrantedAuthority("ROBOT_REGISTER");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_mqtt_credential_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.default-schema", () -> "flyway_history");
        registry.add(
                "spring.flyway.placeholders.applicationRole",
                DATABASE::getUsername);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RecordingMqttCredentialProvisioner provisioner;

    @MockitoBean
    private SessionSecurityStateRepository sessionSecurityStateRepository;

    @BeforeEach
    void createRobotAndAuthenticatedAdminFixture() {
        provisioner.reset();
        jdbcTemplate.update(
                "DELETE FROM request_control.idempotency_request");
        jdbcTemplate.update(
                "DELETE FROM robot.robot_credential WHERE robot_id = ?",
                ROBOT_ID);
        jdbcTemplate.update(
                "DELETE FROM robot.robot WHERE robot_id = ?",
                ROBOT_ID);

        when(sessionSecurityStateRepository.findBySessionId(anyString()))
                .thenReturn(java.util.Optional.of(new SessionSecurityState(
                        ADMIN_USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        java.time.Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0
                )));

        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    password_hash,
                    display_name,
                    email,
                    is_active,
                    account_status,
                    password_change_required,
                    password_changed_at
                ) VALUES (?, ?, ?, ?, ?, TRUE, 'ACTIVE', FALSE, NOW())
                ON CONFLICT (user_id) DO NOTHING
                """,
                ADMIN_USER_ID,
                "USR-CREDENTIAL-INTEGRATION",
                passwordEncoder.encode("integration-only-admin-password"),
                "MQTT 자격증명 테스트 관리자",
                "mqtt-credential-integration@example.test");

        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    robot_code,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (?, ?, ?, ?, 'PROVISIONING', ?)
                ON CONFLICT (robot_id) DO NOTHING
                """,
                ROBOT_ID,
                ROBOT_CODE,
                "자격증명 발급 테스트 로봇",
                "SN-CREDENTIAL-777",
                ADMIN_USER_ID);
    }

    @Test
    void provisionsPersistsAndNeverStoresThePlaintextResponseSecret()
            throws Exception {
        UUID requestId =
                UUID.fromString("3623b449-af21-41cf-b136-8421d6e249a0");
        UUID replayRequestId =
                UUID.fromString("1970b771-5ac4-4682-a270-b991caf8ae07");
        UUID idempotencyKey =
                UUID.fromString("3126c1fb-ac84-44f1-af9e-923f65782295");

        String responseJson = mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, requestId)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath(
                        "$.mqtt.brokerUri",
                        is("ssl://125.137.58.131:8883")))
                .andExpect(jsonPath("$.mqtt.username", is(ROBOT_CODE)))
                .andExpect(jsonPath("$.mqtt.clientId", is(ROBOT_CODE)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseJson);
        String password = response.at("/mqtt/password").asText();
        UUID credentialId =
                UUID.fromString(response.get("credentialId").asText());

        assertThat(Base64.getUrlDecoder().decode(password)).hasSize(32);
        assertThat(provisioner.username).isEqualTo(ROBOT_CODE);
        assertThat(provisioner.password).isEqualTo(password);
        assertThat(provisioner.provisionCount).hasValue(1);
        assertThat(provisioner.removeCount).hasValue(0);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT
                    credential_type,
                    credential_version,
                    secret_hash,
                    secret_fingerprint,
                    credential_status,
                    issued_by
                FROM robot.robot_credential
                WHERE credential_id = ?
                """, credentialId))
                .containsEntry("credential_type", "MQTT_PASSWORD")
                .containsEntry("credential_version", 1)
                .containsEntry("secret_hash", provisioner.passwordHash)
                .containsEntry(
                        "secret_fingerprint",
                        sha256Hex(password))
                .containsEntry("credential_status", "ACTIVE")
                .containsEntry("issued_by", ADMIN_USER_ID)
                .doesNotContainValue(password);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT operational_status
                FROM robot.robot
                WHERE robot_id = ?
                """, String.class, ROBOT_ID))
                .isEqualTo("ACTIVE");

        String storedIdempotentBody = jdbcTemplate.queryForObject("""
                SELECT response_body::text
                FROM request_control.idempotency_request
                WHERE request_path = ?
                """,
                String.class,
                "/api/v1/robots/" + ROBOT_ID + "/credentials");
        assertThat(storedIdempotentBody)
                .contains("\"secretReplayable\": false")
                .doesNotContain(password)
                .doesNotContain(provisioner.passwordHash);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type = 'ROBOT_CREDENTIAL_ISSUED'
                  AND target_id = ?
                  AND result = 'SUCCESS'
                """, Integer.class, requestId, credentialId))
                .isEqualTo(1);

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, replayRequestId)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(
                        "$.code",
                        is("ROBOT_CREDENTIAL_SECRET_ALREADY_ISSUED")));

        assertThat(provisioner.provisionCount).hasValue(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_credential
                WHERE robot_id = ?
                """, Integer.class, ROBOT_ID))
                .isEqualTo(1);
    }

    @Test
    void removesProvisionedBrokerAccountWhenDatabaseTransactionRollsBack()
            throws Exception {
        // PostgreSQL rejects NUL in text after the external provisioner succeeds.
        provisioner.passwordHash = "\u0000invalid-verifier";

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(
                                RequestIdFilter.HEADER_NAME,
                                "88043dfa-673f-4c99-9028-0815a3cc53b9")
                        .header(
                                "Idempotency-Key",
                                "a4c945c4-d7eb-4a98-8e4d-fdc07211eea1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is("COMMON_INTERNAL_ERROR")));

        assertThat(provisioner.provisionCount).hasValue(1);
        assertThat(provisioner.removeCount).hasValue(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_credential
                WHERE robot_id = ?
                """, Integer.class, ROBOT_ID))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT operational_status
                FROM robot.robot
                WHERE robot_id = ?
                """, String.class, ROBOT_ID))
                .isEqualTo("PROVISIONING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM request_control.idempotency_request
                """, Integer.class))
                .isZero();
    }

    private String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(MqttCredentialProvisioningProperties.class)
    @Import({
            SecurityConfig.class,
            SecurityErrorResponseWriter.class,
            SecurityHeadersFilter.class,
            RequestIdFilter.class,
            RequestBodySizeLimitFilter.class,
            GlobalExceptionHandler.class,
            MqttCredentialIssuanceController.class,
            MqttCredentialIssuanceService.class,
            MqttCredentialSecretGenerator.class,
            AuthenticatedUserIdResolver.class,
            PasswordHashingConfiguration.class,
            ProvisionerTestConfiguration.class
    })
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProvisionerTestConfiguration {

        @Bean
        RecordingMqttCredentialProvisioner mqttCredentialProvisioner() {
            return new RecordingMqttCredentialProvisioner();
        }
    }

    static final class RecordingMqttCredentialProvisioner
            implements MqttCredentialProvisioner {

        private String passwordHash =
                "$7$101$integration-test-mosquitto-verifier";
        private final AtomicInteger provisionCount = new AtomicInteger();
        private final AtomicInteger removeCount = new AtomicInteger();
        private volatile String username;
        private volatile String password;

        @Override
        public ProvisionedMqttCredential provision(
                String provisionedUsername,
                String provisionedPassword
        ) {
            username = provisionedUsername;
            password = provisionedPassword;
            provisionCount.incrementAndGet();
            return new ProvisionedMqttCredential(passwordHash);
        }

        @Override
        public void remove(String removedUsername) {
            removeCount.incrementAndGet();
        }

        void reset() {
            passwordHash = "$7$101$integration-test-mosquitto-verifier";
            provisionCount.set(0);
            removeCount.set(0);
            username = null;
            password = null;
        }
    }
}
