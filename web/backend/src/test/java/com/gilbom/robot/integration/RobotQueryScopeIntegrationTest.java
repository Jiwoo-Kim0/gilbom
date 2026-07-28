package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.application.RobotQueryService;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.data.application.command.TelemetryIngestionCommand;
import com.gilbom.robot.data.mqtt.v2.common.BatteryState;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.realtime.RobotRealtimeEventListener;
import com.gilbom.robot.realtime.RobotSseService;
import com.gilbom.robot.telemetry.TelemetryIngestionService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = RobotQueryScopeIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class RobotQueryScopeIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID ADMIN_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000021");
    private static final UUID VIEWER_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000022");
    private static final UUID ASSIGNED_ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2121");
    private static final UUID OUT_OF_SCOPE_ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2122");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_robot_query_test")
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RobotQueryService queryService;

    @Autowired
    private TelemetryIngestionService telemetryIngestionService;

    @MockitoBean
    private RobotSseService sseService;

    @BeforeEach
    void setUpScopeFixtures() {
        createUser(ADMIN_USER_ID, "QUERY-ADMIN");
        createUser(VIEWER_USER_ID, "QUERY-VIEWER");
        assignAdminRole(ADMIN_USER_ID);
        createRobot(ASSIGNED_ROBOT_ID, "RBT-021", "Assigned Robot");
        createRobot(OUT_OF_SCOPE_ROBOT_ID, "RBT-022", "Hidden Robot");
        createCurrentState(ASSIGNED_ROBOT_ID, 127.01, 37.51);
        createCurrentState(OUT_OF_SCOPE_ROBOT_ID, 127.02, 37.52);
        assignRobot(VIEWER_USER_ID, ASSIGNED_ROBOT_ID);
    }

    @Test
    void appliesSameSqlScopeToListSummaryMapAndDetail() {
        var viewerList = queryService.findRobots(
                VIEWER_USER_ID,
                0,
                100,
                null);
        assertThat(viewerList.totalElements()).isEqualTo(1);
        assertThat(viewerList.content())
                .extracting(item -> item.robotId())
                .containsExactly(ASSIGNED_ROBOT_ID);

        var viewerSummary = queryService.summarize(VIEWER_USER_ID);
        assertThat(viewerSummary.totalRobots()).isEqualTo(1);
        assertThat(viewerSummary.onlineRobots()).isEqualTo(1);

        var viewerMap = queryService.findMapRobots(VIEWER_USER_ID);
        assertThat(viewerMap.robots())
                .extracting(marker -> marker.robotId())
                .containsExactly(ASSIGNED_ROBOT_ID);

        assertThat(queryService.findRobot(
                VIEWER_USER_ID,
                ASSIGNED_ROBOT_ID).mqttId())
                .isEqualTo("RBT-021");
        assertThatThrownBy(() -> queryService.findRobot(
                VIEWER_USER_ID,
                OUT_OF_SCOPE_ROBOT_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ROBOT_NOT_FOUND));

        var adminList = queryService.findRobots(
                ADMIN_USER_ID,
                0,
                100,
                null);
        assertThat(adminList.totalElements()).isEqualTo(2);
    }

    @Test
    void publishesScopedSseEventAfterTelemetryTransactionCommits() {
        Instant sampledAt = Instant.parse("2026-07-28T02:00:00Z");
        TelemetryPayload payload = new TelemetryPayload(
                "2.0",
                UUID.fromString("7750afcb-d682-4ef6-a90d-f63fcf5c2121"),
                "RBT-021",
                UUID.fromString("6750afcb-d682-4ef6-a90d-f63fcf5c2121"),
                1L,
                sampledAt,
                null,
                new TelemetryPayload.Position(
                        true,
                        37.51,
                        127.01,
                        90.0),
                new TelemetryPayload.Motion(true, 0.8),
                new TelemetryPayload.Battery(
                        true,
                        79.0,
                        BatteryState.NORMAL,
                        TelemetryPayload.Source.MEASURED,
                        24.0,
                        1.2,
                        false,
                        41.0,
                        3600),
                new TelemetryPayload.SystemMetrics(52.0),
                null);

        telemetryIngestionService.ingest(new TelemetryIngestionCommand(
                payload,
                "{}",
                sampledAt.plusSeconds(1)));

        verify(sseService).publish(argThat(event ->
                event.robotId().equals(ASSIGNED_ROBOT_ID)
                        && event.mqttId().equals("RBT-021")
                        && event.eventType().equals(
                                "ROBOT_TELEMETRY_UPDATED")
                        && event.observedAt().equals(sampledAt)));
    }

    private void createUser(UUID userId, String suffix) {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    password_hash,
                    display_name,
                    email,
                    account_status,
                    is_active,
                    password_change_required,
                    password_changed_at,
                    account_origin
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    'ACTIVE', TRUE, FALSE, NOW(), 'ADMIN_CREATED'
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                userId,
                "USR-" + suffix,
                "{argon2}$argon2id$integration-test-" + suffix,
                "Robot query " + suffix,
                suffix.toLowerCase() + "@example.invalid");
    }

    private void assignAdminRole(UUID userId) {
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
                ON CONFLICT (user_id, role_id) DO NOTHING
                """,
                userId,
                userId);
    }

    private void createRobot(
            UUID robotId,
            String mqttId,
            String robotName
    ) {
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (robot_id) DO NOTHING
                """,
                robotId,
                mqttId,
                robotName,
                "SN-" + mqttId,
                ADMIN_USER_ID);
    }

    private void createCurrentState(
            UUID robotId,
            double longitude,
            double latitude
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_current_state (
                    robot_id,
                    connectivity_status,
                    last_received_at,
                    battery_valid,
                    battery_percent,
                    battery_state,
                    battery_source,
                    position_valid,
                    position,
                    overall_health_status
                ) VALUES (
                    ?,
                    'ONLINE',
                    NOW(),
                    TRUE,
                    80.0,
                    'NORMAL',
                    'MEASURED',
                    TRUE,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326),
                    'OK'
                )
                ON CONFLICT (robot_id) DO NOTHING
                """,
                robotId,
                longitude,
                latitude);
    }

    private void assignRobot(UUID userId, UUID robotId) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id,
                    robot_id,
                    assignment_type,
                    reason,
                    assigned_by
                ) VALUES (?, ?, 'VIEWER', 'robot query integration test', ?)
                ON CONFLICT (user_id, robot_id)
                    WHERE is_active = TRUE
                    DO NOTHING
                """,
                userId,
                robotId,
                ADMIN_USER_ID);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            RobotQueryService.class,
            RobotScopeService.class,
            TelemetryIngestionService.class,
            RobotRealtimeEventListener.class
    })
    static class TestApplication {
    }
}
