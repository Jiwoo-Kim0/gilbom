package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.defect.IncidentEventIngestionService;
import com.gilbom.defect.IncidentEventIngestionService.IncidentIngestionResult;
import com.gilbom.robot.data.application.command.TelemetryIngestionCommand;
import com.gilbom.robot.data.mqtt.v2.common.BatteryState;
import com.gilbom.robot.data.mqtt.v2.common.EventSource;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.event.RobotEventIngestionResult;
import com.gilbom.robot.event.RobotEventIngestionService;
import com.gilbom.robot.mission.MissionEventIngestionService;
import com.gilbom.robot.mission.MissionProgressService;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService;
import com.gilbom.robot.telemetry.TelemetryIngestionService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Testcontainers(disabledWithoutDocker = true)
class RobotEventMissionDefectIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID ADMIN_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000002");
    private static final UUID AREA_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000003");
    private static final UUID ROUTE_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000004");
    private static final UUID MISSION_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000005");
    private static final UUID BOOT_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000006");
    private static final Instant BASE_TIME =
            Instant.parse("2026-07-28T01:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_event_mission_defect_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static JdbcTemplate jdbcTemplate;
    private static SimpleMeterRegistry meterRegistry;
    private static RobotEventIngestionService eventIngestionService;
    private static MissionEventIngestionService missionIngestionService;
    private static IncidentEventIngestionService incidentIngestionService;
    private static TelemetryIngestionService telemetryIngestionService;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("flyway_history")
                .placeholders(Map.of(
                        "applicationRole",
                        DATABASE.getUsername()))
                .load()
                .migrate();

        ObjectMapper objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        MqttReplayProtectionService replayProtectionService =
                new MqttReplayProtectionService(
                        jdbcTemplate,
                        meterRegistry);
        eventIngestionService = new RobotEventIngestionService(
                jdbcTemplate,
                replayProtectionService);
        missionIngestionService = new MissionEventIngestionService(
                jdbcTemplate,
                eventIngestionService,
                objectMapper);
        incidentIngestionService = new IncidentEventIngestionService(
                jdbcTemplate,
                eventIngestionService,
                objectMapper);
        ApplicationEventPublisher noOpPublisher = event -> {
        };
        telemetryIngestionService = new TelemetryIngestionService(
                jdbcTemplate,
                noOpPublisher,
                new MissionProgressService(jdbcTemplate),
                replayProtectionService);
    }

    @BeforeEach
    void createFixture() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    defect.incident_ingestion_issue,
                    defect.defect_observation,
                    defect.defect_status_history,
                    defect.defect_merge_history,
                    defect.defect,
                    defect.incident_code_counter,
                    robot.mission_status_history,
                    telemetry.robot_event,
                    telemetry.robot_telemetry,
                    robot.robot_command_result,
                    robot.robot_command,
                    robot.mission,
                    robot.patrol_route,
                    robot.patrol_area,
                    telemetry.robot_status_history,
                    telemetry.robot_current_state,
                    telemetry.robot_boot_session,
                    robot.robot_credential,
                    robot.robot
                CASCADE
                """);
        createAdmin();
        createRobot();
        createPatrolAndMission();
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_current_state (
                    robot_id,
                    connectivity_status
                ) VALUES (?, 'OFFLINE')
                """,
                ROBOT_ID);
    }

    @Test
    void storesMissionEventAndTransitionOnceForRetransmission() {
        UUID eventId = UUID.fromString(
                "23000000-0000-4000-8000-000000000101");
        MissionEventPayload started = missionEvent(
                eventId,
                1,
                MissionEventPayload.EventType.MISSION_STARTED,
                null);

        RobotEventIngestionResult first = missionIngestionService.ingest(
                started,
                "{\"event_type\":\"MISSION_STARTED\"}",
                BASE_TIME.plusSeconds(2));
        RobotEventIngestionResult duplicate =
                missionIngestionService.ingest(
                        started,
                        "{\"event_type\":\"MISSION_STARTED\"}",
                        BASE_TIME.plusSeconds(3));

        assertThat(first.inserted()).isTrue();
        assertThat(duplicate.inserted()).isFalse();
        assertThat(count("telemetry.robot_event")).isOne();
        assertThat(count("robot.mission_status_history")).isOne();
        assertThat(missionStatus()).isEqualTo("RUNNING");
        assertThat(missionStartedAt()).isEqualTo(BASE_TIME);
    }

    @Test
    void storesPrestartMissionFailureWithoutStartedAt() {
        jdbcTemplate.update("""
                UPDATE robot.mission
                SET status = 'READY',
                    updated_at = ?
                WHERE mission_id = ?
                """,
                java.sql.Timestamp.from(BASE_TIME.minusSeconds(1)),
                MISSION_ID);
        MissionEventPayload failed = missionEvent(
                UUID.fromString(
                        "23000000-0000-4000-8000-000000000104"),
                1,
                MissionEventPayload.EventType.MISSION_FAILED,
                new MissionEventPayload.Result(
                        0,
                        0,
                        0,
                        "PRECHECK_FAILED"));

        RobotEventIngestionResult stored =
                missionIngestionService.ingest(
                        failed,
                        "{\"event_type\":\"MISSION_FAILED\"}",
                        BASE_TIME.plusSeconds(2));

        Map<String, Object> mission = jdbcTemplate.queryForMap("""
                SELECT
                    status,
                    started_at,
                    ended_at,
                    progress_percent,
                    completion_reason,
                    result_duration_sec
                FROM robot.mission
                WHERE mission_id = ?
                """,
                MISSION_ID);
        Map<String, Object> history = jdbcTemplate.queryForMap("""
                SELECT
                    before_status,
                    after_status,
                    transition_reason
                FROM robot.mission_status_history
                WHERE mission_id = ?
                """,
                MISSION_ID);

        assertThat(stored.inserted()).isTrue();
        assertThat(mission.get("status")).isEqualTo("FAILED");
        assertThat(mission.get("started_at")).isNull();
        assertThat(mission.get("ended_at")).isNotNull();
        assertThat(mission.get("progress_percent").toString())
                .isEqualTo("0.00");
        assertThat(mission.get("completion_reason"))
                .isEqualTo("PRECHECK_FAILED");
        assertThat(mission.get("result_duration_sec")).isEqualTo(0);
        assertThat(history)
                .containsEntry("before_status", "READY")
                .containsEntry("after_status", "FAILED")
                .containsEntry(
                        "transition_reason",
                        "PRECHECK_FAILED");
    }

    @Test
    void rejectsChangedEventIdempotencyKeyAndSequenceCollision() {
        UUID eventId = UUID.fromString(
                "23000000-0000-4000-8000-000000000102");
        missionIngestionService.ingest(
                missionEvent(
                        eventId,
                        1,
                        MissionEventPayload.EventType.MISSION_STARTED,
                        null),
                "{}",
                BASE_TIME.plusSeconds(2));

        assertThatThrownBy(() -> eventIngestionService.ingest(
                missionEvent(
                        eventId,
                        2,
                        MissionEventPayload.EventType.MISSION_PAUSED,
                        null),
                MissionEventPayload.EventType.MISSION_PAUSED,
                "{}",
                BASE_TIME.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.MQTT_EVENT_CONFLICT));

        assertThatThrownBy(() -> eventIngestionService.ingest(
                missionEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000103"),
                        1,
                        MissionEventPayload.EventType.MISSION_STARTED,
                        null),
                MissionEventPayload.EventType.MISSION_STARTED,
                "{}",
                BASE_TIME.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.MQTT_SEQUENCE_CONFLICT));

        assertThat(count("telemetry.robot_event")).isOne();
        assertThat(count("robot.mission_status_history")).isOne();
    }

    @Test
    void storesTelemetryMissionUuidAndRejectsUnknownMissionCode() {
        TelemetryPayload valid = telemetryPayload(
                UUID.fromString(
                        "23000000-0000-4000-8000-000000000201"),
                "MIS-20260728-001",
                1);

        telemetryIngestionService.ingest(new TelemetryIngestionCommand(
                valid,
                "{}",
                BASE_TIME.plusSeconds(2)));

        UUID storedMissionId = jdbcTemplate.queryForObject("""
                SELECT mission_id
                FROM telemetry.robot_telemetry
                WHERE message_id = ?
                """,
                UUID.class,
                valid.messageId());
        assertThat(storedMissionId).isEqualTo(MISSION_ID);

        assertThatThrownBy(() -> telemetryIngestionService.ingest(
                new TelemetryIngestionCommand(
                        telemetryPayload(
                                UUID.fromString(
                                        "23000000-0000-4000-8000-000000000202"),
                                "MIS-UNKNOWN",
                                2),
                        "{}",
                        BASE_TIME.plusSeconds(3))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.MISSION_NOT_FOUND));
        assertThat(count("telemetry.robot_telemetry")).isOne();
    }

    @Test
    void updatesRunningMissionProgressFromElapsedTime() {
        missionIngestionService.ingest(
                missionEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000203"),
                        1,
                        MissionEventPayload.EventType.MISSION_STARTED,
                        null),
                "{\"event_type\":\"MISSION_STARTED\"}",
                BASE_TIME.plusSeconds(2));
        TelemetryPayload telemetry = telemetryPayload(
                UUID.fromString(
                        "23000000-0000-4000-8000-000000000204"),
                "MIS-20260728-001",
                2);

        telemetryIngestionService.ingest(new TelemetryIngestionCommand(
                telemetry,
                "{}",
                BASE_TIME.plusSeconds(3)));

        Double progress = jdbcTemplate.queryForObject("""
                SELECT progress_percent::DOUBLE PRECISION
                FROM robot.mission
                WHERE mission_id = ?
                """,
                Double.class,
                MISSION_ID);
        assertThat(progress).isEqualTo(25.0);
    }

    @Test
    void advancesPausedMissionProgressAndCompletesOnlyByEvent() {
        missionIngestionService.ingest(
                missionEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000214"),
                        1,
                        MissionEventPayload.EventType.MISSION_STARTED,
                        null),
                "{\"event_type\":\"MISSION_STARTED\"}",
                BASE_TIME.plusSeconds(2));
        missionIngestionService.ingest(
                missionEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000215"),
                        2,
                        MissionEventPayload.EventType.MISSION_PAUSED,
                        null),
                "{\"event_type\":\"MISSION_PAUSED\"}",
                BASE_TIME.plusSeconds(3));

        telemetryIngestionService.ingest(new TelemetryIngestionCommand(
                telemetryPayload(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000216"),
                        "MIS-20260728-001",
                        3),
                "{}",
                BASE_TIME.plusSeconds(4)));

        assertThat(missionStatus()).isEqualTo("PAUSED");
        assertThat(missionProgress()).isEqualTo(37.5);

        missionIngestionService.ingest(
                missionEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000217"),
                        4,
                        MissionEventPayload.EventType.MISSION_RESUMED,
                        null),
                "{\"event_type\":\"MISSION_RESUMED\"}",
                BASE_TIME.plusSeconds(5));
        missionIngestionService.ingest(
                missionEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000218"),
                        5,
                        MissionEventPayload.EventType.MISSION_COMPLETED,
                        new MissionEventPayload.Result(
                                5,
                                0,
                                0,
                                "PLANNED_DURATION_REACHED")),
                "{\"event_type\":\"MISSION_COMPLETED\"}",
                BASE_TIME.plusSeconds(6));

        assertThat(missionStatus()).isEqualTo("COMPLETED");
        assertThat(missionProgress()).isEqualTo(100.0);
    }

    @Test
    void rejectsCrossTopicSequenceCollisionBeforeMissionSideEffects() {
        TelemetryPayload telemetry = telemetryPayload(
                UUID.fromString(
                        "23000000-0000-4000-8000-000000000205"),
                "MIS-20260728-001",
                1);
        telemetryIngestionService.ingest(new TelemetryIngestionCommand(
                telemetry,
                "{}",
                BASE_TIME.plusSeconds(2)));
        double before = meterRegistry.counter(
                "gilbom.mqtt.inbound.rejected",
                "topic",
                "MISSION_EVENT",
                "reason",
                "sequence_conflict").count();

        assertThatThrownBy(() -> missionIngestionService.ingest(
                missionEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000206"),
                        1,
                        MissionEventPayload.EventType.MISSION_STARTED,
                        null),
                "{\"event_type\":\"MISSION_STARTED\"}",
                BASE_TIME.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.MQTT_SEQUENCE_CONFLICT));

        assertThat(count("telemetry.robot_event")).isZero();
        assertThat(count("robot.mission_status_history")).isZero();
        assertThat(missionStatus()).isEqualTo("CREATED");
        assertThat(meterRegistry.counter(
                "gilbom.mqtt.inbound.rejected",
                "topic",
                "MISSION_EVENT",
                "reason",
                "sequence_conflict").count()).isEqualTo(before + 1.0);
    }

    @Test
    void rejectsEventFromPreviousBootWithoutEventSideEffects() {
        UUID newBootId = UUID.fromString(
                "23000000-0000-4000-8000-000000000207");
        telemetryIngestionService.ingest(new TelemetryIngestionCommand(
                telemetryPayload(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000208"),
                        BOOT_ID,
                        "MIS-20260728-001",
                        1),
                "{}",
                BASE_TIME.plusSeconds(2)));
        telemetryIngestionService.ingest(new TelemetryIngestionCommand(
                telemetryPayload(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000209"),
                        newBootId,
                        "MIS-20260728-001",
                        1),
                "{}",
                BASE_TIME.plusSeconds(3)));

        assertThatThrownBy(() -> eventIngestionService.ingest(
                missionEvent(
                        BOOT_ID,
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000210"),
                        2,
                        MissionEventPayload.EventType.MISSION_STARTED,
                        null),
                MissionEventPayload.EventType.MISSION_STARTED,
                "{}",
                BASE_TIME.plusSeconds(4)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.MQTT_REPLAY_REJECTED));

        assertThat(count("telemetry.robot_event")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT session_status
                FROM telemetry.robot_boot_session
                WHERE boot_id = ?
                """,
                String.class,
                BOOT_ID)).isEqualTo("OFFLINE");
    }

    @Test
    void rejectsEventWhoseNewBootDoesNotStartAtSequenceOne() {
        UUID unknownBootId = UUID.fromString(
                "23000000-0000-4000-8000-000000000211");

        assertThatThrownBy(() -> eventIngestionService.ingest(
                missionEvent(
                        unknownBootId,
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000212"),
                        2,
                        MissionEventPayload.EventType.MISSION_STARTED,
                        null),
                MissionEventPayload.EventType.MISSION_STARTED,
                "{}",
                BASE_TIME.plusSeconds(2)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.MQTT_REPLAY_REJECTED));

        assertThat(count("telemetry.robot_event")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry.robot_boot_session "
                        + "WHERE boot_id = ?",
                Integer.class,
                unknownBootId)).isZero();
    }

    @Test
    void createsAndSpatiallyMergesIncidentObservationsIdempotently() {
        IncidentEventPayload firstPayload = incidentEvent(
                UUID.fromString(
                        "23000000-0000-4000-8000-000000000301"),
                1,
                127.027600,
                37.497900,
                0.87);
        IncidentIngestionResult first = incidentIngestionService.ingest(
                firstPayload,
                "{\"event_type\":\"BRAILLE_BLOCK_INCIDENT_DETECTED\"}",
                BASE_TIME.plusSeconds(2));

        IncidentIngestionResult duplicate =
                incidentIngestionService.ingest(
                        firstPayload,
                        "{\"event_type\":\"BRAILLE_BLOCK_INCIDENT_DETECTED\"}",
                        BASE_TIME.plusSeconds(3));
        IncidentIngestionResult nearby = incidentIngestionService.ingest(
                incidentEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000302"),
                        2,
                        127.027610,
                        37.497905,
                        0.91),
                "{\"event_type\":\"BRAILLE_BLOCK_INCIDENT_DETECTED\"}",
                BASE_TIME.plusSeconds(4));

        assertThat(first.observationInserted()).isTrue();
        assertThat(duplicate.observationInserted()).isFalse();
        assertThat(nearby.defectId()).isEqualTo(first.defectId());
        assertThat(count("telemetry.robot_event")).isEqualTo(2);
        assertThat(count("defect.defect")).isOne();
        assertThat(count("defect.defect_observation")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT observation_count
                FROM defect.defect
                WHERE defect_id = ?
                """,
                Integer.class,
                first.defectId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT incident_code
                FROM defect.defect
                WHERE defect_id = ?
                """,
                String.class,
                first.defectId())).isEqualTo("INC-20260728-000001");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT risk_level
                FROM defect.defect
                WHERE defect_id = ?
                """,
                String.class,
                first.defectId())).isEqualTo("MEDIUM");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT risk_score
                FROM defect.defect
                WHERE defect_id = ?
                """,
                BigDecimal.class,
                first.defectId())).isEqualByComparingTo("32.00");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT risk_rule_version
                FROM defect.defect
                WHERE defect_id = ?
                """,
                String.class,
                first.defectId())).isEqualTo("DAMAGE_RATIO_V1");
    }

    @Test
    void startsNewDefectWhenTheResolvedOneIsDetectedAgain() {
        // 수리가 끝난 자리에서 다시 탐지되면 같은 결함이 아니다. 옛 결함에 관측만
        // 붙이면 "수리했는데 또 났다"는 사실이 관측 횟수 뒤로 숨는다.
        IncidentIngestionResult first = incidentIngestionService.ingest(
                incidentEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000401"),
                        1,
                        127.027600,
                        37.497900,
                        0.87),
                "{\"event_type\":\"BRAILLE_BLOCK_INCIDENT_DETECTED\"}",
                BASE_TIME.plusSeconds(2));
        jdbcTemplate.update("""
                UPDATE defect.defect
                SET workflow_status = 'RESOLVED'
                WHERE defect_id = ?
                """,
                first.defectId());

        // 병합 반경 2m 안쪽이다. 상태만 아니면 흡수됐을 거리다.
        IncidentIngestionResult afterRepair = incidentIngestionService.ingest(
                incidentEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000402"),
                        2,
                        127.027610,
                        37.497905,
                        0.91),
                "{\"event_type\":\"BRAILLE_BLOCK_INCIDENT_DETECTED\"}",
                BASE_TIME.plusSeconds(4));

        assertThat(afterRepair.defectId()).isNotEqualTo(first.defectId());
        assertThat(count("defect.defect")).isEqualTo(2);
        // 종결된 결함은 그대로 둔다. 되살아나면 안 된다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT observation_count
                FROM defect.defect
                WHERE defect_id = ?
                """,
                Integer.class,
                first.defectId())).isOne();
    }

    @Test
    void keepsAbsorbingDetectionsThatWereDismissedAsFalsePositive() {
        // 오탐으로 판정한 대상은 순찰마다 다시 잡힌다. 새 결함으로 쌓으면 목록이
        // 같은 오탐으로 잠기므로, 이미 기각한 기록에 붙인다.
        IncidentIngestionResult first = incidentIngestionService.ingest(
                incidentEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000411"),
                        1,
                        127.027600,
                        37.497900,
                        0.87),
                "{\"event_type\":\"BRAILLE_BLOCK_INCIDENT_DETECTED\"}",
                BASE_TIME.plusSeconds(2));
        jdbcTemplate.update("""
                UPDATE defect.defect
                SET workflow_status = 'FALSE_POSITIVE'
                WHERE defect_id = ?
                """,
                first.defectId());

        IncidentIngestionResult again = incidentIngestionService.ingest(
                incidentEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000412"),
                        2,
                        127.027610,
                        37.497905,
                        0.91),
                "{\"event_type\":\"BRAILLE_BLOCK_INCIDENT_DETECTED\"}",
                BASE_TIME.plusSeconds(4));

        assertThat(again.defectId()).isEqualTo(first.defectId());
        assertThat(count("defect.defect")).isOne();
    }

    @Test
    void calculatesRiskBandsAndDailyIncidentCodeSequence() {
        IncidentIngestionResult low = incidentIngestionService.ingest(
                incidentEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000311"),
                        1,
                        127.027600,
                        37.497900,
                        0.90,
                        0.10),
                "{\"incident\":{\"attributes\":{\"damage_ratio\":0.10}}}",
                BASE_TIME.plusSeconds(2));
        IncidentIngestionResult critical = incidentIngestionService.ingest(
                incidentEvent(
                        UUID.fromString(
                                "23000000-0000-4000-8000-000000000312"),
                        2,
                        127.028600,
                        37.498900,
                        0.90,
                        0.80),
                "{\"incident\":{\"attributes\":{\"damage_ratio\":0.80}}}",
                BASE_TIME.plusSeconds(3));

        Map<String, Object> lowDefect = jdbcTemplate.queryForMap("""
                SELECT
                    incident_code,
                    risk_level,
                    risk_score,
                    risk_rule_version,
                    risk_calculated_at
                FROM defect.defect
                WHERE defect_id = ?
                """,
                low.defectId());
        Map<String, Object> criticalDefect = jdbcTemplate.queryForMap("""
                SELECT incident_code, risk_level, risk_score
                FROM defect.defect
                WHERE defect_id = ?
                """,
                critical.defectId());

        assertThat(lowDefect.get("incident_code"))
                .isEqualTo("INC-20260728-000001");
        assertThat(lowDefect.get("risk_level")).isEqualTo("LOW");
        assertThat((BigDecimal) lowDefect.get("risk_score"))
                .isEqualByComparingTo("10.00");
        assertThat(lowDefect.get("risk_rule_version"))
                .isEqualTo("DAMAGE_RATIO_V1");
        assertThat(lowDefect.get("risk_calculated_at")).isNotNull();
        assertThat(criticalDefect.get("incident_code"))
                .isEqualTo("INC-20260728-000002");
        assertThat(criticalDefect.get("risk_level")).isEqualTo("CRITICAL");
        assertThat((BigDecimal) criticalDefect.get("risk_score"))
                .isEqualByComparingTo("80.00");
    }

    @Test
    void preservesInvalidLocationIncidentAsReviewIssueWithoutDefect() {
        IncidentEventPayload payload = incidentEvent(
                UUID.fromString(
                        "23000000-0000-4000-8000-000000000303"),
                1,
                null,
                null,
                0.80);

        IncidentIngestionResult result = incidentIngestionService.ingest(
                payload,
                "{\"location\":{\"valid\":false}}",
                BASE_TIME.plusSeconds(2));

        assertThat(result.event().inserted()).isTrue();
        assertThat(result.observationInserted()).isFalse();
        assertThat(result.defectId()).isNull();
        assertThat(count("telemetry.robot_event")).isOne();
        assertThat(count("defect.defect")).isZero();
        assertThat(count("defect.defect_observation")).isZero();
        assertThat(count("defect.incident_ingestion_issue")).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT issue_type
                FROM defect.incident_ingestion_issue
                WHERE event_id = ?
                """,
                String.class,
                payload.eventId())).isEqualTo("INVALID_GPS");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT issue_status
                FROM defect.incident_ingestion_issue
                WHERE event_id = ?
                """,
                String.class,
                payload.eventId())).isEqualTo("OPEN");
        assertThat(count("defect.incident_code_counter")).isZero();
    }

    private static MissionEventPayload missionEvent(
            UUID eventId,
            long sequence,
            MissionEventPayload.EventType eventType,
            MissionEventPayload.Result result
    ) {
        return missionEvent(
                BOOT_ID,
                eventId,
                sequence,
                eventType,
                result);
    }

    private static MissionEventPayload missionEvent(
            UUID bootId,
            UUID eventId,
            long sequence,
            MissionEventPayload.EventType eventType,
            MissionEventPayload.Result result
    ) {
        return new MissionEventPayload(
                "2.0",
                eventId,
                eventType,
                "RBT-EVENT-001",
                bootId,
                sequence,
                EventSource.MISSION,
                BASE_TIME.plusSeconds(sequence - 1),
                BASE_TIME.plusSeconds(sequence),
                300,
                "MIS-20260728-001",
                result);
    }

    private static IncidentEventPayload incidentEvent(
            UUID eventId,
            long sequence,
            Double longitude,
            Double latitude,
            double confidence
    ) {
        return incidentEvent(
                eventId,
                sequence,
                longitude,
                latitude,
                confidence,
                0.32);
    }

    private static IncidentEventPayload incidentEvent(
            UUID eventId,
            long sequence,
            Double longitude,
            Double latitude,
            double confidence,
            Double damageRatio
    ) {
        boolean valid = longitude != null && latitude != null;
        return new IncidentEventPayload(
                "2.0",
                eventId,
                IncidentEventPayload.EventType
                        .BRAILLE_BLOCK_INCIDENT_DETECTED,
                "RBT-EVENT-001",
                BOOT_ID,
                sequence,
                EventSource.AI,
                BASE_TIME.plusSeconds(sequence - 1),
                BASE_TIME.plusSeconds(sequence),
                300,
                "MIS-20260728-001",
                new IncidentEventPayload.Location(
                        valid,
                        latitude,
                        longitude,
                        "똥방구 설사"),
                new IncidentEventPayload.Incident(
                        IncidentEventPayload.AssetType.BRAILLE_BLOCK,
                        IncidentEventPayload.BlockType.LINEAR,
                        IncidentEventPayload.DefectType.DAMAGED,
                        confidence,
                        new IncidentEventPayload.Attributes(damageRatio)),
                new IncidentEventPayload.Evidence(
                        null,
                        BASE_TIME.plusSeconds(sequence - 1),
                        1280,
                        720,
                        List.of(0.16, 0.42, 0.81, 0.95)),
                new IncidentEventPayload.Model(
                        "tbs-braille-yolo",
                        "1.0.0"));
    }

    private static TelemetryPayload telemetryPayload(
            UUID messageId,
            String missionCode,
            long sequence
    ) {
        return telemetryPayload(
                messageId,
                BOOT_ID,
                missionCode,
                sequence);
    }

    private static TelemetryPayload telemetryPayload(
            UUID messageId,
            UUID bootId,
            String missionCode,
            long sequence
    ) {
        return new TelemetryPayload(
                "2.0",
                messageId,
                "RBT-EVENT-001",
                bootId,
                sequence,
                BASE_TIME.plusSeconds(sequence),
                missionCode,
                new TelemetryPayload.Position(
                        true,
                        37.4979,
                        127.0276,
                        90.0),
                new TelemetryPayload.Motion(true, 0.8),
                new TelemetryPayload.Battery(
                        true,
                        80.0,
                        BatteryState.NORMAL,
                        TelemetryPayload.Source.MEASURED,
                        24.0,
                        1.2,
                        false,
                        41.0,
                        3600),
                new TelemetryPayload.SystemMetrics(52.0),
                new TelemetryPayload.Mission(
                        com.gilbom.robot.data.mqtt.v2.common.MissionState
                                .RUNNING,
                        Math.toIntExact(sequence),
                        0));
    }

    private static int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private static String missionStatus() {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM robot.mission
                WHERE mission_id = ?
                """,
                String.class,
                MISSION_ID);
    }

    private static Double missionProgress() {
        return jdbcTemplate.queryForObject("""
                SELECT progress_percent::DOUBLE PRECISION
                FROM robot.mission
                WHERE mission_id = ?
                """,
                Double.class,
                MISSION_ID);
    }

    private static Instant missionStartedAt() {
        return jdbcTemplate.queryForObject("""
                SELECT started_at
                FROM robot.mission
                WHERE mission_id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getObject(
                        "started_at",
                        java.time.OffsetDateTime.class).toInstant(),
                MISSION_ID);
    }

    private static void createAdmin() {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    legacy_username,
                    password_hash,
                    display_name,
                    email,
                    account_status,
                    is_active,
                    account_origin
                ) VALUES (
                    ?,
                    'ADM-EVENT-INGEST',
                    'event_ingest_admin',
                    '{argon2}$argon2id$event-ingest-test',
                    'Event Ingest Admin',
                    'event-ingest@example.invalid',
                    'ACTIVE',
                    TRUE,
                    'ADMIN_CREATED'
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                ADMIN_ID);
    }

    private static void createRobot() {
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
                    'RBT-EVENT-001',
                    'Event Ingest Robot',
                    'SN-EVENT-001',
                    'ACTIVE',
                    ?
                )
                """,
                ROBOT_ID,
                ADMIN_ID);
    }

    private static void createPatrolAndMission() {
        jdbcTemplate.update("""
                INSERT INTO robot.patrol_area (
                    patrol_area_id,
                    area_code,
                    area_name,
                    boundary,
                    lifecycle_status,
                    created_by,
                    updated_by,
                    created_at,
                    updated_at
                ) VALUES (
                    ?,
                    '똥방구 설사',
                    'Event Test Area',
                    ST_GeomFromText(
                        'POLYGON(('
                        || '127.02 37.49,'
                        || '127.04 37.49,'
                        || '127.04 37.51,'
                        || '127.02 37.51,'
                        || '127.02 37.49))',
                        4326
                    ),
                    'ACTIVE',
                    ?,
                    ?,
                    '2026-07-28T00:00:00Z',
                    '2026-07-28T00:00:00Z'
                )
                """,
                AREA_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO robot.patrol_route (
                    patrol_route_id,
                    patrol_area_id,
                    route_code,
                    route_name,
                    route_version,
                    route_geometry,
                    lifecycle_status,
                    created_by,
                    updated_by,
                    created_at,
                    updated_at
                ) VALUES (
                    ?,
                    ?,
                    'A-01-MAIN',
                    'Event Test Route',
                    1,
                    ST_GeomFromText(
                        'LINESTRING('
                        || '127.025 37.495,'
                        || '127.035 37.505)',
                        4326
                    ),
                    'ACTIVE',
                    ?,
                    ?,
                    '2026-07-28T00:00:00Z',
                    '2026-07-28T00:00:00Z'
                )
                """,
                ROUTE_ID,
                AREA_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO robot.mission (
                    mission_id,
                    mission_code,
                    robot_id,
                    patrol_area_id,
                    patrol_route_id,
                    planned_duration_sec,
                    status,
                    created_by,
                    updated_by,
                    created_at,
                    updated_at
                ) VALUES (
                    ?,
                    'MIS-20260728-001',
                    ?,
                    ?,
                    ?,
                    8,
                    'CREATED',
                    ?,
                    ?,
                    '2026-07-28T00:00:00Z',
                    '2026-07-28T00:00:00Z'
                )
                """,
                MISSION_ID,
                ROBOT_ID,
                AREA_ID,
                ROUTE_ID,
                ADMIN_ID,
                ADMIN_ID);
    }
}
