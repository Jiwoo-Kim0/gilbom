package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaResponse;
import com.gilbom.robot.mission.PatrolCatalogQueryService;
import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;
import com.gilbom.statistics.StatisticsDtos.StatisticsByAreaItem;
import com.gilbom.statistics.StatisticsDtos.StatisticsByPeriodItem;
import com.gilbom.statistics.StatisticsQueryService;
import com.gilbom.statistics.StatisticsRepository;

@Testcontainers(disabledWithoutDocker = true)
class PatrolMissionSchemaIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final String APPLICATION_ROLE =
            "gilbom_patrol_schema_app";
    private static final String APPLICATION_PASSWORD =
            "patrol-schema-test-password";
    private static final UUID ADMIN_ID = UUID.fromString(
            "22000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID = UUID.fromString(
            "22000000-0000-4000-8000-000000000002");
    private static final UUID AREA_ID = UUID.fromString(
            "22000000-0000-4000-8000-000000000003");
    private static final UUID ROUTE_ID = UUID.fromString(
            "22000000-0000-4000-8000-000000000004");
    private static final UUID MISSION_ID = UUID.fromString(
            "22000000-0000-4000-8000-000000000005");
    private static final UUID BOOT_ID = UUID.fromString(
            "22000000-0000-4000-8000-000000000006");
    private static final UUID EVENT_ID = UUID.fromString(
            "22000000-0000-4000-8000-000000000007");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_patrol_schema_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static JdbcTemplate ownerJdbcTemplate;
    private static JdbcTemplate applicationJdbcTemplate;
    private static DataSource ownerDataSource;
    private static DataSource applicationDataSource;
    private static PatrolCatalogQueryService patrolCatalogQueryService;
    private static StatisticsQueryService statisticsQueryService;

    @BeforeAll
    static void migrateAndCreateFixture() throws Exception {
        ownerDataSource = dataSource(
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        ownerJdbcTemplate = new JdbcTemplate(ownerDataSource);
        ownerJdbcTemplate.execute("""
                CREATE ROLE gilbom_patrol_schema_app
                LOGIN
                PASSWORD 'patrol-schema-test-password'
                NOSUPERUSER
                NOCREATEDB
                NOCREATEROLE
                NOINHERIT
                """);

        Flyway.configure()
                .dataSource(ownerDataSource)
                .defaultSchema("flyway_history")
                .placeholders(Map.of(
                        "applicationRole",
                        APPLICATION_ROLE
                ))
                .load()
                .migrate();

        applicationDataSource = dataSource(
                APPLICATION_ROLE,
                APPLICATION_PASSWORD
        );
        applicationJdbcTemplate =
                new JdbcTemplate(applicationDataSource);
        createFixture();
        runStatisticsFixture();
        patrolCatalogQueryService = new PatrolCatalogQueryService(
                applicationJdbcTemplate,
                new ObjectMapper());
        statisticsQueryService = new StatisticsQueryService(
                new StatisticsRepository(
                        new NamedParameterJdbcTemplate(
                                applicationDataSource)));
    }

    @Test
    void createsRequiredTablesIndexesAndRuntimeSelectPrivileges() {
        List<String> requiredTables = List.of(
                "robot.patrol_area",
                "robot.patrol_route",
                "robot.mission",
                "robot.mission_status_history",
                "telemetry.robot_event",
                "storage.robot_image",
                "defect.incident_code_counter",
                "defect.incident_ingestion_issue"
        );

        for (String table : requiredTables) {
            Boolean canSelect = ownerJdbcTemplate.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'SELECT')",
                    Boolean.class,
                    APPLICATION_ROLE,
                    table
            );
            Integer rowCount = applicationJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table,
                    Integer.class
            );

            assertThat(canSelect)
                    .as("%s runtime SELECT", table)
                    .isTrue();
            assertThat(rowCount)
                    .as("%s is queryable", table)
                    .isNotNull();
        }

        assertThat(indexExists("ix_patrol_area_boundary")).isTrue();
        assertThat(indexExists("ix_mission_robot_started")).isTrue();
        assertThat(indexExists("ix_mission_area_started")).isTrue();
        assertThat(indexExists("ix_mission_route_started")).isTrue();
        assertThat(indexExists("ix_mission_status_started")).isTrue();
        assertThat(indexExists("uq_mission_robot_active")).isTrue();
        assertThat(indexExists("ix_robot_event_robot_occurred")).isTrue();
        assertThat(indexExists("ix_robot_event_mission_occurred")).isTrue();
        assertThat(indexExists("ix_robot_telemetry_mission_sampled")).isTrue();
        assertThat(indexExists("ix_defect_current_last_seen_stats"))
                .isTrue();
        assertThat(indexExists(
                "ix_defect_current_area_last_seen_stats"))
                .isTrue();
        assertThat(indexExists(
                "ix_incident_ingestion_issue_open_created"))
                .isTrue();

        Boolean eventCanUpdate = ownerJdbcTemplate.queryForObject(
                "SELECT has_table_privilege(?, "
                        + "'telemetry.robot_event', 'UPDATE')",
                Boolean.class,
                APPLICATION_ROLE
        );
        Boolean historyCanUpdate = ownerJdbcTemplate.queryForObject(
                "SELECT has_table_privilege(?, "
                        + "'robot.mission_status_history', 'UPDATE')",
                Boolean.class,
                APPLICATION_ROLE
        );
        assertThat(eventCanUpdate).isFalse();
        assertThat(historyCanUpdate).isFalse();
    }

    @Test
    void enforcesEventIdempotencySequenceAndAppendOnlyHistory() {
        assertThatThrownBy(() -> ownerJdbcTemplate.update("""
                INSERT INTO telemetry.robot_event (
                    event_id,
                    schema_version,
                    robot_id,
                    boot_id,
                    seq,
                    mission_id,
                    event_type,
                    source_component,
                    occurred_at,
                    sent_at,
                    received_at,
                    raw_payload
                ) VALUES (
                    ?,
                    '2.0',
                    ?,
                    ?,
                    2,
                    ?,
                    'MISSION_PAUSED',
                    'mission',
                    '2026-07-28T01:05:00Z',
                    '2026-07-28T01:05:01Z',
                    '2026-07-28T01:05:02Z',
                    '{}'::JSONB
                )
                """,
                EVENT_ID,
                ROBOT_ID,
                BOOT_ID,
                MISSION_ID
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> ownerJdbcTemplate.update("""
                INSERT INTO telemetry.robot_event (
                    event_id,
                    schema_version,
                    robot_id,
                    boot_id,
                    seq,
                    mission_id,
                    event_type,
                    source_component,
                    occurred_at,
                    sent_at,
                    received_at,
                    raw_payload
                ) VALUES (
                    gen_random_uuid(),
                    '2.0',
                    ?,
                    ?,
                    1,
                    ?,
                    'MISSION_PAUSED',
                    'mission',
                    '2026-07-28T01:05:00Z',
                    '2026-07-28T01:05:01Z',
                    '2026-07-28T01:05:02Z',
                    '{}'::JSONB
                )
                """,
                ROBOT_ID,
                BOOT_ID,
                MISSION_ID
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> ownerJdbcTemplate.update(
                "UPDATE telemetry.robot_event "
                        + "SET received_at = NOW() WHERE event_id = ?",
                EVENT_ID
        )).hasMessageContaining("robot_event is append-only");

        assertThatThrownBy(() -> ownerJdbcTemplate.update(
                "UPDATE robot.mission_status_history "
                        + "SET transition_reason = 'changed' "
                        + "WHERE source_event_id = ?",
                EVENT_ID
        )).hasMessageContaining("mission_status_history is append-only");
    }

    @Test
    void enforcesMissionForeignKeysGeometryAndStateTransitions() {
        assertThatThrownBy(() -> ownerJdbcTemplate.update("""
                INSERT INTO robot.patrol_route (
                    patrol_route_id,
                    patrol_area_id,
                    route_code,
                    route_name,
                    route_version,
                    route_geometry,
                    lifecycle_status,
                    created_by,
                    updated_by
                ) VALUES (
                    gen_random_uuid(),
                    ?,
                    'BAD-GEOMETRY',
                    'Bad Geometry',
                    1,
                    ST_GeomFromText(
                        'POLYGON((127 37,128 37,128 38,127 37))',
                        4326
                    ),
                    'DRAFT',
                    ?,
                    ?
                )
                """,
                AREA_ID,
                ADMIN_ID,
                ADMIN_ID
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> ownerJdbcTemplate.update("""
                UPDATE robot.mission
                SET status = 'COMPLETED',
                    started_at = '2026-07-28T01:00:00Z',
                    ended_at = '2026-07-28T01:10:00Z',
                    progress_percent = 100,
                    completion_reason = 'ROUTE_COMPLETED',
                    updated_at = '2026-07-28T01:10:00Z'
                WHERE mission_id = ?
                """,
                MISSION_ID
        )).hasMessageContaining(
                "illegal mission status transition: CREATED -> COMPLETED");

        int running = ownerJdbcTemplate.update("""
                UPDATE robot.mission
                SET status = 'RUNNING',
                    started_at = '2026-07-28T01:00:00Z',
                    progress_percent = 10,
                    updated_at = '2026-07-28T01:00:00Z'
                WHERE mission_id = ?
                """,
                MISSION_ID
        );
        int completed = ownerJdbcTemplate.update("""
                UPDATE robot.mission
                SET status = 'COMPLETED',
                    ended_at = '2026-07-28T01:10:00Z',
                    progress_percent = 100,
                    completion_reason = 'ROUTE_COMPLETED',
                    result_duration_sec = 600,
                    result_incident_count = 1,
                    result_normal_detection_count = 5,
                    result_payload = '{"duration_sec":600}'::JSONB,
                    updated_at = '2026-07-28T01:10:00Z'
                WHERE mission_id = ?
                """,
                MISSION_ID
        );

        assertThat(running).isOne();
        assertThat(completed).isOne();
        assertThat(ownerJdbcTemplate.queryForObject(
                "SELECT status FROM robot.mission WHERE mission_id = ?",
                String.class,
                MISSION_ID
        )).isEqualTo("COMPLETED");
    }

    @Test
    void allowsReadyMissionToFailBeforeStartWithoutStartedAt() {
        UUID prestartFailureMissionId = UUID.fromString(
                "22000000-0000-4000-8000-000000000008");

        int inserted = applicationJdbcTemplate.update("""
                INSERT INTO robot.mission (
                    mission_id,
                    mission_code,
                    robot_id,
                    patrol_area_id,
                    patrol_route_id,
                    status,
                    scheduled_start_at,
                    created_by,
                    updated_by,
                    created_at,
                    updated_at
                ) VALUES (
                    ?,
                    'MIS-20260728-PRESTART-FAIL',
                    ?,
                    ?,
                    ?,
                    'CREATED',
                    '2026-07-28T01:00:00Z',
                    ?,
                    ?,
                    '2026-07-28T00:00:00Z',
                    '2026-07-28T00:00:00Z'
                )
                """,
                prestartFailureMissionId,
                ROBOT_ID,
                AREA_ID,
                ROUTE_ID,
                ADMIN_ID,
                ADMIN_ID);
        int ready = applicationJdbcTemplate.update("""
                UPDATE robot.mission
                SET status = 'READY',
                    updated_at = '2026-07-28T00:30:00Z'
                WHERE mission_id = ?
                """,
                prestartFailureMissionId);
        int failed = applicationJdbcTemplate.update("""
                UPDATE robot.mission
                SET status = 'FAILED',
                    ended_at = '2026-07-28T00:45:00Z',
                    progress_percent = 0,
                    completion_reason = 'PRECHECK_FAILED',
                    result_duration_sec = 0,
                    result_incident_count = 0,
                    result_normal_detection_count = 0,
                    result_payload =
                        '{"completion_reason":"PRECHECK_FAILED"}'::JSONB,
                    updated_at = '2026-07-28T00:45:00Z'
                WHERE mission_id = ?
                """,
                prestartFailureMissionId);

        Map<String, Object> stored = applicationJdbcTemplate.queryForMap("""
                SELECT
                    status,
                    started_at,
                    ended_at,
                    progress_percent,
                    completion_reason
                FROM robot.mission
                WHERE mission_id = ?
                """,
                prestartFailureMissionId);

        assertThat(inserted).isOne();
        assertThat(ready).isOne();
        assertThat(failed).isOne();
        assertThat(stored.get("status")).isEqualTo("FAILED");
        assertThat(stored.get("started_at")).isNull();
        assertThat(stored.get("ended_at")).isNotNull();
        assertThat(stored.get("progress_percent").toString())
                .isEqualTo("0.00");
        assertThat(stored.get("completion_reason"))
                .isEqualTo("PRECHECK_FAILED");

        assertThatThrownBy(() -> applicationJdbcTemplate.update("""
                INSERT INTO robot.mission (
                    mission_id,
                    mission_code,
                    robot_id,
                    patrol_area_id,
                    patrol_route_id,
                    status,
                    ended_at,
                    progress_percent,
                    completion_reason,
                    created_by,
                    updated_by,
                    created_at,
                    updated_at
                ) VALUES (
                    gen_random_uuid(),
                    'MIS-20260728-BAD-COMPLETED',
                    ?,
                    ?,
                    ?,
                    'COMPLETED',
                    '2026-07-28T00:45:00Z',
                    100,
                    'ROUTE_COMPLETED',
                    ?,
                    ?,
                    '2026-07-28T00:00:00Z',
                    '2026-07-28T00:45:00Z'
                )
                """,
                ROBOT_ID,
                AREA_ID,
                ROUTE_ID,
                ADMIN_ID,
                ADMIN_ID
        )).isInstanceOf(Exception.class);
    }

    @Test
    void statisticsFixtureIsDeterministicAndMatchesExpectedAggregates()
            throws Exception {
        assertStatisticsFixtureTotals();

        assertThat(missionCountForArea("A-11")).isEqualTo(2);
        assertThat(missionCountForArea("B-22")).isEqualTo(2);
        assertThat(durationForArea("A-11")).isEqualTo(5400);
        assertThat(durationForArea("B-22")).isEqualTo(6600);
        assertThat(missionCountForRoute("A-MAIN")).isOne();
        assertThat(missionCountForRoute("A-WEST")).isOne();
        assertThat(missionCountForRoute("B-MAIN")).isEqualTo(2);
        assertThat(defectCountForArea("A-11")).isEqualTo(2);
        assertThat(defectCountForArea("B-22")).isEqualTo(2);

        runStatisticsFixture();
        assertStatisticsFixtureTotals();

        String aggregateExplain = explain("""
                SELECT
                    m.status,
                    COUNT(*) AS mission_count,
                    COALESCE(SUM(m.result_duration_sec), 0)
                        AS duration_sec
                FROM robot.mission m
                WHERE m.patrol_area_id =
                    '30000000-0000-4000-8000-000000000001'
                  AND m.started_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND m.started_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                GROUP BY m.status
                ORDER BY m.status
                """);
        assertThat(aggregateExplain)
                .contains("Planning Time")
                .contains("Execution Time");
        System.out.println(
                "PATROL_STATISTICS_NATURAL_EXPLAIN\n"
                        + aggregateExplain);

        String indexEvidence = explainWithSeqScanDisabled("""
                SELECT
                    m.status,
                    COUNT(*) AS mission_count,
                    COALESCE(SUM(m.result_duration_sec), 0)
                        AS duration_sec
                FROM robot.mission m
                WHERE m.patrol_area_id =
                    '30000000-0000-4000-8000-000000000001'
                  AND m.started_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND m.started_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                GROUP BY m.status
                ORDER BY m.status
                """);
        assertThat(indexEvidence)
                .contains("ix_mission_area_started");
        System.out.println(
                "PATROL_STATISTICS_INDEX_EVIDENCE\n"
                        + indexEvidence);

        String defectNaturalExplain = explain("""
                SELECT
                    d.workflow_status,
                    d.defect_type,
                    COUNT(DISTINCT d.defect_id)
                FROM defect.defect d
                WHERE d.merged_into_defect_id IS NULL
                  AND d.last_seen_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND d.last_seen_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                GROUP BY d.workflow_status, d.defect_type
                """);
        System.out.println(
                "DEFECT_STATISTICS_NATURAL_EXPLAIN\n"
                        + defectNaturalExplain);

        String defectCurrentIndexEvidence =
                explainWithSeqScanDisabled("""
                        SELECT
                            d.workflow_status,
                            d.defect_type,
                            COUNT(DISTINCT d.defect_id)
                        FROM defect.defect d
                        WHERE d.merged_into_defect_id IS NULL
                          AND d.last_seen_at >=
                            TIMESTAMPTZ '2026-07-01T00:00:00Z'
                          AND d.last_seen_at <
                            TIMESTAMPTZ '2026-07-05T00:00:00Z'
                        GROUP BY
                            d.workflow_status,
                            d.defect_type
                        """);
        System.out.println(
                "DEFECT_STATISTICS_CURRENT_INDEX_EVIDENCE\n"
                        + defectCurrentIndexEvidence);

        String defectPeriodIndexEvidence =
                explainWithSeqScanDisabled("""
                        SELECT
                            (
                                d.last_seen_at AT TIME ZONE 'UTC'
                            )::date AS bucket_date,
                            COUNT(DISTINCT d.defect_id)
                        FROM defect.defect d
                        WHERE d.merged_into_defect_id IS NULL
                          AND d.last_seen_at >=
                            TIMESTAMPTZ '2026-07-01T00:00:00Z'
                          AND d.last_seen_at <
                            TIMESTAMPTZ '2026-07-05T00:00:00Z'
                        GROUP BY bucket_date
                        ORDER BY bucket_date
                        """);
        assertThat(defectPeriodIndexEvidence)
                .contains("Index Only Scan")
                .contains("ix_defect_current_");
        System.out.println(
                "DEFECT_PERIOD_STATISTICS_INDEX_EVIDENCE\n"
                        + defectPeriodIndexEvidence);

        String defectAreaCurrentIndexEvidence =
                explainWithSeqScanDisabled("""
                        SELECT
                            d.workflow_status,
                            d.defect_type,
                            COUNT(DISTINCT d.defect_id)
                        FROM defect.defect d
                        WHERE d.merged_into_defect_id IS NULL
                          AND d.patrol_area_id =
                            '30000000-0000-4000-8000-000000000001'
                          AND d.last_seen_at >=
                            TIMESTAMPTZ '2026-07-01T00:00:00Z'
                          AND d.last_seen_at <
                            TIMESTAMPTZ '2026-07-05T00:00:00Z'
                        GROUP BY
                            d.workflow_status,
                            d.defect_type
                        """);
        assertThat(defectAreaCurrentIndexEvidence)
                .contains("ix_defect_current_area_last_seen_stats");
        System.out.println(
                "DEFECT_AREA_STATISTICS_CURRENT_INDEX_EVIDENCE\n"
                        + defectAreaCurrentIndexEvidence);

        assertIndexCanServe("ix_mission_robot_started", """
                SELECT mission_id
                FROM robot.mission
                WHERE robot_id =
                    '20000000-0000-4000-8000-000000000001'
                  AND started_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND started_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY started_at DESC
                """);
        assertIndexCanServe("ix_defect_current_last_seen_stats", """
                SELECT
                    defect_id,
                    patrol_area_id,
                    defect_type,
                    workflow_status
                FROM defect.defect
                WHERE merged_into_defect_id IS NULL
                  AND last_seen_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND last_seen_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY last_seen_at DESC, defect_id
                """);
        assertIndexCanServe(
                "ix_defect_current_area_last_seen_stats",
                """
                SELECT
                    defect_id,
                    defect_type,
                    workflow_status
                FROM defect.defect
                WHERE merged_into_defect_id IS NULL
                  AND patrol_area_id =
                    '30000000-0000-4000-8000-000000000001'
                  AND last_seen_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND last_seen_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY last_seen_at DESC, defect_id
                """);
        assertIndexCanServe("ix_mission_area_started", """
                SELECT mission_id
                FROM robot.mission
                WHERE patrol_area_id =
                    '30000000-0000-4000-8000-000000000001'
                  AND started_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND started_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY started_at DESC
                """);
        assertIndexCanServe("ix_mission_route_started", """
                SELECT mission_id
                FROM robot.mission
                WHERE patrol_route_id =
                    '31000000-0000-4000-8000-000000000003'
                  AND started_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND started_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY started_at DESC
                """);
        assertIndexCanServe("ix_mission_status_started", """
                SELECT mission_id
                FROM robot.mission
                WHERE status = 'COMPLETED'
                  AND started_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND started_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY started_at DESC
                """);
        assertIndexCanServe("ix_robot_event_robot_occurred", """
                SELECT event_id
                FROM telemetry.robot_event
                WHERE robot_id =
                    '20000000-0000-4000-8000-000000000001'
                  AND occurred_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND occurred_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY occurred_at DESC
                """);
        assertIndexCanServe("ix_robot_event_mission_occurred", """
                SELECT event_id
                FROM telemetry.robot_event
                WHERE mission_id =
                    '40000000-0000-4000-8000-000000000004'
                  AND occurred_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND occurred_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY occurred_at DESC
                """);
        assertIndexCanServe("ix_robot_telemetry_mission_sampled", """
                SELECT message_id
                FROM telemetry.robot_telemetry
                WHERE mission_id =
                    '40000000-0000-4000-8000-000000000004'
                  AND sampled_at >=
                    TIMESTAMPTZ '2026-07-01T00:00:00Z'
                  AND sampled_at <
                    TIMESTAMPTZ '2026-07-05T00:00:00Z'
                ORDER BY sampled_at DESC
                """);
        assertIndexCanServe("ix_patrol_area_boundary", """
                SELECT patrol_area_id
                FROM robot.patrol_area
                WHERE boundary && ST_MakeEnvelope(
                    127.0000,
                    37.0000,
                    127.0150,
                    37.0150,
                    4326
                )
                """);
    }

    @Test
    void statusStatisticsApplyScopeExcludeMergedAndZeroFillEnums() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-05T00:00:00Z");
        UUID fixtureAdminId = UUID.fromString(
                "10000000-0000-4000-8000-000000000001");
        UUID scopedViewerId = UUID.fromString(
                "10000000-0000-4000-8000-000000000002");

        var all = statisticsQueryService.findByStatus(
                fixtureAdminId,
                from,
                to);
        assertThat(all.totalDefectCount()).isEqualTo(4);
        assertThat(all.items())
                .extracting(
                        item -> item.workflowStatus(),
                        item -> item.defectCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                DefectWorkflowStatus.NEW, 2L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectWorkflowStatus.ACCEPTED, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectWorkflowStatus.IN_PROGRESS, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectWorkflowStatus.RESOLVED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectWorkflowStatus.FALSE_POSITIVE, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectWorkflowStatus.REJECTED, 0L));

        var scoped = statisticsQueryService.findByStatus(
                scopedViewerId,
                from,
                to);
        assertThat(scoped.totalDefectCount()).isEqualTo(2);
        assertThat(scoped.items())
                .extracting(item -> item.defectCount())
                .containsExactly(1L, 1L, 0L, 0L, 0L, 0L);

        var empty = statisticsQueryService.findByStatus(
                fixtureAdminId,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-02T00:00:00Z"));
        assertThat(empty.totalDefectCount()).isZero();
        assertThat(empty.items())
                .hasSize(DefectWorkflowStatus.values().length)
                .allMatch(item -> item.defectCount() == 0);

        assertThat(statisticsQueryService.findByStatus(
                fixtureAdminId,
                from,
                to)).isEqualTo(all);
    }

    @Test
    void defectTypeStatisticsApplyScopeExcludeMergedAndZeroFillEnums() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-05T00:00:00Z");
        UUID fixtureAdminId = UUID.fromString(
                "10000000-0000-4000-8000-000000000001");
        UUID scopedViewerId = UUID.fromString(
                "10000000-0000-4000-8000-000000000002");

        var all = statisticsQueryService.findByDefectType(
                fixtureAdminId,
                from,
                to);
        assertThat(all.totalDefectCount()).isEqualTo(4);
        assertThat(all.items())
                .extracting(
                        item -> item.defectType(),
                        item -> item.defectCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.DAMAGED, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.MISSING, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.INSTALLATION_INVALID, 0L),
                        // 유형 축소(V36) 전 CONTAMINATED·UPLIFTED·BLOCKED였던
                        // 픽스처 3건이 기타 이상으로 합쳐졌다.
                        org.assertj.core.groups.Tuple.tuple(
                                DefectType.UNKNOWN_ANOMALY, 3L));

        var scoped = statisticsQueryService.findByDefectType(
                scopedViewerId,
                from,
                to);
        assertThat(scoped.totalDefectCount()).isEqualTo(2);
        assertThat(scoped.items())
                .extracting(item -> item.defectCount())
                .containsExactly(1L, 0L, 0L, 1L);

        var empty = statisticsQueryService.findByDefectType(
                fixtureAdminId,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-02T00:00:00Z"));
        assertThat(empty.totalDefectCount()).isZero();
        assertThat(empty.items())
                .hasSize(DefectType.values().length)
                .allMatch(item -> item.defectCount() == 0);

        assertThat(statisticsQueryService.findByDefectType(
                fixtureAdminId,
                from,
                to)).isEqualTo(all);
    }

    @Test
    void areaStatisticsAggregateSourcesBeforeJoiningAndApplyScope() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-05T00:00:00Z");
        UUID fixtureAdminId = UUID.fromString(
                "10000000-0000-4000-8000-000000000001");
        UUID scopedViewerId = UUID.fromString(
                "10000000-0000-4000-8000-000000000002");

        var all = statisticsQueryService.findByArea(
                fixtureAdminId,
                from,
                to,
                null,
                0,
                20);
        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(all.totalPages()).isEqualTo(1);
        assertThat(all.items())
                .extracting(StatisticsByAreaItem::areaCode)
                .containsExactly("A-01", "A-11", "B-22");

        StatisticsByAreaItem areaA = all.items().get(1);
        assertThat(areaA.metrics().missionCount()).isEqualTo(2);
        assertThat(areaA.metrics().completedMissionCount()).isEqualTo(1);
        assertThat(areaA.metrics().cancelledMissionCount()).isEqualTo(1);
        assertThat(areaA.metrics().failedMissionCount()).isZero();
        assertThat(areaA.metrics().completionRatePercent())
                .isEqualByComparingTo("50.00");
        assertThat(areaA.metrics().totalMissionDurationSeconds())
                .isEqualTo(5_400);
        assertThat(areaA.metrics().incidentEventCount()).isEqualTo(3);
        assertThat(areaA.metrics().defectCount()).isEqualTo(2);
        assertThat(areaA.metrics().observationCount()).isEqualTo(3);

        StatisticsByAreaItem areaB = all.items().get(2);
        assertThat(areaB.metrics().missionCount()).isEqualTo(2);
        assertThat(areaB.metrics().completedMissionCount()).isEqualTo(1);
        assertThat(areaB.metrics().cancelledMissionCount()).isZero();
        assertThat(areaB.metrics().failedMissionCount()).isEqualTo(1);
        assertThat(areaB.metrics().completionRatePercent())
                .isEqualByComparingTo("50.00");
        assertThat(areaB.metrics().totalMissionDurationSeconds())
                .isEqualTo(6_600);
        assertThat(areaB.metrics().incidentEventCount()).isEqualTo(3);
        assertThat(areaB.metrics().defectCount()).isEqualTo(2);
        assertThat(areaB.metrics().observationCount()).isEqualTo(3);

        var scoped = statisticsQueryService.findByArea(
                scopedViewerId,
                from,
                to,
                null,
                0,
                20);
        assertThat(scoped.items().get(1).metrics())
                .isEqualTo(areaA.metrics());
        assertThat(scoped.items().get(2).metrics())
                .satisfies(metrics -> {
                    assertThat(metrics.missionCount()).isZero();
                    assertThat(metrics.incidentEventCount()).isZero();
                    assertThat(metrics.defectCount()).isZero();
                    assertThat(metrics.observationCount()).isZero();
                });

        var emptyExistingArea = statisticsQueryService.findByArea(
                fixtureAdminId,
                from,
                to,
                AREA_ID,
                0,
                20);
        assertThat(emptyExistingArea.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.areaCode()).isEqualTo("A-01");
                    assertThat(item.metrics().missionCount()).isZero();
                    assertThat(item.metrics().incidentEventCount()).isZero();
                    assertThat(item.metrics().defectCount()).isZero();
                    assertThat(item.metrics().observationCount()).isZero();
                });

        var secondPage = statisticsQueryService.findByArea(
                fixtureAdminId,
                from,
                to,
                null,
                1,
                1);
        assertThat(secondPage.totalElements()).isEqualTo(3);
        assertThat(secondPage.totalPages()).isEqualTo(3);
        assertThat(secondPage.items())
                .extracting(StatisticsByAreaItem::areaCode)
                .containsExactly("A-11");

        assertThat(statisticsQueryService.findByArea(
                fixtureAdminId,
                from,
                to,
                null,
                0,
                20)).isEqualTo(all);
    }

    @Test
    void actualStatisticsRepositoryQueriesHaveReviewableExecutionPlans() {
        UUID fixtureAdminId = UUID.fromString(
                "10000000-0000-4000-8000-000000000001");
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-05T00:00:00Z");
        CapturingNamedParameterJdbcTemplate capturingTemplate =
                new CapturingNamedParameterJdbcTemplate(
                        applicationDataSource);
        StatisticsQueryService capturingService =
                new StatisticsQueryService(
                        new StatisticsRepository(capturingTemplate));

        capturingService.findByStatus(fixtureAdminId, from, to);
        assertActualStatisticsExplain(
                "STATISTICS_BY_STATUS",
                capturingTemplate.capturedQuery());

        capturingService.findByDefectType(fixtureAdminId, from, to);
        assertActualStatisticsExplain(
                "STATISTICS_BY_DEFECT_TYPE",
                capturingTemplate.capturedQuery());

        capturingService.findByArea(
                fixtureAdminId,
                from,
                to,
                null,
                0,
                20);
        assertActualStatisticsExplain(
                "STATISTICS_BY_AREA",
                capturingTemplate.capturedQuery());

        capturingService.findByPeriod(
                fixtureAdminId,
                from,
                to,
                "DAY");
        assertActualStatisticsExplain(
                "STATISTICS_BY_PERIOD",
                capturingTemplate.capturedQuery());
    }

    @Test
    void periodStatisticsUseUtcHalfOpenBucketsScopeAndZeroFill() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-05T00:00:00Z");
        UUID fixtureAdminId = UUID.fromString(
                "10000000-0000-4000-8000-000000000001");
        UUID scopedViewerId = UUID.fromString(
                "10000000-0000-4000-8000-000000000002");

        var all = statisticsQueryService.findByPeriod(
                fixtureAdminId,
                from,
                to,
                "DAY");
        assertThat(all.items())
                .extracting(StatisticsByPeriodItem::date)
                .containsExactly(
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 3),
                        LocalDate.of(2026, 7, 4));
        assertPeriodMetrics(
                all.items().get(0),
                1, 1, 0, 0,
                3_600, 2, 1, 2);
        assertPeriodMetrics(
                all.items().get(1),
                1, 0, 1, 0,
                1_800, 1, 1, 1);
        assertPeriodMetrics(
                all.items().get(2),
                1, 0, 0, 1,
                1_200, 0, 0, 0);
        assertPeriodMetrics(
                all.items().get(3),
                1, 1, 0, 0,
                5_400, 3, 2, 3);

        var repeated = statisticsQueryService.findByPeriod(
                fixtureAdminId,
                from,
                to,
                "DAY");
        assertThat(repeated).isEqualTo(all);

        var exactBoundary = statisticsQueryService.findByPeriod(
                fixtureAdminId,
                from,
                Instant.parse("2026-07-02T00:00:00Z"),
                "DAY");
        assertThat(exactBoundary.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.date())
                            .isEqualTo(LocalDate.of(2026, 7, 1));
                    assertPeriodMetrics(
                            item,
                            1, 1, 0, 0,
                            3_600, 2, 1, 2);
                });

        var partialBoundary = statisticsQueryService.findByPeriod(
                fixtureAdminId,
                Instant.parse("2026-07-01T00:20:00Z"),
                Instant.parse("2026-07-02T00:10:00Z"),
                "DAY");
        assertThat(partialBoundary.items())
                .extracting(StatisticsByPeriodItem::date)
                .containsExactly(
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2));
        assertPeriodMetrics(
                partialBoundary.items().get(0),
                0, 0, 0, 0,
                0, 2, 1, 2);
        assertPeriodMetrics(
                partialBoundary.items().get(1),
                1, 0, 1, 0,
                1_800, 0, 0, 0);

        var scoped = statisticsQueryService.findByPeriod(
                scopedViewerId,
                from,
                to,
                "DAY");
        assertThat(scoped.items().subList(0, 2))
                .isEqualTo(all.items().subList(0, 2));
        assertThat(scoped.items().subList(2, 4))
                .allSatisfy(item -> assertPeriodMetrics(
                        item,
                        0, 0, 0, 0,
                        0, 0, 0, 0));

        var empty = statisticsQueryService.findByPeriod(
                fixtureAdminId,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z"),
                "DAY");
        assertThat(empty.items())
                .extracting(StatisticsByPeriodItem::date)
                .containsExactly(
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 2));
        assertThat(empty.items())
                .allSatisfy(item -> assertPeriodMetrics(
                        item,
                        0, 0, 0, 0,
                        0, 0, 0, 0));

        assertInvalidStatisticsPeriod(
                () -> statisticsQueryService.findByPeriod(
                        fixtureAdminId,
                        from,
                        from,
                        "DAY"));
        assertInvalidStatisticsPeriod(
                () -> statisticsQueryService.findByPeriod(
                        fixtureAdminId,
                        to,
                        from,
                        "DAY"));
        assertInvalidStatisticsPeriod(
                () -> statisticsQueryService.findByPeriod(
                        fixtureAdminId,
                        from,
                        from.plus(Duration.ofDays(366)).plusNanos(1),
                        "DAY"));
    }

    @Test
    void runtimeCatalogReturnsAreaAndRouteGeoJson() {
        PatrolAreaResponse area = patrolCatalogQueryService.findArea(
                UUID.fromString(
                        "30000000-0000-4000-8000-000000000001"));

        assertThat(area.areaCode()).isEqualTo("A-11");
        assertThat(area.boundary().path("type").asText())
                .isEqualTo("Polygon");
        assertThat(patrolCatalogQueryService.findRoutes(
                area.patrolAreaId()).routes())
                .extracting(route -> route.routeCode())
                .containsExactly("A-MAIN", "A-WEST");
        assertThat(patrolCatalogQueryService.findAreas().areas())
                .extracting(PatrolAreaResponse::areaCode)
                .contains("A-01", "A-11", "B-22");

        UUID missingArea = UUID.fromString(
                "30000000-0000-4000-8000-000000000099");
        assertThatThrownBy(() ->
                patrolCatalogQueryService.findArea(missingArea))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.PATROL_AREA_NOT_FOUND));
        assertThatThrownBy(() ->
                patrolCatalogQueryService.findRoutes(missingArea))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.PATROL_AREA_NOT_FOUND));
    }

    private static void assertStatisticsFixtureTotals() {
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.mission
                WHERE started_at >= '2026-07-01T00:00:00Z'
                  AND started_at < '2026-07-05T00:00:00Z'
                """, Integer.class)).isEqualTo(4);
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.mission
                WHERE started_at >= '2026-07-01T00:00:00Z'
                  AND started_at < '2026-07-05T00:00:00Z'
                  AND status = 'COMPLETED'
                """, Integer.class)).isEqualTo(2);
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.mission
                WHERE started_at >= '2026-07-01T00:00:00Z'
                  AND started_at < '2026-07-05T00:00:00Z'
                  AND status = 'CANCELLED'
                """, Integer.class)).isOne();
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.mission
                WHERE started_at >= '2026-07-01T00:00:00Z'
                  AND started_at < '2026-07-05T00:00:00Z'
                  AND status = 'FAILED'
                """, Integer.class)).isOne();
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(result_duration_sec), 0)
                FROM robot.mission
                WHERE started_at >= '2026-07-01T00:00:00Z'
                  AND started_at < '2026-07-05T00:00:00Z'
                """, Integer.class)).isEqualTo(12000);
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM telemetry.robot_event
                WHERE occurred_at >= '2026-07-01T00:00:00Z'
                  AND occurred_at < '2026-07-05T00:00:00Z'
                  AND event_type =
                    'BRAILLE_BLOCK_INCIDENT_DETECTED'
                """, Integer.class)).isEqualTo(6);
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM telemetry.robot_telemetry
                WHERE mission_id IN (
                    SELECT mission_id
                    FROM robot.mission
                    WHERE started_at >= '2026-07-01T00:00:00Z'
                      AND started_at < '2026-07-05T00:00:00Z'
                )
                """, Integer.class)).isEqualTo(8);
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM defect.defect_observation
                WHERE event_id IN (
                    SELECT event_id
                    FROM telemetry.robot_event
                    WHERE occurred_at >= '2026-07-01T00:00:00Z'
                      AND occurred_at < '2026-07-05T00:00:00Z'
                      AND event_type =
                        'BRAILLE_BLOCK_INCIDENT_DETECTED'
                )
                """, Integer.class)).isEqualTo(6);
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM defect.defect
                """, Integer.class)).isEqualTo(5);
        assertThat(applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM defect.defect
                WHERE merged_into_defect_id IS NULL
                """, Integer.class)).isEqualTo(4);
    }

    private static void assertPeriodMetrics(
            StatisticsByPeriodItem item,
            long missionCount,
            long completedMissionCount,
            long cancelledMissionCount,
            long failedMissionCount,
            long totalMissionDurationSeconds,
            long incidentEventCount,
            long defectCount,
            long observationCount
    ) {
        assertThat(item.metrics().missionCount()).isEqualTo(missionCount);
        assertThat(item.metrics().completedMissionCount())
                .isEqualTo(completedMissionCount);
        assertThat(item.metrics().cancelledMissionCount())
                .isEqualTo(cancelledMissionCount);
        assertThat(item.metrics().failedMissionCount())
                .isEqualTo(failedMissionCount);
        assertThat(item.metrics().completionRatePercent())
                .isEqualByComparingTo(missionCount == 0
                        ? BigDecimal.ZERO.setScale(2)
                        : BigDecimal.valueOf(completedMissionCount)
                                .multiply(BigDecimal.valueOf(100))
                                .divide(
                                        BigDecimal.valueOf(missionCount),
                                        2,
                                        RoundingMode.HALF_UP));
        assertThat(item.metrics().totalMissionDurationSeconds())
                .isEqualTo(totalMissionDurationSeconds);
        assertThat(item.metrics().incidentEventCount())
                .isEqualTo(incidentEventCount);
        assertThat(item.metrics().defectCount()).isEqualTo(defectCount);
        assertThat(item.metrics().observationCount())
                .isEqualTo(observationCount);
    }

    private static void assertInvalidStatisticsPeriod(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.STATISTICS_PERIOD_INVALID));
    }

    private static int missionCountForArea(String areaCode) {
        return applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.mission m
                JOIN robot.patrol_area a
                  ON a.patrol_area_id = m.patrol_area_id
                WHERE a.area_code = ?
                  AND m.started_at >= '2026-07-01T00:00:00Z'
                  AND m.started_at < '2026-07-05T00:00:00Z'
                """, Integer.class, areaCode);
    }

    private static int durationForArea(String areaCode) {
        return applicationJdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(m.result_duration_sec), 0)
                FROM robot.mission m
                JOIN robot.patrol_area a
                  ON a.patrol_area_id = m.patrol_area_id
                WHERE a.area_code = ?
                  AND m.started_at >= '2026-07-01T00:00:00Z'
                  AND m.started_at < '2026-07-05T00:00:00Z'
                """, Integer.class, areaCode);
    }

    private static int missionCountForRoute(String routeCode) {
        return applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.mission m
                JOIN robot.patrol_route r
                  ON r.patrol_route_id = m.patrol_route_id
                WHERE r.route_code = ?
                  AND m.started_at >= '2026-07-01T00:00:00Z'
                  AND m.started_at < '2026-07-05T00:00:00Z'
                """, Integer.class, routeCode);
    }

    private static int defectCountForArea(String areaCode) {
        return applicationJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM defect.defect d
                JOIN robot.patrol_area a
                  ON a.patrol_area_id = d.patrol_area_id
                WHERE a.area_code = ?
                  AND d.first_seen_at >= '2026-07-01T00:00:00Z'
                  AND d.first_seen_at < '2026-07-05T00:00:00Z'
                  AND d.merged_into_defect_id IS NULL
                """, Integer.class, areaCode);
    }

    private static String explain(String query) {
        return String.join(
                System.lineSeparator(),
                ownerJdbcTemplate.queryForList(
                        "EXPLAIN (ANALYZE, BUFFERS) " + query,
                        String.class));
    }

    private static void assertActualStatisticsExplain(
            String label,
            CapturedQuery query
    ) {
        assertThat(query).isNotNull();
        String plan = String.join(
                System.lineSeparator(),
                new NamedParameterJdbcTemplate(applicationDataSource)
                        .queryForList(
                                "EXPLAIN (ANALYZE, BUFFERS) "
                                        + query.sql(),
                                query.parameters(),
                                String.class));
        assertThat(plan)
                .contains("Planning Time")
                .contains("Execution Time");
        System.out.println(label + "_ACTUAL_QUERY_EXPLAIN\n" + plan);
    }

    private static String explainWithSeqScanDisabled(String query)
            throws Exception {
        try (Connection connection = ownerDataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (ResultSet resultSet = statement.executeQuery(
                    "EXPLAIN (ANALYZE, BUFFERS) " + query)) {
                StringBuilder plan = new StringBuilder();
                while (resultSet.next()) {
                    if (!plan.isEmpty()) {
                        plan.append(System.lineSeparator());
                    }
                    plan.append(resultSet.getString(1));
                }
                return plan.toString();
            }
        }
    }

    private static void assertIndexCanServe(
            String indexName,
            String query
    ) throws Exception {
        assertThat(explainWithSeqScanDisabled(query))
                .as("%s can serve its representative query", indexName)
                .contains(indexName);
    }

    private static void runStatisticsFixture() throws Exception {
        try (Connection connection = ownerDataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "fixtures/patrol-mission-statistics.sql"));
        }
    }

    private static boolean indexExists(String indexName) {
        Boolean exists = ownerJdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_indexes
                    WHERE indexname = ?
                )
                """,
                Boolean.class,
                indexName
        );
        return Boolean.TRUE.equals(exists);
    }

    private static void createFixture() {
        ownerJdbcTemplate.update("""
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
                    'ADM-PATROL-SCHEMA',
                    'patrol_schema_admin',
                    '{argon2}$argon2id$v=19$m=19456,t=2,p=1$schema-hash',
                    'Patrol Schema Admin',
                    'patrol-schema@example.invalid',
                    'ACTIVE',
                    TRUE,
                    'ADMIN_CREATED'
                )
                """,
                ADMIN_ID
        );
        ownerJdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (
                    ?,
                    'RBT-PATROL-SCHEMA',
                    'Patrol Schema Robot',
                    'SN-PATROL-SCHEMA',
                    'ACTIVE',
                    ?
                )
                """,
                ROBOT_ID,
                ADMIN_ID
        );
        ownerJdbcTemplate.update("""
                INSERT INTO telemetry.robot_boot_session (
                    boot_id,
                    robot_id,
                    schema_version,
                    session_status,
                    first_received_at,
                    last_received_at
                ) VALUES (
                    ?,
                    ?,
                    '2.0',
                    'ONLINE',
                    '2026-07-28T00:59:00Z',
                    '2026-07-28T01:10:00Z'
                )
                """,
                BOOT_ID,
                ROBOT_ID
        );
        ownerJdbcTemplate.update("""
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
                    'A-01',
                    'Patrol Area A',
                    ST_GeomFromText(
                        'POLYGON(('
                        || '127.00 37.00,'
                        || '127.10 37.00,'
                        || '127.10 37.10,'
                        || '127.00 37.10,'
                        || '127.00 37.00))',
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
                ADMIN_ID
        );
        ownerJdbcTemplate.update("""
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
                    'Patrol Area A Main Route',
                    1,
                    ST_GeomFromText(
                        'LINESTRING('
                        || '127.01 37.01,'
                        || '127.05 37.05,'
                        || '127.09 37.09)',
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
                ADMIN_ID
        );
        ownerJdbcTemplate.update("""
                INSERT INTO robot.mission (
                    mission_id,
                    mission_code,
                    robot_id,
                    patrol_area_id,
                    patrol_route_id,
                    status,
                    scheduled_start_at,
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
                    'CREATED',
                    '2026-07-28T01:00:00Z',
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
                ADMIN_ID
        );
        ownerJdbcTemplate.update("""
                INSERT INTO telemetry.robot_event (
                    event_id,
                    schema_version,
                    robot_id,
                    boot_id,
                    seq,
                    mission_id,
                    event_type,
                    source_component,
                    occurred_at,
                    sent_at,
                    received_at,
                    raw_payload
                ) VALUES (
                    ?,
                    '2.0',
                    ?,
                    ?,
                    1,
                    ?,
                    'MISSION_STARTED',
                    'mission',
                    '2026-07-28T01:00:00Z',
                    '2026-07-28T01:00:01Z',
                    '2026-07-28T01:00:02Z',
                    '{"event_type":"MISSION_STARTED"}'::JSONB
                )
                """,
                EVENT_ID,
                ROBOT_ID,
                BOOT_ID,
                MISSION_ID
        );
        ownerJdbcTemplate.update("""
                INSERT INTO robot.mission_status_history (
                    history_id,
                    mission_id,
                    source_event_id,
                    transition_source,
                    before_status,
                    after_status,
                    occurred_at,
                    received_at
                ) VALUES (
                    gen_random_uuid(),
                    ?,
                    ?,
                    'MQTT',
                    'CREATED',
                    'RUNNING',
                    '2026-07-28T01:00:00Z',
                    '2026-07-28T01:00:02Z'
                )
                """,
                MISSION_ID,
                EVENT_ID
        );
    }

    private static DataSource dataSource(
            String username,
            String password
    ) {
        return new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                username,
                password
        );
    }

    private record CapturedQuery(
            String sql,
            SqlParameterSource parameters
    ) {
    }

    private static final class CapturingNamedParameterJdbcTemplate
            extends NamedParameterJdbcTemplate {

        private CapturedQuery capturedQuery;

        private CapturingNamedParameterJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(
                String sql,
                SqlParameterSource parameterSource,
                RowMapper<T> rowMapper
        ) {
            capturedQuery = new CapturedQuery(sql, parameterSource);
            return super.query(sql, parameterSource, rowMapper);
        }

        private CapturedQuery capturedQuery() {
            return capturedQuery;
        }
    }
}
