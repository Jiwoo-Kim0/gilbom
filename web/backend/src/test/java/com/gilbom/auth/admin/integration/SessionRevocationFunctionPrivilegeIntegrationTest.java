package com.gilbom.auth.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class SessionRevocationFunctionPrivilegeIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final String APPLICATION_ROLE =
            "gilbom_session_revocation_app";
    private static final String APPLICATION_PASSWORD =
            "session-revocation-test-password";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_session_privilege_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static JdbcTemplate ownerJdbcTemplate;
    private static JdbcTemplate applicationJdbcTemplate;

    @BeforeAll
    static void migrateWithDistinctApplicationRole() {
        DataSource ownerDataSource = dataSource(
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        ownerJdbcTemplate = new JdbcTemplate(ownerDataSource);
        ownerJdbcTemplate.execute("""
                CREATE ROLE gilbom_session_revocation_app
                LOGIN
                PASSWORD 'session-revocation-test-password'
                NOSUPERUSER
                NOCREATEDB
                NOCREATEROLE
                NOINHERIT
                """);

        Flyway.configure()
                .dataSource(ownerDataSource)
                .defaultSchema("flyway_history")
                .placeholders(Map.of(
                        "applicationRole",
                        APPLICATION_ROLE
                ))
                .load()
                .migrate();

        applicationJdbcTemplate = new JdbcTemplate(dataSource(
                APPLICATION_ROLE,
                APPLICATION_PASSWORD
        ));
    }

    @Test
    @Tag("security-acceptance")
    void applicationRoleCanExecuteOnlyExplicitlyGrantedSessionFunction() {
        Boolean executable = applicationJdbcTemplate.queryForObject(
                """
                SELECT has_function_privilege(
                    current_user,
                    'auth.revoke_user_authorization_sessions(uuid)',
                    'EXECUTE'
                )
                """,
                Boolean.class
        );
        Integer deletedSessions = applicationJdbcTemplate.queryForObject(
                "SELECT auth.revoke_user_authorization_sessions(?)",
                Integer.class,
                UUID.randomUUID()
        );
        List<String> grantees = ownerJdbcTemplate.queryForList(
                """
                SELECT grantee
                FROM information_schema.routine_privileges
                WHERE routine_schema = 'auth'
                  AND routine_name =
                      'revoke_user_authorization_sessions'
                  AND privilege_type = 'EXECUTE'
                ORDER BY grantee
                """,
                String.class
        );

        assertThat(executable).isTrue();
        assertThat(deletedSessions).isZero();
        assertThat(grantees)
                .contains(APPLICATION_ROLE, DATABASE.getUsername())
                .doesNotContain("PUBLIC");
    }

    /**
     * 권한 입력값이 바뀌면 trigger가 그 사용자의 session을 지운다 — **현재 session도
     * 제외하지 않는다.**
     *
     * <p>V43이 trigger 함수 여섯 개를 다시 쓰면서 RAISE LOG를 더했다. 판정과 삭제 동작이
     * 그대로인지 못박는다. 이 경로는 애플리케이션 로그에 흔적을 남기지 않아, 깨져도
     * (혹은 반대로 과하게 발동해도) 사람이 알아채기 어렵다.
     *
     * <p>`user_permission_override`를 쓴다. `user_robot_assignment`도 같은 함수를 타지만
     * migration이 robot을 시드하지 않아 FK를 채울 수 없다.
     */
    @Test
    void authorizationChangeRevokesTheUsersSession() {
        UUID userId = ownerJdbcTemplate.queryForObject(
                "SELECT user_id FROM auth.admin_user LIMIT 1",
                UUID.class
        );
        UUID permissionId = ownerJdbcTemplate.queryForObject(
                "SELECT permission_id FROM auth.permission LIMIT 1",
                UUID.class
        );
        UUID sessionPrimaryId = UUID.randomUUID();
        insertAuthenticatedSession(sessionPrimaryId, userId);

        assertThat(countSessions(sessionPrimaryId))
                .as("지우기 전에는 session이 있어야 시험이 성립한다")
                .isOne();

        ownerJdbcTemplate.update(
                """
                INSERT INTO auth.user_permission_override (
                    user_id, permission_id, override_effect, reason,
                    is_active, granted_by
                ) VALUES (?, ?, 'ALLOW', 'V43 회귀 시험', TRUE, ?)
                """,
                userId,
                permissionId,
                userId
        );

        assertThat(countSessions(sessionPrimaryId))
                .as("권한이 바뀌면 그 사용자의 session은 남지 않는다")
                .isZero();
        assertThat(ownerJdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_log
                WHERE action_type = 'AUTH_SESSION_DELETED'
                  AND target_type = 'USER'
                  AND target_id = ?
                  AND reason = 'AUTHORIZATION_CHANGE'
                  AND after_value ->> 'deletionReason' =
                      'AUTHORIZATION_CHANGE'
                """,
                Integer.class,
                userId
        )).isOne();
    }

    /**
     * `ck_session_security_lifetime_limits`가 AUTHENTICATED에 대해
     * {@code absolute_expiry_time <= updated_time + 8시간}을 요구한다.
     */
    private static void insertAuthenticatedSession(
            UUID sessionPrimaryId,
            UUID userId
    ) {
        long now = 1L;
        long absoluteExpiry = now + 28_800_000L;
        ownerJdbcTemplate.update(
                """
                INSERT INTO session_store.spring_session (
                    primary_id, session_id, creation_time, last_access_time,
                    max_inactive_interval, expiry_time, principal_name
                ) VALUES (?, ?, ?, ?, 1800, ?, ?)
                """,
                sessionPrimaryId.toString(),
                UUID.randomUUID().toString(),
                now,
                now,
                absoluteExpiry,
                userId.toString()
        );
        ownerJdbcTemplate.update(
                """
                INSERT INTO session_store.session_security_state (
                    session_primary_id, user_id, authentication_state,
                    absolute_expiry_time,
                    authentication_challenge_failure_count,
                    created_time, updated_time
                ) VALUES (?, ?, 'AUTHENTICATED', ?, 0, ?, ?)
                """,
                sessionPrimaryId.toString(),
                userId,
                absoluteExpiry,
                now,
                now
        );
    }

    private static int countSessions(UUID sessionPrimaryId) {
        Integer count = ownerJdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM session_store.spring_session
                WHERE primary_id = ?
                """,
                Integer.class,
                sessionPrimaryId.toString()
        );
        return count == null ? 0 : count;
    }

    private static DataSource dataSource(
            String username,
            String password
    ) {
        return new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                username,
                password
        );
    }
}
