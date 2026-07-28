package com.gilbom.audit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.audit.AuditLogController;
import com.gilbom.audit.AuditLogQueryService;
import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.BestEffortAuditLogWriter;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = AuditLogIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
@AutoConfigureMockMvc
class AuditLogIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID USER_ID =
            UUID.fromString("33000000-0000-4000-8000-000000000001");
    private static final UUID TARGET_ID =
            UUID.fromString("33000000-0000-4000-8000-000000000002");
    private static final UUID VIEW_REQUEST_ID =
            UUID.fromString("33000000-0000-4000-8000-000000000003");
    private static final SimpleGrantedAuthority AUDIT_LOG_VIEW =
            new SimpleGrantedAuthority("AUDIT_LOG_VIEW");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_audit_log_test")
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
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @BeforeEach
    void createUserAndSeedAudit() {
        when(sessionStateRepository.findBySessionId(anyString()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        Instant.parse("2099-01-01T00:00:00Z"),
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
                USER_ID,
                "USR-AUDIT-INTEGRATION",
                passwordEncoder.encode("integration-only-password"),
                "감사 조회 통합 테스트",
                "audit-integration@example.test");
        jdbcTemplate.update("""
                INSERT INTO audit.audit_log (
                    actor_user_id,
                    actor_type,
                    action_type,
                    target_type,
                    target_id,
                    after_value,
                    result
                ) VALUES (
                    ?,
                    'USER',
                    'ROBOT_REGISTERED',
                    'ROBOT',
                    ?,
                    '{"operationalStatus":"ACTIVE"}'::jsonb,
                    'SUCCESS'
                )
                """,
                USER_ID,
                TARGET_ID);
    }

    @Test
    @Tag("security-acceptance")
    void queriesWithPermissionAndAuditsTheViewWithoutRawFilterValues()
            throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("actionType", "ROBOT_REGISTERED")
                        .header(
                                RequestIdFilter.HEADER_NAME,
                                VIEW_REQUEST_ID
                        )
                        .header("User-Agent", "audit-integration-agent")
                        .with(user(USER_ID.toString())
                                .authorities(AUDIT_LOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath(
                        "$.content[0].actionType",
                        is("ROBOT_REGISTERED")
                ))
                .andExpect(jsonPath(
                        "$.content[0].targetId",
                        is(TARGET_ID.toString())
                ))
                .andExpect(jsonPath("$.totalElements", is(1)));

        var viewed = jdbcTemplate.queryForMap("""
                SELECT
                    actor_user_id,
                    action_type,
                    target_type,
                    after_value,
                    result,
                    user_agent
                FROM audit.audit_log
                WHERE request_id = ?
                """,
                VIEW_REQUEST_ID);
        assertThat(viewed)
                .containsEntry("actor_user_id", USER_ID)
                .containsEntry("action_type", "AUDIT_LOG_VIEWED")
                .containsEntry("target_type", "AUDIT_LOG")
                .containsEntry("result", "SUCCESS")
                .containsEntry("user_agent", "audit-integration-agent");
        assertThat(viewed.get("after_value").toString())
                .contains("actionType")
                .doesNotContain("ROBOT_REGISTERED");
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
            AuditLogController.class,
            AuditLogQueryService.class,
            AuditLogService.class,
            AuditLogRepository.class,
            BestEffortAuditLogWriter.class,
            AuthenticatedUserIdResolver.class,
            PasswordHashingConfiguration.class
    })
    static class TestApplication {
    }
}
