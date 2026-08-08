package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.gilbom.auth.model.AuthenticationState;

@Testcontainers(disabledWithoutDocker = true)
class SessionDeletionAuditIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID SEEDED_USER_ID = UUID.fromString(
            "39000000-0000-4000-8000-000000000001"
    );

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_session_deletion_audit_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static JdbcTemplate jdbcTemplate;
    private static SessionSecurityStateRepository repository;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("flyway_history")
                .placeholders(Map.of(
                        "applicationRole",
                        DATABASE.getUsername()
                ))
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new SessionSecurityStateRepository(jdbcTemplate);
    }

    @Test
    @Tag("security-acceptance")
    void everyDatabaseDeleteIsAuditedWithoutSessionIdentifiers() {
        clearSeededUserSessions();
        UUID contextualPrimaryId = UUID.randomUUID();
        String contextualSessionId = UUID.randomUUID().toString();
        insertAuthenticatedSession(
                contextualPrimaryId,
                contextualSessionId,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        assertThat(repository.deleteBySessionId(
                contextualSessionId,
                SessionDeletionReason.LOGOUT
        )).isOne();
        assertSafeDeletionAudit("LOGOUT", 1);

        UUID rawPrimaryId = UUID.randomUUID();
        String rawSessionId = UUID.randomUUID().toString();
        insertAuthenticatedSession(
                rawPrimaryId,
                rawSessionId,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        jdbcTemplate.update(
                "DELETE FROM session_store.spring_session WHERE session_id = ?",
                rawSessionId
        );

        assertSafeDeletionAudit("UNCLASSIFIED_DELETE", 1);
    }

    @Test
    void authenticatedLoginStoresIdleAbsoluteAndClearedChallengeExpiries() {
        clearSeededUserSessions();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant challengeExpiry = now.plus(5, ChronoUnit.MINUTES);
        Instant absoluteExpiry = now.plus(8, ChronoUnit.HOURS);
        UUID primaryId = UUID.randomUUID();
        String sessionId = UUID.randomUUID().toString();
        insertSpringSession(primaryId, sessionId, challengeExpiry);

        assertThat(repository.createChallenge(
                sessionId,
                SEEDED_USER_ID,
                AuthenticationState.MFA_REQUIRED,
                now,
                challengeExpiry
        )).isTrue();
        assertThat(repository.promoteAuthenticated(
                sessionId,
                SEEDED_USER_ID,
                AuthenticationState.MFA_REQUIRED,
                now,
                absoluteExpiry,
                1_800
        )).isTrue();

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                """
                SELECT
                    session.max_inactive_interval,
                    session.expiry_time,
                    security_state.authentication_state,
                    security_state.absolute_expiry_time,
                    security_state.authentication_challenge_started_time,
                    security_state.authentication_challenge_expires_at
                FROM session_store.spring_session session
                JOIN session_store.session_security_state security_state
                  ON security_state.session_primary_id = session.primary_id
                WHERE session.session_id = ?
                """,
                sessionId
        );
        assertThat(stored)
                .containsEntry("max_inactive_interval", 1_800)
                .containsEntry(
                        "expiry_time",
                        now.plusSeconds(1_800).toEpochMilli()
                )
                .containsEntry("authentication_state", "AUTHENTICATED")
                .containsEntry(
                        "absolute_expiry_time",
                        absoluteExpiry.toEpochMilli()
                );
        assertThat(stored.get("authentication_challenge_started_time"))
                .isNull();
        assertThat(stored.get("authentication_challenge_expires_at"))
                .isNull();

        // 이전 challenge 시각이 지난 직후여도 AUTHENTICATED 세션은 cleanup 대상이 아니다.
        assertThat(repository.deleteExpiredSecuritySessions(
                1_000,
                challengeExpiry.plusMillis(1)
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM session_store.spring_session
                WHERE session_id = ?
                """,
                Integer.class,
                sessionId
        )).isOne();
    }

    private static void assertSafeDeletionAudit(
            String reason,
            int minimumCount
    ) {
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                """
                SELECT reason, target_type, target_id, after_value::text snapshot
                FROM audit.audit_log
                WHERE action_type = 'AUTH_SESSION_DELETED'
                  AND reason = ?
                ORDER BY audit_id DESC
                LIMIT 1
                """,
                reason
        );
        assertThat(audit)
                .containsEntry("reason", reason)
                .containsEntry("target_type", "USER")
                .containsEntry("target_id", SEEDED_USER_ID);
        String snapshot = String.valueOf(audit.get("snapshot"));
        assertThat(snapshot)
                .contains("\"deletionReason\": \"" + reason + "\"")
                .doesNotContain("sessionId")
                .doesNotContain("session_id")
                .doesNotContain("primaryId")
                .doesNotContain("primary_id");
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_log
                WHERE action_type = 'AUTH_SESSION_DELETED'
                  AND reason = ?
                """,
                Integer.class,
                reason
        );
        assertThat(count).isGreaterThanOrEqualTo(minimumCount);
    }

    private static void clearSeededUserSessions() {
        repository.deleteAllByUserId(
                SEEDED_USER_ID,
                SessionDeletionReason.APPLICATION_REQUEST
        );
    }

    private static void insertAuthenticatedSession(
            UUID primaryId,
            String sessionId,
            Instant expiry
    ) {
        insertSpringSession(primaryId, sessionId, expiry);
        long now = Instant.now().toEpochMilli();
        jdbcTemplate.update(
                """
                INSERT INTO session_store.session_security_state (
                    session_primary_id, user_id, authentication_state,
                    absolute_expiry_time,
                    authentication_challenge_failure_count,
                    created_time, updated_time
                ) VALUES (?, ?, 'AUTHENTICATED', ?, 0, ?, ?)
                """,
                primaryId.toString(),
                SEEDED_USER_ID,
                expiry.toEpochMilli(),
                now,
                now
        );
    }

    private static void insertSpringSession(
            UUID primaryId,
            String sessionId,
            Instant expiry
    ) {
        long now = Instant.now().toEpochMilli();
        jdbcTemplate.update(
                """
                INSERT INTO session_store.spring_session (
                    primary_id, session_id, creation_time, last_access_time,
                    max_inactive_interval, expiry_time, principal_name
                ) VALUES (?, ?, ?, ?, 1800, ?, NULL)
                """,
                primaryId.toString(),
                sessionId,
                now,
                now,
                expiry.toEpochMilli()
        );
    }
}
