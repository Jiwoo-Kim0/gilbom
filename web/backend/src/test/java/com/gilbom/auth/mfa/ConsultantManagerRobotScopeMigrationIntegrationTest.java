package com.gilbom.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.robot.application.RobotQueryService;
import com.gilbom.robot.application.RobotScopeService;

@Testcontainers(disabledWithoutDocker = true)
class ConsultantManagerRobotScopeMigrationIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID CONSULTANT_USER_ID =
            UUID.fromString("39000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID =
            UUID.fromString("40000000-0000-4000-8000-000000000040");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_consultant_scope_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    @Tag("security-acceptance")
    void v40BackfillsExistingRobotsWithoutGrantingAdminRole() {
        DataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        migrate(dataSource, MigrationVersion.fromVersion("39"));

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (?, 'RBT-CONSULTANT-040', 'Scope migration robot',
                          'SN-CONSULTANT-040', 'ACTIVE', ?)
                """,
                ROBOT_ID,
                CONSULTANT_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_current_state (robot_id)
                VALUES (?)
                """,
                ROBOT_ID);

        NamedParameterJdbcTemplate namedJdbcTemplate =
                new NamedParameterJdbcTemplate(dataSource);
        RobotQueryService queryService =
                new RobotQueryService(namedJdbcTemplate);
        assertThat(queryService.findRobots(
                CONSULTANT_USER_ID, 0, 20, null
        ).totalElements()).isZero();

        migrate(dataSource, null);

        assertThat(queryService.findRobots(
                CONSULTANT_USER_ID, 0, 20, null
        ).content())
                .extracting(item -> item.robotId())
                .containsExactly(ROBOT_ID);
        assertThat(new RobotScopeService(namedJdbcTemplate).canOperate(
                CONSULTANT_USER_ID,
                ROBOT_ID
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM auth.user_role user_role
                JOIN auth.role role USING (role_id)
                WHERE user_role.user_id = ?
                  AND user_role.is_active = TRUE
                  AND role.role_code = 'ADMIN'
                """,
                Integer.class,
                CONSULTANT_USER_ID)).isZero();
    }

    private void migrate(
            DataSource dataSource,
            MigrationVersion target
    ) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("flyway_history")
                .placeholders(Map.of(
                        "applicationRole",
                        DATABASE.getUsername()
                ));
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
