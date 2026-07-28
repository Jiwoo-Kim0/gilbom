package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.BestEffortAuditLogWriter;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.api.RobotRegistrationController;
import com.gilbom.robot.application.RobotRegistrationService;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = RobotRegistrationIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
@AutoConfigureMockMvc
class RobotRegistrationIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID ADMIN_USER_ID =
            UUID.fromString("f854f421-f550-40c3-bbe7-6369a17bc993");
    private static final String ADMIN_PASSWORD = "integration-only-admin-password";
    private static final SimpleGrantedAuthority ROBOT_REGISTER =
            new SimpleGrantedAuthority("ROBOT_REGISTER");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_robot_registration_test")
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

    @MockitoBean
    private SessionSecurityStateRepository sessionSecurityStateRepository;

    @BeforeEach
    void createAuthenticatedAdminFixture() {
        when(sessionSecurityStateRepository.findBySessionId(anyString()))
                .thenReturn(java.util.Optional.of(new SessionSecurityState(
                        ADMIN_USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        java.time.Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0
                )));
        String passwordHash = passwordEncoder.encode(ADMIN_PASSWORD);
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
                ON CONFLICT (user_id) DO UPDATE
                SET password_hash = EXCLUDED.password_hash,
                    is_active = TRUE,
                    account_status = 'ACTIVE',
                    password_change_required = FALSE,
                    password_changed_at = NOW(),
                    deleted_at = NULL,
                    updated_at = NOW()
                """,
                ADMIN_USER_ID,
                "USR-ROBOT-INTEGRATION",
                passwordHash,
                "로봇 통합 테스트 관리자",
                "robot-integration@example.test");
    }

    @Test
    void completesRegistrationPersistenceAuditAndIdempotentReplay() throws Exception {
        UUID firstRequestId =
                UUID.fromString("d5727022-019d-4e16-b4db-4094e91bfca7");
        UUID replayRequestId =
                UUID.fromString("c9b87ce1-667b-4474-ab16-1526953f47cd");
        UUID idempotencyKey =
                UUID.fromString("76f8c7e4-2b9e-48ca-a366-e0175a1d9109");

        String firstResponse = mockMvc.perform(post("/api/v1/robots")
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, firstRequestId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        matchesPattern("^/api/v1/robots/[0-9a-f-]{36}$")))
                .andExpect(jsonPath("$.mqttId", matchesPattern("^RBT-[0-9]{3,}$")))
                .andExpect(jsonPath("$.operationalStatus", is("PROVISIONING")))
                .andExpect(jsonPath("$.connectivityStatus", is("OFFLINE")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode firstBody = objectMapper.readTree(firstResponse);
        UUID robotId = UUID.fromString(firstBody.get("robotId").asText());
        String mqttId = firstBody.get("mqttId").asText();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                FROM robot.robot
                WHERE robot_id = ?
                """, robotId))
                .containsEntry("mqtt_id", mqttId)
                .containsEntry("robot_name", "점자블록 순찰 로봇 1호")
                .containsEntry("serial_number", "SN-JETSON-0001")
                .containsEntry("operational_status", "PROVISIONING")
                .containsEntry("registered_by", ADMIN_USER_ID);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT connectivity_status
                FROM telemetry.robot_current_state
                WHERE robot_id = ?
                """, String.class, robotId))
                .isEqualTo("OFFLINE");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND actor_user_id = ?
                  AND action_type = 'ROBOT_REGISTERED'
                  AND target_type = 'ROBOT'
                  AND target_id = ?
                  AND result = 'SUCCESS'
                """, Integer.class, firstRequestId, ADMIN_USER_ID, robotId))
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT processing_status, response_status
                FROM request_control.idempotency_request
                """))
                .containsEntry("processing_status", "SUCCEEDED")
                .containsEntry("response_status", 201);

        String replayResponse = mockMvc.perform(post("/api/v1/robots")
                        .with(user(ADMIN_USER_ID.toString())
                                .authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, replayRequestId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.robotId", is(robotId.toString())))
                .andExpect(jsonPath("$.mqttId", is(mqttId)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(replayResponse))
                .isEqualTo(firstBody);
        assertThat(count("robot.robot")).isEqualTo(1);
        assertThat(count("telemetry.robot_current_state")).isEqualTo(1);
        assertThat(count("audit.audit_log")).isEqualTo(1);
        assertThat(count("request_control.idempotency_request")).isEqualTo(1);

        // 로봇 등록과 MQTT Credential 발급은 별도 API다.
        assertThat(count("robot.robot_credential")).isZero();

        String storedAdminHash = jdbcTemplate.queryForObject("""
                SELECT password_hash
                FROM auth.admin_user
                WHERE user_id = ?
                """, String.class, ADMIN_USER_ID);
        assertThat(storedAdminHash).startsWith("{argon2}");
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, storedAdminHash)).isTrue();
    }

    @Test
    void rollsBackTheEntireRegistrationWhenAuditInsertFails()
            throws Exception {
        UUID requestId =
                UUID.fromString("d5727022-019d-4e16-b4db-4094e91bfca8");
        UUID idempotencyKey =
                UUID.fromString("76f8c7e4-2b9e-48ca-a366-e0175a1d9110");

        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION audit.reject_insert_for_test()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced audit failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tr_reject_audit_insert_for_test
                BEFORE INSERT ON audit.audit_log
                FOR EACH ROW
                EXECUTE FUNCTION audit.reject_insert_for_test()
                """);
        try {
            mockMvc.perform(post("/api/v1/robots")
                            .with(user(ADMIN_USER_ID.toString())
                                    .authorities(ROBOT_REGISTER))
                            .with(csrf().asHeader())
                            .header(RequestIdFilter.HEADER_NAME, requestId)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "robotName": "감사 실패 롤백 로봇",
                                      "serialNumber": "SN-AUDIT-ROLLBACK"
                                    }
                                    """))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath(
                            "$.code",
                            is("COMMON_INTERNAL_ERROR")
                    ));
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER IF EXISTS
                    tr_reject_audit_insert_for_test
                    ON audit.audit_log
                    """);
            jdbcTemplate.execute("""
                    DROP FUNCTION IF EXISTS
                    audit.reject_insert_for_test()
                    """);
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.robot
                    WHERE serial_number = 'SN-AUDIT-ROLLBACK'
                )
                """, Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM request_control.idempotency_request
                    WHERE request_path = '/api/v1/robots'
                      AND processing_status = 'PROCESSING'
                )
                """, Boolean.class)).isFalse();
    }

    private int count(String qualifiedTableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedTableName,
                Integer.class);
        return count == null ? 0 : count;
    }

    private String validRequest() {
        return """
                {
                  "robotName": "점자블록 순찰 로봇 1호",
                  "serialNumber": "SN-JETSON-0001"
                }
                """;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
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
            RobotRegistrationController.class,
            RobotRegistrationService.class,
            AuthenticatedUserIdResolver.class,
            PasswordHashingConfiguration.class
    })
    static class TestApplication {
    }
}
