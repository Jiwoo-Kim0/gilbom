package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.telemetry.RobotTelemetrySeriesService;
import com.gilbom.robot.telemetry.RobotTelemetryStatisticsService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = RobotTelemetryStatisticsServiceIntegrationTest
                .TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class RobotTelemetryStatisticsServiceIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID VIEWER_USER_ID =
            UUID.fromString("27000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("27000000-0000-4000-8000-000000000002");
    private static final Instant FROM =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO =
            Instant.parse("2026-08-01T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_robot_telemetry_statistics_test")
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
    private RobotTelemetryStatisticsService statisticsService;

    @Autowired
    private RobotTelemetrySeriesService seriesService;

    private UUID visibleRobotId;
    private UUID hiddenRobotId;
    private UUID visibleBootId;
    private UUID hiddenBootId;

    @BeforeEach
    void createFixture() {
        visibleRobotId = UUID.randomUUID();
        hiddenRobotId = UUID.randomUUID();
        visibleBootId = UUID.randomUUID();
        hiddenBootId = UUID.randomUUID();

        createUser(VIEWER_USER_ID, "TELEMETRY-STATS-VIEWER");
        createUser(OTHER_USER_ID, "TELEMETRY-STATS-OTHER");
        createRobot(visibleRobotId, "Visible telemetry robot");
        createRobot(hiddenRobotId, "Hidden telemetry robot");
        assignRobot(VIEWER_USER_ID, visibleRobotId);
        createBootSession(visibleRobotId, visibleBootId);
        createBootSession(hiddenRobotId, hiddenBootId);

        insertTelemetry(
                visibleRobotId,
                visibleBootId,
                1,
                "2026-07-05T01:00:00Z",
                true,
                true,
                80.0,
                50.0,
                1.0);
        insertTelemetry(
                visibleRobotId,
                visibleBootId,
                2,
                "2026-07-10T01:00:00Z",
                false,
                true,
                60.0,
                70.0,
                3.0);
        insertTelemetry(
                visibleRobotId,
                visibleBootId,
                3,
                "2026-07-15T01:00:00Z",
                true,
                false,
                null,
                null,
                null);
        insertTelemetry(
                visibleRobotId,
                visibleBootId,
                4,
                "2026-06-01T01:00:00Z",
                true,
                true,
                10.0,
                99.0,
                9.0);
        insertTelemetry(
                hiddenRobotId,
                hiddenBootId,
                1,
                "2026-07-07T01:00:00Z",
                true,
                true,
                100.0,
                100.0,
                10.0);
    }

    @Test
    void aggregatesOnlyVisibleRobotTelemetryInsideHalfOpenPeriod() {
        var response = statisticsService.find(
                VIEWER_USER_ID,
                visibleRobotId,
                FROM,
                TO);

        assertThat(response.robotId()).isEqualTo(visibleRobotId);
        assertThat(response.sampleCount()).isEqualTo(3);
        assertThat(response.validBatterySampleCount()).isEqualTo(2);
        assertThat(response.averageBatteryPercent())
                .isEqualByComparingTo("70.00");
        assertThat(response.minimumBatteryPercent())
                .isEqualByComparingTo("60.00");
        assertThat(response.averageJetsonTemperatureC())
                .isEqualByComparingTo("60.00");
        assertThat(response.maximumJetsonTemperatureC())
                .isEqualByComparingTo("70.00");
        assertThat(response.averageSpeedMps())
                .isEqualByComparingTo("2.00");
        assertThat(response.maximumSpeedMps())
                .isEqualByComparingTo("3.00");
        assertThat(response.validGpsSampleCount()).isEqualTo(2);
        assertThat(response.invalidGpsSampleCount()).isEqualTo(1);
        assertThat(response.gpsValidRatePercent())
                .isEqualByComparingTo("66.67");
        assertThat(response.lastSampledAt())
                .isEqualTo(Instant.parse("2026-07-15T01:00:00Z"));
    }

    @Test
    void groupsVisibleRobotTelemetryIntoOrderedTimeBuckets() {
        insertTelemetry(
                visibleRobotId,
                visibleBootId,
                5,
                "2026-07-05T01:30:00Z",
                false,
                true,
                60.0,
                70.0,
                3.0);

        var response = seriesService.find(
                VIEWER_USER_ID,
                visibleRobotId,
                FROM,
                TO,
                "1H");

        assertThat(response.robotId()).isEqualTo(visibleRobotId);
        assertThat(response.interval()).isEqualTo("1h");
        assertThat(response.points()).hasSize(3);
        var firstPoint = response.points().getFirst();
        assertThat(firstPoint.bucketAt())
                .isEqualTo(Instant.parse("2026-07-05T01:00:00Z"));
        assertThat(firstPoint.sampleCount()).isEqualTo(2);
        assertThat(firstPoint.validBatterySampleCount()).isEqualTo(2);
        assertThat(firstPoint.averageBatteryPercent())
                .isEqualByComparingTo("70.00");
        assertThat(firstPoint.minimumBatteryPercent())
                .isEqualByComparingTo("60.00");
        assertThat(firstPoint.averageJetsonTemperatureC())
                .isEqualByComparingTo("60.00");
        assertThat(firstPoint.maximumJetsonTemperatureC())
                .isEqualByComparingTo("70.00");
        assertThat(firstPoint.averageSpeedMps())
                .isEqualByComparingTo("2.00");
        assertThat(firstPoint.maximumSpeedMps())
                .isEqualByComparingTo("3.00");
        assertThat(firstPoint.validGpsSampleCount()).isEqualTo(1);
        assertThat(firstPoint.invalidGpsSampleCount()).isEqualTo(1);
        assertThat(firstPoint.gpsValidRatePercent())
                .isEqualByComparingTo("50.00");
        assertThat(response.points().getLast().bucketAt())
                .isEqualTo(Instant.parse("2026-07-15T01:00:00Z"));
    }

    @Test
    void returnsEmptyTelemetrySeriesWhenNoSamplesExist() {
        jdbcTemplate.update(
                "DELETE FROM telemetry.robot_telemetry WHERE robot_id = ?",
                visibleRobotId);

        var response = seriesService.find(
                VIEWER_USER_ID,
                visibleRobotId,
                FROM,
                TO,
                "15m");

        assertThat(response.points()).isEmpty();
    }

    @Test
    void protectsSeriesScopeAndIntervalPointLimit() {
        assertThatThrownBy(() -> seriesService.find(
                VIEWER_USER_ID,
                hiddenRobotId,
                FROM,
                TO,
                "1h"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ROBOT_NOT_FOUND));

        assertThatThrownBy(() -> seriesService.find(
                VIEWER_USER_ID,
                visibleRobotId,
                FROM,
                TO,
                "1m"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.STATISTICS_PERIOD_INVALID));

        assertThatThrownBy(() -> seriesService.find(
                VIEWER_USER_ID,
                visibleRobotId,
                FROM,
                TO,
                "30m"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.COMMON_INVALID_REQUEST));
    }

    @Test
    void returnsZeroAndNullMetricsWhenNoTelemetryExists() {
        jdbcTemplate.update(
                "DELETE FROM telemetry.robot_telemetry WHERE robot_id = ?",
                visibleRobotId);

        var response = statisticsService.find(
                VIEWER_USER_ID,
                visibleRobotId,
                FROM,
                TO);

        assertThat(response.sampleCount()).isZero();
        assertThat(response.validBatterySampleCount()).isZero();
        assertThat(response.averageBatteryPercent()).isNull();
        assertThat(response.minimumBatteryPercent()).isNull();
        assertThat(response.averageJetsonTemperatureC()).isNull();
        assertThat(response.maximumJetsonTemperatureC()).isNull();
        assertThat(response.averageSpeedMps()).isNull();
        assertThat(response.maximumSpeedMps()).isNull();
        assertThat(response.validGpsSampleCount()).isZero();
        assertThat(response.invalidGpsSampleCount()).isZero();
        assertThat(response.gpsValidRatePercent())
                .isEqualByComparingTo("0.00");
        assertThat(response.lastSampledAt()).isNull();
    }

    @Test
    void hidesOutOfScopeRobotAndLimitsPeriodToThirtyOneDays() {
        assertThatThrownBy(() -> statisticsService.find(
                VIEWER_USER_ID,
                hiddenRobotId,
                FROM,
                TO))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ROBOT_NOT_FOUND));

        assertThatThrownBy(() -> statisticsService.find(
                VIEWER_USER_ID,
                visibleRobotId,
                FROM,
                FROM.plusSeconds(32L * 24 * 60 * 60)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.STATISTICS_PERIOD_INVALID));
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
                "Robot telemetry statistics " + suffix,
                suffix.toLowerCase() + "@example.invalid");
    }

    private void createRobot(UUID robotId, String robotName) {
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """,
                robotId,
                "RBT-" + robotId.toString().substring(0, 8),
                robotName,
                "SN-" + robotId,
                OTHER_USER_ID);
    }

    private void assignRobot(UUID userId, UUID robotId) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id,
                    robot_id,
                    assignment_type,
                    reason,
                    assigned_by
                ) VALUES (
                    ?, ?, 'VIEWER',
                    'robot telemetry statistics integration test',
                    ?
                )
                """,
                userId,
                robotId,
                OTHER_USER_ID);
    }

    private void createBootSession(UUID robotId, UUID bootId) {
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_boot_session (
                    boot_id,
                    robot_id,
                    schema_version,
                    session_status,
                    robot_connected_at,
                    first_received_at,
                    last_received_at,
                    last_seen_seq
                ) VALUES (
                    ?, ?, '2.0', 'ONLINE',
                    '2026-05-01T00:00:00Z',
                    '2026-05-01T00:00:00Z',
                    '2026-08-01T00:00:00Z',
                    10
                )
                """,
                bootId,
                robotId);
    }

    private void insertTelemetry(
            UUID robotId,
            UUID bootId,
            long seq,
            String sampledAt,
            boolean positionValid,
            boolean batteryValid,
            Double batteryPercent,
            Double jetsonTemperatureC,
            Double speedMps
    ) {
        OffsetDateTime sampled = OffsetDateTime.ofInstant(
                Instant.parse(sampledAt),
                ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_telemetry (
                    message_id,
                    schema_version,
                    robot_id,
                    boot_id,
                    seq,
                    sampled_at,
                    received_at,
                    position_valid,
                    position,
                    heading_deg,
                    moving,
                    speed_mps,
                    battery_valid,
                    battery_percent,
                    battery_state,
                    battery_source,
                    jetson_temp_c,
                    raw_payload
                ) VALUES (
                    ?, '2.0', ?, ?, ?, ?, ?,
                    ?,
                    CASE
                        WHEN ? THEN ST_SetSRID(
                            ST_MakePoint(127.01, 37.51),
                            4326
                        )
                        ELSE NULL
                    END,
                    NULL,
                    FALSE,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    '{}'::JSONB
                )
                """,
                UUID.randomUUID(),
                robotId,
                bootId,
                seq,
                sampled,
                sampled,
                positionValid,
                positionValid,
                speedMps,
                batteryValid,
                batteryPercent,
                batteryValid ? "NORMAL" : "UNKNOWN",
                batteryValid ? "MEASURED" : null,
                jetsonTemperatureC);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            RobotTelemetryStatisticsService.class,
            RobotTelemetrySeriesService.class
    })
    static class TestApplication {
    }
}
