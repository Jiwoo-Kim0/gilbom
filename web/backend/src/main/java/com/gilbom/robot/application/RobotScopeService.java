package com.gilbom.robot.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RobotScopeService {

    static final String VISIBLE_ROBOT_PREDICATE = """
            (
                EXISTS (
                    SELECT 1
                    FROM auth.user_role user_role
                    JOIN auth.role role
                      ON role.role_id = user_role.role_id
                    WHERE user_role.user_id = :userId
                      AND user_role.is_active = TRUE
                      AND role.role_code = 'ADMIN'
                      AND role.is_active = TRUE
                )
                OR EXISTS (
                    SELECT 1
                    FROM auth.user_robot_assignment assignment
                    WHERE assignment.user_id = :userId
                      AND assignment.robot_id = r.robot_id
                      AND assignment.is_active = TRUE
                      AND (
                          assignment.expires_at IS NULL
                          OR assignment.expires_at > CURRENT_TIMESTAMP
                      )
                )
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RobotScopeService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public boolean canView(UUID userId, UUID robotId) {
        Boolean allowed = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.robot r
                    WHERE r.robot_id = :robotId
                      AND
                """ + VISIBLE_ROBOT_PREDICATE + """
                )
                """,
                Map.of(
                        "userId", userId,
                        "robotId", robotId),
                Boolean.class);
        return Boolean.TRUE.equals(allowed);
    }
}
