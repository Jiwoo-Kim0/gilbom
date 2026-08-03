package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.http.MediaType;
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
import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.BestEffortAuditLogWriter;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.api.MqttCredentialIssuanceController;
import com.gilbom.robot.credential.MqttCredentialIssuanceService;
import com.gilbom.robot.credential.MqttCredentialProvisioner;
import com.gilbom.robot.credential.MqttCredentialProvisioningProperties;
import com.gilbom.robot.credential.MqttCredentialRegistryService;
import com.gilbom.robot.credential.MqttCredentialSecretGenerator;
import com.gilbom.robot.data.api.response.MqttCredentialRegistryStatusResponse;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;
import com.gilbom.storage.StorageProperties;
import com.gilbom.storage.auth.RobotImageApiKeyIssuanceService;
import com.gilbom.storage.auth.RobotImageApiKeySecretGenerator;

import io.micrometer.core.instrument.MeterRegistry;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = MqttCredentialIssuanceIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none",
                "gilbom.storage.upload-url=https://example.test/api/v1/images",
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
    private static final UUID VIEWER_USER_ID =
            UUID.fromString("f854f421-f550-40c3-bbe7-6369a17bc995");
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

    @Autowired
    private MqttCredentialIssuanceService issuanceService;

    @Autowired
    private MqttCredentialRegistryService registryService;

    @Autowired
    private MeterRegistry meterRegistry;

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
                "DELETE FROM storage.robot_image_api_key WHERE robot_id = ?",
                ROBOT_ID);
        jdbcTemplate.update(
                "DELETE FROM auth.user_robot_assignment WHERE robot_id = ?",
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
                INSERT INTO auth.user_role (
                    user_id,
                    role_id,
                    is_primary,
                    is_active,
                    assigned_by
                )
                SELECT ?, role_id, TRUE, TRUE, ?
                FROM auth.role
                WHERE role_code = 'ADMIN'
                  AND is_system = TRUE
                ON CONFLICT (user_id, role_id) DO NOTHING
                """,
                ADMIN_USER_ID,
                ADMIN_USER_ID);

        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
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
    void hidesRobotFromViewerDuringCredentialStateChange() throws Exception {
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
                VIEWER_USER_ID,
                "USR-CREDENTIAL-VIEWER",
                passwordEncoder.encode("integration-only-viewer-password"),
                "MQTT 자격증명 테스트 조회자",
                "mqtt-credential-viewer@example.test");
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id,
                    robot_id,
                    assignment_type,
                    reason,
                    assigned_by
                ) VALUES (?, ?, 'VIEWER', ?, ?)
                """,
                VIEWER_USER_ID,
                ROBOT_ID,
                "credential scope integration test",
                ADMIN_USER_ID);

        assertThatThrownBy(() -> issuanceService.issue(
                ROBOT_ID,
                VIEWER_USER_ID,
                UUID.fromString("d62f8054-d9e0-4bf9-9753-146755bd324e"),
                UUID.fromString("c2c4c594-7c21-48e8-a48b-e7fe177bbb18")
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.ROBOT_NOT_FOUND)
        );

        when(sessionSecurityStateRepository.findBySessionId(anyString()))
                .thenReturn(java.util.Optional.of(new SessionSecurityState(
                        VIEWER_USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        java.time.Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0
                )));
        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(VIEWER_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(
                                RequestIdFilter.HEADER_NAME,
                                "e8123461-bb59-49aa-a5c5-d4bf0d00ee24")
                        .header(
                                "Idempotency-Key",
                                "0dfaa1de-f98d-4f4d-b69a-46f793ca87bc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ROBOT_NOT_FOUND")));

        assertThat(provisioner.provisionCount).hasValue(0);
    }

    @Test
    void hidesCredentialListAndRevokeFromViewerScope() {
        UUID credentialId = issueDirect(
                "30fe7112-83e4-4f61-8f8c-8e889b237c58",
                "a7700122-14c6-493f-b504-c97179726e89");
        createViewerFixture();

        assertThatThrownBy(() -> issuanceService.list(
                ROBOT_ID,
                VIEWER_USER_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ROBOT_NOT_FOUND));
        assertThatThrownBy(() -> issuanceService.revoke(
                ROBOT_ID,
                credentialId,
                VIEWER_USER_ID,
                "UNAUTHORIZED_SCOPE_TEST",
                UUID.fromString(
                        "3c46d5bc-2845-42d7-83ba-f325823dd9bf")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ROBOT_NOT_FOUND));

        assertThat(provisioner.revokeCount).hasValue(0);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT credential_status
                FROM robot.robot_credential
                WHERE credential_id = ?
                """, String.class, credentialId))
                .isEqualTo("ACTIVE");
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
                        "$.robotId",
                        is(ROBOT_ID.toString())))
                .andExpect(jsonPath("$.mqttId", is(ROBOT_CODE)))
                .andExpect(jsonPath(
                        "$.mqtt.brokerUri",
                        is("ssl://125.137.58.131:8883")))
                .andExpect(jsonPath("$.mqtt.username", is(ROBOT_CODE)))
                .andExpect(jsonPath("$.mqtt.clientId", is(ROBOT_CODE)))
                .andExpect(jsonPath(
                        "$.imageUpload.url",
                        is("https://example.test/api/v1/images")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseJson);
        String password = response.at("/mqtt/password").asText();
        String imageApiKey = response.at("/imageUpload/apiKey").asText();
        UUID credentialId =
                UUID.fromString(response.get("credentialId").asText());

        assertThat(Base64.getUrlDecoder().decode(password)).hasSize(32);
        assertThat(imageApiKey).startsWith("gik_");
        assertThat(Base64.getUrlDecoder().decode(imageApiKey.substring(4)))
                .hasSize(32);
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

        assertThat(jdbcTemplate.queryForMap("""
                SELECT key_prefix, key_hash, key_status
                FROM storage.robot_image_api_key
                WHERE robot_id = ?
                """, ROBOT_ID))
                .containsEntry("key_prefix", imageApiKey.substring(0, 12))
                .containsEntry("key_hash", sha256Hex(imageApiKey))
                .containsEntry("key_status", "ACTIVE")
                .doesNotContainValue(imageApiKey);

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
                .doesNotContain(imageApiKey)
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
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM storage.robot_image_api_key
                WHERE robot_id = ?
                """, Integer.class, ROBOT_ID))
                .isEqualTo(1);
    }

    @Test
    void rotatesToANewVersionAndListsOnlySafeCredentialFields()
            throws Exception {
        UUID firstCredentialId = issueDirect(
                "f0bc7c66-f797-4f7b-86c8-d352fa4bb6c0",
                "8c5f6f4e-10c5-49d1-9e0a-36b0614be09d");
        String previousPasswordHash = provisioner.brokerPasswordHash;
        provisioner.passwordHash =
                "$7$101$integration-test-rotated-verifier";
        UUID requestId =
                UUID.fromString("756da722-48ff-4383-8b32-46d7cf95e1f2");

        String responseJson = mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, requestId)
                        .header(
                                "Idempotency-Key",
                                "c477b464-a29c-42e5-9df7-8d82caf62f3e"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.mqtt.username", is(ROBOT_CODE)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID rotatedCredentialId = UUID.fromString(
                objectMapper.readTree(responseJson)
                        .get("credentialId")
                        .asText());
        assertThat(rotatedCredentialId).isNotEqualTo(firstCredentialId);
        assertThat(provisioner.provisionCount).hasValue(1);
        assertThat(provisioner.rotateCount).hasValue(1);
        assertThat(provisioner.restoreCount).hasValue(0);
        assertThat(provisioner.brokerPasswordHash)
                .isEqualTo(provisioner.passwordHash)
                .isNotEqualTo(previousPasswordHash);

        List<java.util.Map<String, Object>> credentials =
                jdbcTemplate.queryForList("""
                        SELECT
                            credential_id,
                            credential_version,
                            credential_status,
                            revoked_by,
                            revocation_reason
                        FROM robot.robot_credential
                        WHERE robot_id = ?
                        ORDER BY credential_version
                        """,
                        ROBOT_ID);
        assertThat(credentials).hasSize(2);
        assertThat(credentials.get(0))
                .containsEntry("credential_id", firstCredentialId)
                .containsEntry("credential_version", 1)
                .containsEntry("credential_status", "REVOKED")
                .containsEntry("revoked_by", ADMIN_USER_ID)
                .containsEntry("revocation_reason", "ROTATED");
        assertThat(credentials.get(1))
                .containsEntry("credential_id", rotatedCredentialId)
                .containsEntry("credential_version", 2)
                .containsEntry("credential_status", "ACTIVE");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type = 'ROBOT_CREDENTIAL_ROTATED'
                  AND target_id = ?
                  AND result = 'SUCCESS'
                """, Integer.class, requestId, rotatedCredentialId))
                .isEqualTo(1);

        String listJson = mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath(
                        "$.credentials[0].credentialVersion",
                        is(2)))
                .andExpect(jsonPath(
                        "$.credentials[0].credentialStatus",
                        is("ACTIVE")))
                .andExpect(jsonPath(
                        "$.credentials[1].credentialStatus",
                        is("REVOKED")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listJson)
                .doesNotContain("password")
                .doesNotContain("secretHash")
                .doesNotContain("secretFingerprint")
                .doesNotContain(previousPasswordHash)
                .doesNotContain(provisioner.passwordHash);
    }

    @Test
    void revokesBrokerCredentialAndTreatsRepeatedRevokeAsIdempotent()
            throws Exception {
        UUID credentialId = issueDirect(
                "bfebd7f0-1f7d-46fb-8463-bf319cb0ef8e",
                "4bbb2419-a57a-4f83-b0a4-76f9e9379ced");
        UUID requestId =
                UUID.fromString("44257dbf-2d00-4bbd-bf24-b39929f6347f");
        String requestBody = """
                {
                  "reason": "DEVICE_RETIRED"
                }
                """;

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "{credentialId}/revoke",
                        ROBOT_ID,
                        credentialId)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath(
                        "$.credentialStatus",
                        is("REVOKED")))
                .andExpect(jsonPath(
                        "$.revocationReason",
                        is("DEVICE_RETIRED")))
                .andExpect(jsonPath("$.secretHash").doesNotExist())
                .andExpect(jsonPath("$.secretFingerprint").doesNotExist());

        assertThat(provisioner.revokeCount).hasValue(1);
        assertThat(provisioner.brokerPasswordHash).isNull();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT
                    credential_status,
                    revoked_by,
                    revocation_reason
                FROM robot.robot_credential
                WHERE credential_id = ?
                """, credentialId))
                .containsEntry("credential_status", "REVOKED")
                .containsEntry("revoked_by", ADMIN_USER_ID)
                .containsEntry("revocation_reason", "DEVICE_RETIRED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type = 'ROBOT_CREDENTIAL_REVOKED'
                  AND target_id = ?
                  AND result = 'SUCCESS'
                """, Integer.class, requestId, credentialId))
                .isEqualTo(1);

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "{credentialId}/revoke",
                        ROBOT_ID,
                        credentialId)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(
                                RequestIdFilter.HEADER_NAME,
                                "a3b8de9c-e519-4b53-929f-1e8e94570f0f")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.credentialStatus",
                        is("REVOKED")));

        assertThat(provisioner.revokeCount).hasValue(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE action_type = 'ROBOT_CREDENTIAL_REVOKED'
                  AND target_id = ?
                """, Integer.class, credentialId))
                .isEqualTo(1);
    }

    @Test
    void restoresPreviousBrokerHashWhenRotationTransactionRollsBack()
            throws Exception {
        UUID credentialId = issueDirect(
                "06c719a7-52f6-4586-aafc-95aff340e78f",
                "23ca4dbf-8c21-4186-bec8-2d264632fa72");
        String previousPasswordHash = provisioner.brokerPasswordHash;
        provisioner.passwordHash = "\u0000invalid-rotated-verifier";

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(
                                RequestIdFilter.HEADER_NAME,
                                "64067fd8-8cee-482c-8207-8df6de4d0647")
                        .header(
                                "Idempotency-Key",
                                "f19a105c-9c90-4898-9e0f-0befb49e001f"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is("COMMON_INTERNAL_ERROR")));

        assertThat(provisioner.rotateCount).hasValue(1);
        assertThat(provisioner.restoreCount).hasValue(1);
        assertThat(provisioner.brokerPasswordHash)
                .isEqualTo(previousPasswordHash);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_credential
                WHERE robot_id = ?
                """, Integer.class, ROBOT_ID))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT credential_status, credential_version
                FROM robot.robot_credential
                WHERE credential_id = ?
                """, credentialId))
                .containsEntry("credential_status", "ACTIVE")
                .containsEntry("credential_version", 1);
    }

    @Test
    void restoresBrokerHashWhenRevocationTransactionRollsBack() {
        UUID credentialId = issueDirect(
                "6eb72c25-362e-45c3-b5d6-afbdd3880933",
                "09a2ab2a-3403-45a6-b5c8-f54814932218");
        String previousPasswordHash = provisioner.brokerPasswordHash;

        assertThatThrownBy(() -> issuanceService.revoke(
                ROBOT_ID,
                credentialId,
                ADMIN_USER_ID,
                "invalid\u0000reason",
                UUID.fromString(
                        "1c006efb-4f53-4630-a2a9-ec9c585a9c47")))
                .isInstanceOf(RuntimeException.class);

        assertThat(provisioner.revokeCount).hasValue(1);
        assertThat(provisioner.restoreCount).hasValue(1);
        assertThat(provisioner.brokerPasswordHash)
                .isEqualTo(previousPasswordHash);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT credential_status
                FROM robot.robot_credential
                WHERE credential_id = ?
                """, String.class, credentialId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void rejectsRotationWhenDatabaseAndBrokerHashesDrift()
            throws Exception {
        UUID credentialId = issueDirect(
                "29adbb7d-90e9-491c-82a5-f5cc63577013",
                "10d34020-2f08-46db-ae26-ab42009c85cd");
        provisioner.brokerPasswordHash = "$7$101$external-drift";

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(
                                RequestIdFilter.HEADER_NAME,
                                "56691ccd-ef53-45f6-80af-0961f24936bd")
                        .header(
                                "Idempotency-Key",
                                "d3883eb6-6a4f-48c1-80b0-e2566ff922cb"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath(
                        "$.code",
                        is("MQTT_CREDENTIAL_REGISTRY_DRIFT")));

        assertThat(provisioner.rotateCount).hasValue(0);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT credential_status, credential_version
                FROM robot.robot_credential
                WHERE credential_id = ?
                """, credentialId))
                .containsEntry("credential_status", "ACTIVE")
                .containsEntry("credential_version", 1);
    }

    @Test
    void serializesConcurrentRotationsAndKeepsExactlyOneActiveVersion()
            throws Exception {
        issueDirect(
                "cfe4e451-f934-45ae-8343-85bdc1864a8b",
                "16437923-b6a5-452d-854a-c58990ffb4f2");
        provisioner.passwordHash =
                "$7$101$concurrent-rotation-verifier";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> first = executor.submit(() -> {
                start.await();
                return issuanceService.issue(
                        ROBOT_ID,
                        ADMIN_USER_ID,
                        UUID.fromString(
                                "90887138-408a-4bb2-9863-f657f446d7cc"),
                        UUID.fromString(
                                "b668528d-fad2-48e6-8d39-c875275658a7"))
                        .credentialId();
            });
            Future<UUID> second = executor.submit(() -> {
                start.await();
                return issuanceService.issue(
                        ROBOT_ID,
                        ADMIN_USER_ID,
                        UUID.fromString(
                                "85e122bd-f754-4a96-8c98-bf8535591ce9"),
                        UUID.fromString(
                                "517b5cda-b23d-4636-9602-68103ee2ce75"))
                        .credentialId();
            });

            start.countDown();
            assertThat(first.get()).isNotEqualTo(second.get());
        } finally {
            executor.shutdownNow();
        }

        assertThat(provisioner.rotateCount).hasValue(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_credential
                WHERE robot_id = ?
                """, Integer.class, ROBOT_ID))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_credential
                WHERE robot_id = ?
                  AND credential_status = 'ACTIVE'
                """, Integer.class, ROBOT_ID))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT MAX(credential_version)
                FROM robot.robot_credential
                WHERE robot_id = ?
                """, Integer.class, ROBOT_ID))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT issued_at
                FROM robot.robot_credential
                WHERE robot_id = ?
                ORDER BY credential_version
                """, java.sql.Timestamp.class, ROBOT_ID))
                .isSorted();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_credential
                WHERE robot_id = ?
                  AND revoked_at < issued_at
                """, Integer.class, ROBOT_ID))
                .isZero();
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

    @Test
    void detectsAndReconcilesAnActiveCredentialHashMismatch()
            throws Exception {
        UUID credentialId = issueDirect(
                "36f6dc2a-efb5-4bb9-b3ad-ad9f8d0dc4f0",
                "60bc6a23-c1ee-4738-a38d-9efe1a44512c");
        String databaseHash = provisioner.brokerPasswordHash;
        provisioner.brokerPasswordHash =
                "$7$101$externally-modified-verifier";
        UUID reconcileRequestId =
                UUID.fromString("d8f259b4-d3bc-4fe4-a09f-39bbc10318a0");

        mockMvc.perform(get(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "registry-status",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registryStatus", is("MISMATCH")))
                .andExpect(jsonPath("$.driftDetected", is(true)))
                .andExpect(jsonPath("$.activeCredentialId",
                        is(credentialId.toString())))
                .andExpect(jsonPath("$.secretHash").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(post(
                        "/api/v1/robots/{robotId}/credentials/"
                                + "registry-reconcile",
                        ROBOT_ID)
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(
                                RequestIdFilter.HEADER_NAME,
                                reconcileRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "BROKER_HASH_DRIFT_RECOVERY"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registryStatus", is("IN_SYNC")))
                .andExpect(jsonPath("$.driftDetected", is(false)));

        assertThat(provisioner.restoreCount).hasValue(1);
        assertThat(provisioner.brokerPasswordHash).isEqualTo(databaseHash);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type =
                      'ROBOT_CREDENTIAL_REGISTRY_RECONCILED'
                  AND target_id = ?
                  AND result = 'SUCCESS'
                """, Integer.class, reconcileRequestId, credentialId))
                .isEqualTo(1);
    }

    @Test
    void removesAnOrphanedBrokerCredentialDuringReconciliation() {
        UUID credentialId = issueDirect(
                "4d2f1357-a4cd-4510-b864-c094583470dc",
                "8f9ce8c3-35dd-4d5e-a8f5-aecaf80995b2");
        issuanceService.revoke(
                ROBOT_ID,
                credentialId,
                ADMIN_USER_ID,
                "DEVICE_RETIRED",
                UUID.fromString(
                        "dbcd780c-e89d-4f72-83aa-3b5863773858"));
        provisioner.brokerPasswordHash =
                "$7$101$orphaned-broker-verifier";

        MqttCredentialRegistryStatusResponse before =
                registryService.status(ROBOT_ID, ADMIN_USER_ID);
        assertThat(before.registryStatus()).isEqualTo("ORPHANED");
        assertThat(before.driftDetected()).isTrue();

        MqttCredentialRegistryStatusResponse after =
                registryService.reconcile(
                        ROBOT_ID,
                        ADMIN_USER_ID,
                        "ORPHANED_ACCOUNT_RECOVERY",
                        UUID.fromString(
                                "7cba24ce-f7bf-480b-ab17-8279470973ba"));

        assertThat(after.registryStatus()).isEqualTo("IN_SYNC");
        assertThat(after.activeCredentialId()).isNull();
        assertThat(provisioner.brokerPasswordHash).isNull();
        assertThat(provisioner.revokeCount).hasValue(2);
    }

    @Test
    void restoresThePriorDriftStateWhenReconciliationRollsBack() {
        issueDirect(
                "85467123-f895-47cc-9962-4799742dd296",
                "5357d3fd-d01e-44fc-b2f9-e74d7c204269");
        String priorBrokerHash = "$7$101$prior-drift-verifier";
        provisioner.brokerPasswordHash = priorBrokerHash;
        provisioner.failInspectionAfterNextRestore = true;

        assertThatThrownBy(() -> registryService.reconcile(
                ROBOT_ID,
                ADMIN_USER_ID,
                "VERIFY_ROLLBACK_RECOVERY",
                UUID.fromString(
                        "76a7f966-66cb-4749-a197-64f9dfcb4341")))
                .isInstanceOf(RuntimeException.class);

        assertThat(provisioner.restoreCount).hasValue(2);
        assertThat(provisioner.brokerPasswordHash)
                .isEqualTo(priorBrokerHash);
        assertThat(registryService.status(
                ROBOT_ID,
                ADMIN_USER_ID).registryStatus())
                .isEqualTo("MISMATCH");
    }

    @Test
    void scansAllRobotCredentialsAndReportsDriftWithoutHighCardinalityTags() {
        issueDirect(
                "449ff9cc-3c30-49e6-b319-e59f673531c7",
                "002f4e2a-5a4b-480e-b3ee-0f42ed52c682");
        provisioner.brokerPasswordHash =
                "$7$101$periodic-scan-drift";

        MqttCredentialRegistryService.ScanSummary summary =
                registryService.scanRegistry();

        assertThat(summary.checkedCount()).isEqualTo(1);
        assertThat(summary.driftCount()).isEqualTo(1);
        assertThat(summary.inspectionErrorCount()).isZero();
        assertThat(meterRegistry.find(
                "gilbom.mqtt.credential.registry.drift.current")
                .gauge()
                .value()).isEqualTo(1.0);
        assertThat(meterRegistry.find(
                "gilbom.mqtt.credential.registry.checks")
                .meters())
                .allSatisfy(meter -> assertThat(
                        meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactly("result"));
    }

    private UUID issueDirect(String idempotencyKey, String requestId) {
        return issuanceService.issue(
                ROBOT_ID,
                ADMIN_USER_ID,
                UUID.fromString(idempotencyKey),
                UUID.fromString(requestId))
                .credentialId();
    }

    private void createViewerFixture() {
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
                VIEWER_USER_ID,
                "USR-CREDENTIAL-VIEWER",
                passwordEncoder.encode(
                        "integration-only-viewer-password"),
                "MQTT 자격증명 테스트 조회자",
                "mqtt-credential-viewer@example.test");
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id,
                    robot_id,
                    assignment_type,
                    reason,
                    assigned_by
                ) VALUES (?, ?, 'VIEWER', ?, ?)
                """,
                VIEWER_USER_ID,
                ROBOT_ID,
                "credential scope integration test",
                ADMIN_USER_ID);
    }

    private String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties({
            MqttCredentialProvisioningProperties.class,
            StorageProperties.class
    })
    @Import({
            SecurityConfig.class,
            SecurityErrorResponseWriter.class,
            SecurityHeadersFilter.class,
            RequestIdFilter.class,
            RequestBodySizeLimitFilter.class,
            GlobalExceptionHandler.class,
            AuditLogRepository.class,
            AuditLogService.class,
            BestEffortAuditLogWriter.class,
            MqttCredentialIssuanceController.class,
            MqttCredentialIssuanceService.class,
            MqttCredentialRegistryService.class,
            MqttCredentialSecretGenerator.class,
            RobotImageApiKeyIssuanceService.class,
            RobotImageApiKeySecretGenerator.class,
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

        @Bean
        Clock registryClock() {
            return Clock.systemUTC();
        }
    }

    static final class RecordingMqttCredentialProvisioner
            implements MqttCredentialProvisioner {

        private String passwordHash =
                "$7$101$integration-test-mosquitto-verifier";
        private final AtomicInteger provisionCount = new AtomicInteger();
        private final AtomicInteger rotateCount = new AtomicInteger();
        private final AtomicInteger revokeCount = new AtomicInteger();
        private final AtomicInteger restoreCount = new AtomicInteger();
        private final AtomicInteger removeCount = new AtomicInteger();
        private volatile String brokerPasswordHash;
        private volatile String username;
        private volatile String password;
        private volatile boolean failInspectionAfterNextRestore;
        private volatile boolean failNextInspection;

        @Override
        public ProvisionedMqttCredential provision(
                String provisionedUsername,
                String provisionedPassword
        ) {
            username = provisionedUsername;
            password = provisionedPassword;
            provisionCount.incrementAndGet();
            brokerPasswordHash = passwordHash;
            return new ProvisionedMqttCredential(passwordHash);
        }

        @Override
        public ProvisionedMqttCredential rotate(
                String rotatedUsername,
                String expectedPasswordHash,
                String rotatedPassword
        ) {
            requireExpectedHash(expectedPasswordHash);
            username = rotatedUsername;
            password = rotatedPassword;
            rotateCount.incrementAndGet();
            brokerPasswordHash = passwordHash;
            return new ProvisionedMqttCredential(passwordHash);
        }

        @Override
        public void revoke(
                String revokedUsername,
                String expectedPasswordHash
        ) {
            requireExpectedHash(expectedPasswordHash);
            revokeCount.incrementAndGet();
            brokerPasswordHash = null;
        }

        @Override
        public void restore(
                String restoredUsername,
                String restoredPasswordHash
        ) {
            restoreCount.incrementAndGet();
            brokerPasswordHash = restoredPasswordHash;
            if (failInspectionAfterNextRestore) {
                failInspectionAfterNextRestore = false;
                failNextInspection = true;
            }
        }

        @Override
        public void remove(String removedUsername) {
            removeCount.incrementAndGet();
            brokerPasswordHash = null;
        }

        @Override
        public CredentialRegistrySnapshot inspect(
                String inspectedUsername
        ) {
            if (failNextInspection) {
                failNextInspection = false;
                throw new BusinessException(
                        ErrorCode.MQTT_CREDENTIAL_PROVISIONING_UNAVAILABLE);
            }
            return new CredentialRegistrySnapshot(brokerPasswordHash);
        }

        void reset() {
            passwordHash = "$7$101$integration-test-mosquitto-verifier";
            provisionCount.set(0);
            rotateCount.set(0);
            revokeCount.set(0);
            restoreCount.set(0);
            removeCount.set(0);
            brokerPasswordHash = null;
            username = null;
            password = null;
            failInspectionAfterNextRestore = false;
            failNextInspection = false;
        }

        private void requireExpectedHash(String expectedPasswordHash) {
            if (!java.util.Objects.equals(
                    expectedPasswordHash,
                    brokerPasswordHash)) {
                throw new BusinessException(
                        ErrorCode.MQTT_CREDENTIAL_REGISTRY_DRIFT);
            }
        }
    }
}
