package com.gilbom.robot.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Service
public class RobotScopeService {

    /**
     * 시스템 ADMIN은 전체 객체 범위를 가진다. 로봇에서 파생된 리소스가 아직
     * 로봇에 연결되지 않은 경우에도 ADMIN 범위는 성립해야 하므로 조건절을
     * 별도로 공개한다. 이 조건절은 {@code userId}만 바인딩하면 된다.
     */
    public static final String SYSTEM_ADMIN_SCOPE = """
            EXISTS (
                SELECT 1
                FROM auth.admin_user admin_user
                JOIN auth.user_role user_role
                  ON user_role.user_id = admin_user.user_id
                JOIN auth.role role
                  ON role.role_id = user_role.role_id
                WHERE admin_user.user_id = :userId
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND admin_user.deleted_at IS NULL
                  AND user_role.is_active = TRUE
                  AND role.role_code = 'ADMIN'
                  AND role.is_system = TRUE
                  AND role.is_active = TRUE
            )
            """;

    /**
     * 다른 도메인도 로봇에서 파생된 리소스의 범위를 같은 규칙으로 판정해야 하므로
     * 조건절을 공개한다. 사용하는 질의는 로봇 테이블을 {@code r}로 alias 하고
     * {@code userId} 파라미터를 바인딩해야 한다.
     */
    public static final String VISIBLE_ROBOT_PREDICATE = """
            (
            """ + SYSTEM_ADMIN_SCOPE + """
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

    public static final String OPERABLE_ROBOT_PREDICATE = """
            (
            """ + SYSTEM_ADMIN_SCOPE + """
                OR EXISTS (
                    SELECT 1
                    FROM auth.user_robot_assignment assignment
                    WHERE assignment.user_id = :userId
                      AND assignment.robot_id = r.robot_id
                      AND assignment.assignment_type IN (
                          'OPERATOR',
                          'OWNER'
                      )
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

    @Transactional(readOnly = true)
    public boolean canOperate(UUID userId, UUID robotId) {
        return hasRobotScope(
                "r.robot_id = :robotId",
                Map.of(
                        "userId", userId,
                        "robotId", robotId
                ),
                OPERABLE_ROBOT_PREDICATE
        );
    }

    @Transactional(readOnly = true)
    public String requireOperableMqttId(
            UUID userId,
            String mqttId
    ) {
        List<String> visibleIds = jdbcTemplate.queryForList("""
                SELECT r.mqtt_id
                FROM robot.robot r
                WHERE r.mqtt_id = :mqttId
                  AND
                """ + OPERABLE_ROBOT_PREDICATE,
                Map.of(
                        "userId", userId,
                        "mqttId", mqttId
                ),
                String.class);
        if (visibleIds.isEmpty()) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        return visibleIds.getFirst();
    }

    private boolean hasRobotScope(
            String targetPredicate,
            Map<String, ?> parameters,
            String scopePredicate
    ) {
        Boolean allowed = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM robot.robot r
                    WHERE
                """ + targetPredicate + """
                      AND
                """ + scopePredicate + """
                )
                """,
                parameters,
                Boolean.class);
        return Boolean.TRUE.equals(allowed);
    }
}
