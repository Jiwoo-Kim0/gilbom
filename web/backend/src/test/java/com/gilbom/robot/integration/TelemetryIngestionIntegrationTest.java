package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.gilbom.robot.data.application.command.TelemetryIngestionCommand;
import com.gilbom.robot.data.application.result.TelemetryIngestionResult;
import com.gilbom.robot.data.mqtt.v2.common.BatteryState;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.realtime.RobotRealtimeEventListener;
import com.gilbom.robot.realtime.RobotSseService;
import com.gilbom.robot.telemetry.TelemetryIngestionService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = TelemetryIngestionIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class TelemetryIngestionIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID ADMIN_USER_ID =
            UUID.fromString("18000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID =
            UUID.fromString("8850afcb-d682-4ef6-a90d-f63fcf5c2101");
    private static final UUID BOOT_ID =
            UUID.fromString("6850afcb-d682-4ef6-a90d-f63fcf5c2101");
    private static final Instant BASE_SAMPLED_AT =
            Instant.parse("2026-07-28T02:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_telemetry_ingestion_test")
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
    private TelemetryIngestionService ingestionService;

    @MockitoBean
    private RobotSseService sseService;

    @BeforeEach
    void setUpRobot() {
        jdbcTemplate.update(
                "DELETE FROM telemetry.robot_telemetry WHERE robot_id = ?",
                ROBOT_ID);
        jdbcTemplate.update(
                "DELETE FROM telemetry.robot_current_state WHERE robot_id = ?",
                ROBOT_ID);
        jdbcTemplate.update(
                "DELETE FROM telemetry.robot_boot_session WHERE robot_id = ?",
                ROBOT_ID);
        jdbcTemplate.update(
                "DELETE FROM robot.robot WHERE robot_id = ?",
                ROBOT_ID);
        createAdmin();
        createRobot();
        createCurrentState();
        clearInvocations(sseService);
    }

    @Test
    void storesHistoryAndCurrentStateWithSeparateObservedAndReceivedTimes() {
        Instant receivedAt = BASE_SAMPLED_AT.plusSeconds(3);
        TelemetryPayload payload = payload(
                UUID.fromString("7850afcb-d682-4ef6-a90d-f63fcf5c2101"),
                BOOT_ID,
                1L,
                BASE_SAMPLED_AT,
                79.0);

        TelemetryIngestionResult result = ingest(payload, receivedAt);

        assertThat(result.historyInserted()).isTrue();
        assertThat(result.currentStateUpdated()).isTrue();
        assertThat(historyCount()).isEqualTo(1);
        assertThat(historyEpoch("sampled_at"))
                .isEqualTo(BASE_SAMPLED_AT.getEpochSecond());
        assertThat(historyEpoch("received_at"))
                .isEqualTo(receivedAt.getEpochSecond());
        assertThat(currentSequence()).isEqualTo(1L);
        assertThat(currentBatteryPercent()).isEqualTo(79.0);
        assertThat(currentEpoch("telemetry_sampled_at"))
                .isEqualTo(BASE_SAMPLED_AT.getEpochSecond());
        assertThat(currentEpoch("telemetry_received_at"))
                .isEqualTo(receivedAt.getEpochSecond());
        verify(sseService).publish(argThat(event ->
                event.robotId().equals(ROBOT_ID)
                        && event.mqttId().equals("RBT-101")
                        && event.observedAt().equals(BASE_SAMPLED_AT)));
    }

    @Test
    void deduplicatesByMessageIdAndRobotBootSequenceWithoutRepublishing() {
        UUID messageId =
                UUID.fromString("7850afcb-d682-4ef6-a90d-f63fcf5c2102");
        TelemetryPayload original = payload(
                messageId,
                BOOT_ID,
                1L,
                BASE_SAMPLED_AT,
                79.0);
        ingest(original, BASE_SAMPLED_AT.plusSeconds(1));
        clearInvocations(sseService);

        TelemetryIngestionResult sameMessage = ingest(
                original,
                BASE_SAMPLED_AT.plusSeconds(2));
        TelemetryIngestionResult sameSequence = ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2103"),
                        BOOT_ID,
                        1L,
                        BASE_SAMPLED_AT.plusSeconds(1),
                        78.0),
                BASE_SAMPLED_AT.plusSeconds(3));

        assertThat(sameMessage)
                .isEqualTo(new TelemetryIngestionResult(false, false));
        assertThat(sameSequence)
                .isEqualTo(new TelemetryIngestionResult(false, false));
        assertThat(historyCount()).isEqualTo(1);
        assertThat(currentSequence()).isEqualTo(1L);
        assertThat(currentBatteryPercent()).isEqualTo(79.0);
        verifyNoInteractions(sseService);
    }

    @Test
    void storesLateHistoryWithoutOverwritingOrRepublishingCurrentState() {
        ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2104"),
                        BOOT_ID,
                        2L,
                        BASE_SAMPLED_AT.plusSeconds(10),
                        80.0),
                BASE_SAMPLED_AT.plusSeconds(11));
        clearInvocations(sseService);

        TelemetryIngestionResult staleResult = ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2105"),
                        BOOT_ID,
                        3L,
                        BASE_SAMPLED_AT.plusSeconds(5),
                        20.0),
                BASE_SAMPLED_AT.plusSeconds(12));

        assertThat(staleResult.historyInserted()).isTrue();
        assertThat(staleResult.currentStateUpdated()).isFalse();
        assertThat(historyCount()).isEqualTo(2);
        assertThat(currentSequence()).isEqualTo(2L);
        assertThat(currentBatteryPercent()).isEqualTo(80.0);
        assertThat(currentEpoch("telemetry_sampled_at"))
                .isEqualTo(BASE_SAMPLED_AT.plusSeconds(10).getEpochSecond());
        verifyNoInteractions(sseService);
    }

    @Test
    void acceptsSequenceOneForNewBootAndRejectsPreviousBootReplay() {
        UUID newBootId =
                UUID.fromString("6850afcb-d682-4ef6-a90d-f63fcf5c2202");
        ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2201"),
                        BOOT_ID,
                        50L,
                        BASE_SAMPLED_AT,
                        79.0),
                BASE_SAMPLED_AT.plusSeconds(1));

        TelemetryIngestionResult newBoot = ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2202"),
                        newBootId,
                        1L,
                        BASE_SAMPLED_AT.plusSeconds(10),
                        80.0),
                BASE_SAMPLED_AT.plusSeconds(11));
        clearInvocations(sseService);

        TelemetryIngestionResult previousBootReplay = ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2203"),
                        BOOT_ID,
                        51L,
                        BASE_SAMPLED_AT.plusSeconds(20),
                        10.0),
                BASE_SAMPLED_AT.plusSeconds(21));

        assertThat(newBoot)
                .isEqualTo(new TelemetryIngestionResult(true, true));
        assertThat(previousBootReplay)
                .isEqualTo(new TelemetryIngestionResult(true, false));
        assertThat(currentBootId()).isEqualTo(newBootId);
        assertThat(currentSequence()).isEqualTo(1L);
        assertThat(currentBatteryPercent()).isEqualTo(80.0);
        verifyNoInteractions(sseService);
    }

    @Test
    void rejectsDisabledRobotWithoutWritingHistory() {
        jdbcTemplate.update("""
                UPDATE robot.robot
                SET operational_status = 'DISABLED',
                    updated_at = NOW()
                WHERE robot_id = ?
                """,
                ROBOT_ID);

        assertThatThrownBy(() -> ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2106"),
                        BOOT_ID,
                        1L,
                        BASE_SAMPLED_AT,
                        79.0),
                BASE_SAMPLED_AT.plusSeconds(1)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ROBOT_NOT_FOUND));
        assertThat(historyCount()).isZero();
        verifyNoInteractions(sseService);
    }

    @Test
    void rollsBackHistoryWhenRegisteredRobotHasNoCurrentStateRow() {
        jdbcTemplate.update(
                "DELETE FROM telemetry.robot_current_state WHERE robot_id = ?",
                ROBOT_ID);

        assertThatThrownBy(() -> ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2107"),
                        BOOT_ID,
                        1L,
                        BASE_SAMPLED_AT,
                        79.0),
                BASE_SAMPLED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current-state row");
        assertThat(historyCount()).isZero();
        verifyNoInteractions(sseService);
    }

    @Test
    void databaseRejectsSequenceZeroEvenIfServiceIsCalledWithoutParser() {
        assertThatThrownBy(() -> ingest(
                payload(
                        UUID.fromString(
                                "7850afcb-d682-4ef6-a90d-f63fcf5c2108"),
                        BOOT_ID,
                        0L,
                        BASE_SAMPLED_AT,
                        79.0),
                BASE_SAMPLED_AT.plusSeconds(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(historyCount()).isZero();
        assertThat(currentSequence()).isNull();
        verifyNoInteractions(sseService);
    }

    private TelemetryIngestionResult ingest(
            TelemetryPayload payload,
            Instant receivedAt
    ) {
        return ingestionService.ingest(new TelemetryIngestionCommand(
                payload,
                "{}",
                receivedAt));
    }

    private TelemetryPayload payload(
            UUID messageId,
            UUID bootId,
            long sequence,
            Instant sampledAt,
            double batteryPercent
    ) {
        return new TelemetryPayload(
                "2.0",
                messageId,
                "RBT-101",
                bootId,
                sequence,
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
                        batteryPercent,
                        BatteryState.NORMAL,
                        TelemetryPayload.Source.MEASURED,
                        24.0,
                        1.2,
                        false,
                        41.0,
                        3600),
                new TelemetryPayload.SystemMetrics(52.0),
                null);
    }

    private int historyCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry.robot_telemetry "
                        + "WHERE robot_id = ?",
                Integer.class,
                ROBOT_ID);
    }

    private long historyEpoch(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM " + column
                        + ")::BIGINT FROM telemetry.robot_telemetry "
                        + "WHERE robot_id = ?",
                Long.class,
                ROBOT_ID);
    }

    private Long currentSequence() {
        return jdbcTemplate.queryForObject(
                "SELECT telemetry_seq FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                Long.class,
                ROBOT_ID);
    }

    private UUID currentBootId() {
        return jdbcTemplate.queryForObject(
                "SELECT current_boot_id FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                UUID.class,
                ROBOT_ID);
    }

    private double currentBatteryPercent() {
        return jdbcTemplate.queryForObject(
                "SELECT battery_percent::DOUBLE PRECISION "
                        + "FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                Double.class,
                ROBOT_ID);
    }

    private long currentEpoch(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM " + column
                        + ")::BIGINT FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                Long.class,
                ROBOT_ID);
    }

    private void createAdmin() {
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
                    ?,
                    'TELEMETRY-ADMIN',
                    '{argon2}$argon2id$integration-test-telemetry',
                    'Telemetry Admin',
                    'telemetry-admin@example.com',
                    'ACTIVE',
                    TRUE,
                    FALSE,
                    NOW(),
                    'ADMIN_CREATED'
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                ADMIN_USER_ID);
    }

    private void createRobot() {
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (
                    ?,
                    'RBT-101',
                    'Telemetry Robot',
                    'SN-TELEMETRY-101',
                    'ACTIVE',
                    ?
                )
                """,
                ROBOT_ID,
                ADMIN_USER_ID);
    }

    private void createCurrentState() {
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_current_state (
                    robot_id,
                    connectivity_status
                ) VALUES (?, 'OFFLINE')
                """,
                ROBOT_ID);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            TelemetryIngestionService.class,
            RobotRealtimeEventListener.class
    })
    static class TestApplication {
    }
}
