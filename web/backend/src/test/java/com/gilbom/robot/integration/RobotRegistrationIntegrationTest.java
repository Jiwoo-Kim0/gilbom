package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String ADMIN_USERNAME = "robot-integration-admin";
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

    @BeforeEach
    void createAuthenticatedAdminFixture() {
        String passwordHash = passwordEncoder.encode(ADMIN_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    username,
                    password_hash,
                    display_name,
                    email,
                    password_change_required
                ) VALUES (?, ?, ?, ?, ?, ?, FALSE)
                ON CONFLICT (user_id) DO UPDATE
                SET password_hash = EXCLUDED.password_hash,
                    is_active = TRUE,
                    deleted_at = NULL,
                    updated_at = NOW()
                """,
                ADMIN_USER_ID,
                "USR-ROBOT-INTEGRATION",
                ADMIN_USERNAME,
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
                        .with(user(ADMIN_USERNAME).authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, firstRequestId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        matchesPattern("^/api/v1/robots/[0-9a-f-]{36}$")))
                .andExpect(jsonPath("$.robotCode", matchesPattern("^RBT-[0-9]{3,}$")))
                .andExpect(jsonPath("$.operationalStatus", is("PROVISIONING")))
                .andExpect(jsonPath("$.connectivityStatus", is("OFFLINE")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode firstBody = objectMapper.readTree(firstResponse);
        UUID robotId = UUID.fromString(firstBody.get("robotId").asText());
        String robotCode = firstBody.get("robotCode").asText();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT
                    robot_code,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                FROM robot.robot
                WHERE robot_id = ?
                """, robotId))
                .containsEntry("robot_code", robotCode)
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
                        .with(user(ADMIN_USERNAME).authorities(ROBOT_REGISTER))
                        .with(csrf().asHeader())
                        .header(RequestIdFilter.HEADER_NAME, replayRequestId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.robotId", is(robotId.toString())))
                .andExpect(jsonPath("$.robotCode", is(robotCode)))
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
            RobotRegistrationController.class,
            RobotRegistrationService.class
    })
    static class TestApplication {
    }
}
