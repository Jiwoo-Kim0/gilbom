package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import com.gilbom.robot.data.application.command.PresenceIngestionCommand;
import com.gilbom.robot.data.application.command.StateIngestionCommand;
import com.gilbom.robot.data.application.command.TelemetryIngestionCommand;
import com.gilbom.robot.data.application.result.RobotStatusIngestionResult;
import com.gilbom.robot.data.mqtt.v2.common.BatteryState;
import com.gilbom.robot.data.mqtt.v2.common.HealthStatus;
import com.gilbom.robot.data.mqtt.v2.common.RobotMode;
import com.gilbom.robot.data.mqtt.v2.common.RobotStateReason;
import com.gilbom.robot.data.mqtt.v2.inbound.PresencePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.StatePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.mission.MissionProgressService;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService;
import com.gilbom.robot.realtime.RobotRealtimeEventListener;
import com.gilbom.robot.realtime.RobotSseService;
import com.gilbom.robot.status.RobotOfflineDetectionService;
import com.gilbom.robot.status.RobotStatusIngestionService;
import com.gilbom.robot.telemetry.TelemetryIngestionService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = RobotStatusIngestionIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class RobotStatusIngestionIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID ADMIN_USER_ID =
            UUID.fromString("28000000-0000-4000-8000-000000000001");
    private static final Instant BASE_TIME =
            Instant.parse("2026-07-28T05:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_robot_status_test")
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
    private RobotStatusIngestionService ingestionService;

    @Autowired
    private TelemetryIngestionService telemetryIngestionService;

    @Autowired
    private RobotOfflineDetectionService offlineDetectionService;

    @MockitoBean
    private RobotSseService sseService;

    private UUID robotId;
    private String mqttId;

    @BeforeEach
    void setUpRobot() {
        jdbcTemplate.update("""
                UPDATE telemetry.robot_boot_session
                SET session_status = 'OFFLINE',
                    offline_received_at = last_received_at,
                    offline_reason = 'MIGRATED_HISTORY',
                    updated_at = GREATEST(updated_at, last_received_at)
                WHERE session_status = 'ONLINE'
                """);
        robotId = UUID.randomUUID();
        mqttId = "RBT-STATUS-" + robotId.toString().substring(0, 8);
        createAdmin();
        createRobot(robotId, mqttId, true);
        clearInvocations(sseService);
    }

    @Test
    void storesOnlinePresenceAndDeduplicatesRetainedReplay() {
        UUID bootId = UUID.randomUUID();
        PresencePayload online = online(bootId, BASE_TIME);

        RobotStatusIngestionResult first = ingestPresence(
                online,
                BASE_TIME.plusSeconds(1));
        clearInvocations(sseService);
        RobotStatusIngestionResult duplicate = ingestPresence(
                online,
                BASE_TIME.plusSeconds(2));

        assertThat(first)
                .isEqualTo(new RobotStatusIngestionResult(true, true));
        assertThat(duplicate)
                .isEqualTo(new RobotStatusIngestionResult(false, false));
        assertThat(currentBootId()).isEqualTo(bootId);
        assertThat(currentConnectivity()).isEqualTo("ONLINE");
        assertThat(bootStatus(bootId)).isEqualTo("ONLINE");
        assertThat(historyCount("PRESENCE")).isEqualTo(1);
        verifyNoInteractions(sseService);
    }

    @Test
    void storesStateAndKeepsLateStateOutOfCurrentReadModel() {
        UUID bootId = UUID.randomUUID();
        ingestPresence(online(bootId, BASE_TIME), BASE_TIME.plusSeconds(1));
        clearInvocations(sseService);

        StatePayload current = state(
                bootId,
                1L,
                BASE_TIME.plusSeconds(10),
                RobotMode.PATROLLING,
                HealthStatus.DEGRADED);
        RobotStatusIngestionResult currentResult = ingestState(
                current,
                BASE_TIME.plusSeconds(11));
        clearInvocations(sseService);

        RobotStatusIngestionResult duplicate = ingestState(
                current,
                BASE_TIME.plusSeconds(12));
        RobotStatusIngestionResult late = ingestState(
                state(
                        bootId,
                        2L,
                        BASE_TIME.plusSeconds(5),
                        RobotMode.READY,
                        HealthStatus.OK),
                BASE_TIME.plusSeconds(13));

        assertThat(currentResult)
                .isEqualTo(new RobotStatusIngestionResult(true, true));
        assertThat(duplicate)
                .isEqualTo(new RobotStatusIngestionResult(false, false));
        assertThat(late)
                .isEqualTo(new RobotStatusIngestionResult(true, false));
        assertThat(currentMode()).isEqualTo("PATROLLING");
        assertThat(currentStateSequence()).isEqualTo(1L);
        assertThat(currentOverallHealth()).isEqualTo("WARNING");
        assertThat(bootLastSeenSequence(bootId)).isEqualTo(2L);
        assertThat(historyCount("STATE")).isEqualTo(2);
        verifyNoInteractions(sseService);
    }

    @Test
    void acceptsSequenceOneForNewBootAndRejectsOldBootReplay() {
        UUID oldBootId = UUID.randomUUID();
        UUID newBootId = UUID.randomUUID();
        ingestPresence(
                online(oldBootId, BASE_TIME),
                BASE_TIME.plusSeconds(1));
        ingestState(
                state(
                        oldBootId,
                        1L,
                        BASE_TIME.plusSeconds(2),
                        RobotMode.PATROLLING,
                        HealthStatus.OK),
                BASE_TIME.plusSeconds(3));

        ingestPresence(
                online(newBootId, BASE_TIME.plusSeconds(10)),
                BASE_TIME.plusSeconds(11));
        RobotStatusIngestionResult newBootState = ingestState(
                state(
                        newBootId,
                        1L,
                        BASE_TIME.plusSeconds(12),
                        RobotMode.READY,
                        HealthStatus.OK),
                BASE_TIME.plusSeconds(13));
        clearInvocations(sseService);

        assertThatThrownBy(() -> ingestState(
                state(
                        oldBootId,
                        2L,
                        BASE_TIME.plusSeconds(14),
                        RobotMode.ERROR,
                        HealthStatus.FAULT),
                BASE_TIME.plusSeconds(15)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.MQTT_REPLAY_REJECTED));
        RobotStatusIngestionResult oldLastWill = ingestPresence(
                offline(oldBootId),
                BASE_TIME.plusSeconds(16));

        assertThat(newBootState)
                .isEqualTo(new RobotStatusIngestionResult(true, true));
        assertThat(oldLastWill)
                .isEqualTo(new RobotStatusIngestionResult(false, false));
        assertThat(currentBootId()).isEqualTo(newBootId);
        assertThat(currentStateSequence()).isEqualTo(1L);
        assertThat(currentMode()).isEqualTo("READY");
        assertThat(currentConnectivity()).isEqualTo("ONLINE");
        assertThat(bootStatus(oldBootId)).isEqualTo("OFFLINE");
        assertThat(bootOfflineReason(oldBootId))
                .isEqualTo("REPLACED_BY_NEW_BOOT");
        verifyNoInteractions(sseService);
    }

    @Test
    void ignoresOnlineReplayForBootClosedByReplacement() {
        UUID oldBootId = UUID.randomUUID();
        UUID newBootId = UUID.randomUUID();
        PresencePayload oldOnline = online(oldBootId, BASE_TIME);
        ingestPresence(oldOnline, BASE_TIME.plusSeconds(1));
        ingestPresence(
                online(newBootId, BASE_TIME.plusSeconds(10)),
                BASE_TIME.plusSeconds(11));
        clearInvocations(sseService);

        RobotStatusIngestionResult replay = ingestPresence(
                new PresencePayload(
                        "2.0",
                        mqttId,
                        oldBootId,
                        PresencePayload.Status.ONLINE,
                        BASE_TIME.plusSeconds(20),
                        null),
                BASE_TIME.plusSeconds(21));

        assertThat(replay)
                .isEqualTo(new RobotStatusIngestionResult(false, false));
        assertThat(currentBootId()).isEqualTo(newBootId);
        assertThat(currentConnectivity()).isEqualTo("ONLINE");
        assertThat(bootStatus(oldBootId)).isEqualTo("OFFLINE");
        assertThat(bootOfflineReason(oldBootId))
                .isEqualTo("REPLACED_BY_NEW_BOOT");
        assertThat(historyCount("PRESENCE")).isEqualTo(2);
        verifyNoInteractions(sseService);
    }

    @Test
    void serializesStateAndTelemetryClaimingSameBootSequence()
            throws Exception {
        UUID bootId = UUID.randomUUID();
        ingestPresence(online(bootId, BASE_TIME), BASE_TIME.plusSeconds(1));
        StatePayload state = state(
                bootId,
                1L,
                BASE_TIME.plusSeconds(2),
                RobotMode.READY,
                HealthStatus.OK);
        TelemetryPayload telemetry = telemetry(
                bootId,
                1L,
                BASE_TIME.plusSeconds(2));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> stateResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    ingestState(state, BASE_TIME.plusSeconds(3));
                    return "STATE_ACCEPTED";
                } catch (BusinessException exception) {
                    return exception.errorCode().name();
                }
            });
            Future<String> telemetryResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    telemetryIngestionService.ingest(
                            new TelemetryIngestionCommand(
                                    telemetry,
                                    "{}",
                                    BASE_TIME.plusSeconds(3)));
                    return "TELEMETRY_ACCEPTED";
                } catch (BusinessException exception) {
                    return exception.errorCode().name();
                }
            });

            ready.await();
            start.countDown();
            List<String> results = List.of(
                    stateResult.get(),
                    telemetryResult.get());

            assertThat(results)
                    .filteredOn(result -> result.endsWith("_ACCEPTED"))
                    .hasSize(1);
            assertThat(results)
                    .filteredOn(result -> result.equals(
                            ErrorCode.MQTT_SEQUENCE_CONFLICT.name()))
                    .hasSize(1);
            Integer telemetryCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) "
                            + "FROM telemetry.robot_telemetry "
                            + "WHERE robot_id = ?",
                    Integer.class,
                    robotId);
            assertThat(historyCount("STATE") + telemetryCount)
                    .isEqualTo(1);
            assertThat(bootLastSeenSequence(bootId)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void currentBootLastWillMarksRobotOfflineAndPublishesAfterCommit() {
        UUID bootId = UUID.randomUUID();
        ingestPresence(online(bootId, BASE_TIME), BASE_TIME.plusSeconds(1));
        clearInvocations(sseService);

        RobotStatusIngestionResult result = ingestPresence(
                offline(bootId),
                BASE_TIME.plusSeconds(2));

        assertThat(result)
                .isEqualTo(new RobotStatusIngestionResult(true, true));
        assertThat(currentConnectivity()).isEqualTo("OFFLINE");
        assertThat(bootStatus(bootId)).isEqualTo("OFFLINE");
        assertThat(bootOfflineReason(bootId))
                .isEqualTo("UNGRACEFUL_DISCONNECT");
        assertThat(historyCount("PRESENCE")).isEqualTo(2);
        verify(sseService).publish(argThat(event ->
                event.robotId().equals(robotId)
                        && event.mqttId().equals(mqttId)
                        && event.eventType().equals(
                                "ROBOT_CONNECTIVITY_CHANGED")));
    }

    @Test
    void timeoutIsFallbackForMissingLastWillAndIsIdempotent() {
        UUID bootId = UUID.randomUUID();
        ingestPresence(online(bootId, BASE_TIME), BASE_TIME.plusSeconds(1));
        clearInvocations(sseService);

        int changed = offlineDetectionService.markTimedOut(
                BASE_TIME.plusSeconds(91),
                BASE_TIME.plusSeconds(100));
        int duplicate = offlineDetectionService.markTimedOut(
                BASE_TIME.plusSeconds(101),
                BASE_TIME.plusSeconds(110));

        assertThat(changed).isEqualTo(1);
        assertThat(duplicate).isZero();
        assertThat(currentConnectivity()).isEqualTo("OFFLINE");
        assertThat(bootOfflineReason(bootId))
                .isEqualTo("HEARTBEAT_TIMEOUT");
        assertThat(historyCount("TIMEOUT")).isEqualTo(1);
        verify(sseService).publish(argThat(event ->
                event.eventType().equals("ROBOT_CONNECTIVITY_CHANGED")
                        && event.observedAt().equals(
                                BASE_TIME.plusSeconds(100))));
    }

    @Test
    void rollsBackPresenceWhenRobotHasNoCurrentStateRow() {
        UUID robotWithoutState = UUID.randomUUID();
        String mqttWithoutState =
                "RBT-NO-STATE-" + robotWithoutState.toString().substring(0, 8);
        createRobot(robotWithoutState, mqttWithoutState, false);
        UUID bootId = UUID.randomUUID();
        PresencePayload payload = new PresencePayload(
                "2.0",
                mqttWithoutState,
                bootId,
                PresencePayload.Status.ONLINE,
                BASE_TIME,
                null);

        assertThatThrownBy(() -> ingestionService.ingestPresence(
                new PresenceIngestionCommand(
                        payload,
                        "{}",
                        BASE_TIME.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current-state row");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry.robot_boot_session "
                        + "WHERE boot_id = ?",
                Integer.class,
                bootId)).isZero();
        verifyNoInteractions(sseService);
    }

    @Test
    void rejectsBootIdAlreadyOwnedByAnotherRobot() {
        UUID bootId = UUID.randomUUID();
        ingestPresence(online(bootId, BASE_TIME), BASE_TIME.plusSeconds(1));

        UUID anotherRobotId = UUID.randomUUID();
        String anotherMqttId =
                "RBT-OTHER-" + anotherRobotId.toString().substring(0, 8);
        createRobot(anotherRobotId, anotherMqttId, true);
        PresencePayload reusedBoot = new PresencePayload(
                "2.0",
                anotherMqttId,
                bootId,
                PresencePayload.Status.ONLINE,
                BASE_TIME.plusSeconds(2),
                null);

        assertThatThrownBy(() -> ingestionService.ingestPresence(
                new PresenceIngestionCommand(
                        reusedBoot,
                        "{}",
                        BASE_TIME.plusSeconds(3))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.MQTT_ID_MISMATCH));
        assertThat(currentBootId(anotherRobotId)).isNull();
    }

    private RobotStatusIngestionResult ingestPresence(
            PresencePayload payload,
            Instant receivedAt
    ) {
        return ingestionService.ingestPresence(new PresenceIngestionCommand(
                payload,
                "{}",
                receivedAt));
    }

    private RobotStatusIngestionResult ingestState(
            StatePayload payload,
            Instant receivedAt
    ) {
        return ingestionService.ingestState(new StateIngestionCommand(
                payload,
                "{}",
                receivedAt));
    }

    private PresencePayload online(UUID bootId, Instant connectedAt) {
        return new PresencePayload(
                "2.0",
                mqttId,
                bootId,
                PresencePayload.Status.ONLINE,
                connectedAt,
                null);
    }

    private PresencePayload offline(UUID bootId) {
        return new PresencePayload(
                "2.0",
                mqttId,
                bootId,
                PresencePayload.Status.OFFLINE,
                null,
                PresencePayload.OfflineReason.UNGRACEFUL_DISCONNECT);
    }

    private StatePayload state(
            UUID bootId,
            long sequence,
            Instant updatedAt,
            RobotMode mode,
            HealthStatus cameraHealth
    ) {
        return new StatePayload(
                "2.0",
                mqttId,
                bootId,
                sequence,
                updatedAt,
                mode,
                RobotStateReason.NONE,
                null,
                new StatePayload.Battery(
                        true,
                        71.0,
                        BatteryState.NORMAL),
                new StatePayload.Health(
                        cameraHealth,
                        HealthStatus.OK,
                        HealthStatus.OK,
                        HealthStatus.OK,
                        HealthStatus.OK,
                        HealthStatus.OK));
    }

    private TelemetryPayload telemetry(
            UUID bootId,
            long sequence,
            Instant sampledAt
    ) {
        return new TelemetryPayload(
                "2.0",
                UUID.randomUUID(),
                mqttId,
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
    }

    private UUID currentBootId() {
        return currentBootId(robotId);
    }

    private UUID currentBootId(UUID targetRobotId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_boot_id FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                UUID.class,
                targetRobotId);
    }

    private String currentConnectivity() {
        return jdbcTemplate.queryForObject(
                "SELECT connectivity_status "
                        + "FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                String.class,
                robotId);
    }

    private String currentMode() {
        return jdbcTemplate.queryForObject(
                "SELECT robot_mode FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                String.class,
                robotId);
    }

    private Long currentStateSequence() {
        return jdbcTemplate.queryForObject(
                "SELECT state_seq FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                Long.class,
                robotId);
    }

    private String currentOverallHealth() {
        return jdbcTemplate.queryForObject(
                "SELECT overall_health_status "
                        + "FROM telemetry.robot_current_state "
                        + "WHERE robot_id = ?",
                String.class,
                robotId);
    }

    private String bootStatus(UUID bootId) {
        return jdbcTemplate.queryForObject(
                "SELECT session_status FROM telemetry.robot_boot_session "
                        + "WHERE boot_id = ?",
                String.class,
                bootId);
    }

    private String bootOfflineReason(UUID bootId) {
        return jdbcTemplate.queryForObject(
                "SELECT offline_reason FROM telemetry.robot_boot_session "
                        + "WHERE boot_id = ?",
                String.class,
                bootId);
    }

    private Long bootLastSeenSequence(UUID bootId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_seen_seq FROM telemetry.robot_boot_session "
                        + "WHERE boot_id = ?",
                Long.class,
                bootId);
    }

    private int historyCount(String eventKind) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry.robot_status_history "
                        + "WHERE robot_id = ? AND event_kind = ?",
                Integer.class,
                robotId,
                eventKind);
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
                    'ROBOT-STATUS-ADMIN',
                    '{argon2}$argon2id$integration-test-status',
                    'Robot Status Admin',
                    'robot-status-admin@example.com',
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

    private void createRobot(
            UUID targetRobotId,
            String targetMqttId,
            boolean createCurrentState
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
                """,
                targetRobotId,
                targetMqttId,
                "Status Robot",
                "SN-" + targetRobotId,
                ADMIN_USER_ID);
        if (createCurrentState) {
            jdbcTemplate.update("""
                    INSERT INTO telemetry.robot_current_state (
                        robot_id,
                        connectivity_status
                    ) VALUES (?, 'OFFLINE')
                    """,
                    targetRobotId);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            RobotStatusIngestionService.class,
            TelemetryIngestionService.class,
            MissionProgressService.class,
            MqttReplayProtectionService.class,
            RobotOfflineDetectionService.class,
            RobotRealtimeEventListener.class
    })
    static class TestApplication {
    }
}
