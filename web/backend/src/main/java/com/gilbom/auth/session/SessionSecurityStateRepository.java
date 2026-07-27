package com.gilbom.auth.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gilbom.auth.model.AuthenticationState;

@Repository
public class SessionSecurityStateRepository {

    private final JdbcTemplate jdbcTemplate;

    public SessionSecurityStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SessionSecurityState> findBySessionId(String sessionId) {
        List<SessionSecurityState> states = jdbcTemplate.query(
                """
                SELECT
                    security_state.user_id,
                    security_state.authentication_state,
                    security_state.absolute_expiry_time,
                    security_state.authentication_challenge_started_time,
                    security_state.authentication_challenge_expires_at,
                    security_state.authentication_challenge_failure_count
                FROM session_store.spring_session session
                JOIN session_store.session_security_state security_state
                  ON security_state.session_primary_id = session.primary_id
                WHERE session.session_id = ?
                """,
                (resultSet, rowNumber) -> mapState(resultSet),
                sessionId
        );
        return states.stream().findFirst();
    }

    public boolean createChallenge(
            String sessionId,
            UUID userId,
            AuthenticationState nextState,
            Instant now,
            Instant challengeExpiresAt
    ) {
        int affected = jdbcTemplate.update(
                """
                INSERT INTO session_store.session_security_state (
                    session_primary_id,
                    user_id,
                    authentication_state,
                    absolute_expiry_time,
                    authentication_challenge_expires_at,
                    authentication_challenge_failure_count,
                    created_time,
                    updated_time,
                    authentication_challenge_started_time
                )
                SELECT
                    session.primary_id,
                    ?,
                    ?,
                    ?,
                    ?,
                    0,
                    ?,
                    ?,
                    ?
                FROM session_store.spring_session session
                WHERE session.session_id = ?
                ON CONFLICT (session_primary_id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    authentication_state = EXCLUDED.authentication_state,
                    absolute_expiry_time = EXCLUDED.absolute_expiry_time,
                    authentication_challenge_expires_at =
                        EXCLUDED.authentication_challenge_expires_at,
                    authentication_challenge_failure_count = 0,
                    updated_time = EXCLUDED.updated_time,
                    authentication_challenge_started_time =
                        EXCLUDED.authentication_challenge_started_time
                WHERE session_security_state.authentication_state IN (
                    'PASSWORD_CHANGE_REQUIRED',
                    'MFA_ENROLLMENT_REQUIRED',
                    'MFA_REQUIRED'
                )
                """,
                userId,
                nextState.name(),
                challengeExpiresAt.toEpochMilli(),
                challengeExpiresAt.toEpochMilli(),
                now.toEpochMilli(),
                now.toEpochMilli(),
                now.toEpochMilli(),
                sessionId
        );
        return affected == 1;
    }

    public boolean transitionChallenge(
            String sessionId,
            UUID userId,
            AuthenticationState currentState,
            AuthenticationState nextState,
            Instant now
    ) {
        return jdbcTemplate.update(
                """
                UPDATE session_store.session_security_state security_state
                SET authentication_state = ?,
                    updated_time = ?
                FROM session_store.spring_session session
                WHERE session.primary_id = security_state.session_primary_id
                  AND session.session_id = ?
                  AND security_state.user_id = ?
                  AND security_state.authentication_state = ?
                """,
                nextState.name(),
                now.toEpochMilli(),
                sessionId,
                userId,
                currentState.name()
        ) == 1;
    }

    public boolean promoteAuthenticated(
            String sessionId,
            UUID userId,
            AuthenticationState currentState,
            Instant now,
            Instant absoluteExpiresAt,
            int idleTimeoutSeconds
    ) {
        lockUser(userId);
        deleteOtherUserSessions(sessionId, userId);

        int stateUpdated = jdbcTemplate.update(
                """
                UPDATE session_store.session_security_state security_state
                SET authentication_state = 'AUTHENTICATED',
                    absolute_expiry_time = ?,
                    authentication_challenge_started_time = NULL,
                    authentication_challenge_expires_at = NULL,
                    authentication_challenge_failure_count = 0,
                    updated_time = ?
                FROM session_store.spring_session session
                WHERE session.primary_id = security_state.session_primary_id
                  AND session.session_id = ?
                  AND security_state.user_id = ?
                  AND security_state.authentication_state = ?
                """,
                absoluteExpiresAt.toEpochMilli(),
                now.toEpochMilli(),
                sessionId,
                userId,
                currentState.name()
        );
        if (stateUpdated != 1) {
            return false;
        }

        int sessionUpdated = jdbcTemplate.update(
                """
                UPDATE session_store.spring_session
                SET principal_name = ?,
                    max_inactive_interval = ?,
                    expiry_time = LEAST(?, ?)
                WHERE session_id = ?
                """,
                userId.toString(),
                idleTimeoutSeconds,
                now.plusSeconds(idleTimeoutSeconds).toEpochMilli(),
                absoluteExpiresAt.toEpochMilli(),
                sessionId
        );
        return sessionUpdated == 1;
    }

    public Optional<Integer> incrementChallengeFailure(
            String sessionId,
            UUID userId,
            AuthenticationState currentState,
            int maximumFailures,
            Instant now
    ) {
        List<Integer> failureCounts = jdbcTemplate.query(
                """
                UPDATE session_store.session_security_state security_state
                SET authentication_challenge_failure_count =
                        authentication_challenge_failure_count + 1,
                    updated_time = ?
                FROM session_store.spring_session session
                WHERE session.primary_id = security_state.session_primary_id
                  AND session.session_id = ?
                  AND security_state.user_id = ?
                  AND security_state.authentication_state = ?
                  AND security_state.authentication_challenge_failure_count < ?
                  AND security_state.authentication_challenge_expires_at > ?
                RETURNING authentication_challenge_failure_count
                """,
                (resultSet, rowNumber) -> resultSet.getInt(1),
                now.toEpochMilli(),
                sessionId,
                userId,
                currentState.name(),
                maximumFailures,
                now.toEpochMilli()
        );
        return failureCounts.stream().findFirst();
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update(
                """
                DELETE FROM session_store.spring_session
                WHERE session_id = ?
                """,
                sessionId
        );
    }

    public int deleteAllByUserId(UUID userId) {
        return jdbcTemplate.update(
                """
                DELETE FROM session_store.spring_session session
                USING session_store.session_security_state security_state
                WHERE security_state.session_primary_id = session.primary_id
                  AND security_state.user_id = ?
                """,
                userId
        );
    }

    public int deleteOtherByUserId(
            String currentSessionId,
            UUID userId
    ) {
        return jdbcTemplate.update(
                """
                DELETE FROM session_store.spring_session session
                USING session_store.session_security_state security_state
                WHERE security_state.session_primary_id = session.primary_id
                  AND security_state.user_id = ?
                  AND session.session_id <> ?
                """,
                userId,
                currentSessionId
        );
    }

    public int deleteExpiredSecuritySessions(int batchSize, Instant now) {
        return jdbcTemplate.update(
                """
                WITH expired AS (
                    SELECT security_state.session_primary_id
                    FROM session_store.session_security_state security_state
                    WHERE security_state.absolute_expiry_time <= ?
                       OR security_state.authentication_challenge_expires_at <= ?
                    ORDER BY COALESCE(
                        security_state.authentication_challenge_expires_at,
                        security_state.absolute_expiry_time
                    )
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM session_store.spring_session session
                USING expired
                WHERE session.primary_id = expired.session_primary_id
                """,
                now.toEpochMilli(),
                now.toEpochMilli(),
                batchSize
        );
    }

    private void lockUser(UUID userId) {
        jdbcTemplate.queryForObject(
                """
                SELECT user_id
                FROM auth.admin_user
                WHERE user_id = ?
                FOR UPDATE
                """,
                UUID.class,
                userId
        );
    }

    private int deleteOtherUserSessions(
            String currentSessionId,
            UUID userId
    ) {
        return jdbcTemplate.update(
                """
                DELETE FROM session_store.spring_session session
                USING session_store.session_security_state security_state
                WHERE security_state.session_primary_id = session.primary_id
                  AND security_state.user_id = ?
                  AND session.session_id <> ?
                """,
                userId,
                currentSessionId
        );
    }

    private static SessionSecurityState mapState(ResultSet resultSet)
            throws SQLException {
        return new SessionSecurityState(
                resultSet.getObject("user_id", UUID.class),
                AuthenticationState.valueOf(
                        resultSet.getString("authentication_state")),
                nullableInstant(resultSet, "absolute_expiry_time"),
                nullableInstant(
                        resultSet,
                        "authentication_challenge_started_time"),
                nullableInstant(
                        resultSet,
                        "authentication_challenge_expires_at"),
                resultSet.getInt("authentication_challenge_failure_count")
        );
    }

    private static Instant nullableInstant(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
