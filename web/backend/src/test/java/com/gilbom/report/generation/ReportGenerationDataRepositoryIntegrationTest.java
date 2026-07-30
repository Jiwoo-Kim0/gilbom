package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.report.generation.ReportGenerationDataRepository.Snapshot;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes =
                ReportGenerationDataRepositoryIntegrationTest
                        .TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class ReportGenerationDataRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID ROBOT_ID = UUID.randomUUID();
    private static final UUID BOOT_ID = UUID.randomUUID();
    private static final UUID DEFECT_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("report_generation_data_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add(
                "spring.datasource.username",
                DATABASE::getUsername);
        registry.add(
                "spring.datasource.password",
                DATABASE::getPassword);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add(
                "spring.flyway.password",
                DATABASE::getPassword);
        registry.add(
                "spring.flyway.default-schema",
                () -> "flyway_history");
        registry.add(
                "spring.flyway.placeholders.applicationRole",
                DATABASE::getUsername);
    }

    @Autowired
    private ReportGenerationDataRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void createSystemAdmin() {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    password_hash,
                    display_name,
                    email,
                    is_active,
                    account_status,
                    password_change_required,
                    password_changed_at
                ) VALUES (
                    ?, 'REPORT-DATA-ADMIN', ?, '보고서 데이터 관리자',
                    'report-data-admin@example.test',
                    TRUE, 'ACTIVE', FALSE, NOW()
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                ADMIN_ID,
                passwordEncoder.encode(
                        "report-generation-data-password"));
        jdbcTemplate.update("""
                INSERT INTO auth.user_role (
                    user_id,
                    role_id,
                    is_primary,
                    is_active
                )
                SELECT ?, role_id, TRUE, TRUE
                FROM auth.role
                WHERE role_code = 'ADMIN'
                ON CONFLICT (user_id, role_id) DO NOTHING
                """,
                ADMIN_ID);
        createGenerationFixture();
    }

    @Test
    void loadsOneScopedInternallyConsistentSnapshot() {
        Snapshot snapshot = repository.load(
                ADMIN_ID,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-08T00:00:00Z"),
                ReportGenerationCriteria.from(Map.of()));

        assertThat(snapshot.aggregate().missionCount()).isZero();
        assertThat(snapshot.aggregate().defectCount()).isEqualTo(1);
        assertThat(snapshot.aggregate().incidentEventCount())
                .isEqualTo(1);
        assertThat(snapshot.aggregate().observationCount())
                .isEqualTo(1);
        assertThat(snapshot.statusCounts()).hasSize(6);
        assertThat(snapshot.typeCounts()).hasSize(7);
        assertThat(snapshot.riskCounts()).hasSize(4);
        assertThat(snapshot.evidence()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.defectId())
                            .isEqualTo(DEFECT_ID);
                    assertThat(evidence.incidentCode())
                            .isEqualTo("INC-20260703-000001");
                    assertThat(evidence.locationLabel())
                            .isEqualTo("A-01");
                });
        assertThat(snapshot.evidenceMetadata()).hasSize(1);
    }

    private void createGenerationFixture() {
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (
                    ?, 'report-data-robot', '보고서 데이터 로봇',
                    'SN-REPORT-DATA-ROBOT', 'ACTIVE', ?
                )
                ON CONFLICT (robot_id) DO NOTHING
                """,
                ROBOT_ID,
                ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_boot_session (
                    boot_id,
                    robot_id,
                    schema_version,
                    session_status,
                    first_received_at,
                    last_received_at
                ) VALUES (
                    ?, ?, '2.0', 'ONLINE',
                    '2026-07-03T00:00:00Z',
                    '2026-07-03T00:00:01Z'
                )
                ON CONFLICT (boot_id) DO NOTHING
                """,
                BOOT_ID,
                ROBOT_ID);
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_event (
                    event_id,
                    schema_version,
                    robot_id,
                    boot_id,
                    seq,
                    event_type,
                    source_component,
                    occurred_at,
                    sent_at,
                    raw_payload
                ) VALUES (
                    ?, '2.0', ?, ?, 1,
                    'BRAILLE_BLOCK_INCIDENT_DETECTED', 'ai',
                    '2026-07-03T01:00:00Z',
                    '2026-07-03T01:00:01Z',
                    '{}'::JSONB
                )
                ON CONFLICT (event_id) DO NOTHING
                """,
                EVENT_ID,
                ROBOT_ID,
                BOOT_ID);
        jdbcTemplate.update("""
                INSERT INTO defect.defect (
                    defect_id,
                    incident_code,
                    block_type,
                    defect_type,
                    risk_level,
                    workflow_status,
                    location,
                    zone_id,
                    first_seen_at,
                    last_seen_at
                ) VALUES (
                    ?, 'INC-20260703-000001',
                    'LINEAR', 'DAMAGED', 'HIGH', 'NEW',
                    ST_SetSRID(
                        ST_MakePoint(127.01, 37.51),
                        4326
                    ),
                    'A-01',
                    '2026-07-03T01:00:00Z',
                    '2026-07-03T01:00:00Z'
                )
                ON CONFLICT (defect_id) DO NOTHING
                """,
                DEFECT_ID);
        jdbcTemplate.update("""
                INSERT INTO defect.defect_observation (
                    event_id,
                    robot_id,
                    defect_id,
                    location_valid,
                    location,
                    zone_id,
                    asset_type,
                    block_type,
                    detected_defect_type,
                    confidence,
                    model_name,
                    model_version,
                    is_representative,
                    created_at
                ) VALUES (
                    ?, ?, ?, TRUE,
                    ST_SetSRID(
                        ST_MakePoint(127.01, 37.51),
                        4326
                    ),
                    'A-01', 'BRAILLE_BLOCK', 'LINEAR',
                    'DAMAGED', 0.910,
                    'report-data-model', '1.0.0', TRUE,
                    '2026-07-03T01:00:02Z'
                )
                ON CONFLICT (event_id) DO NOTHING
                """,
                EVENT_ID,
                ROBOT_ID,
                DEFECT_ID);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            ReportGenerationDataRepository.class,
            PasswordHashingConfiguration.class
    })
    static class TestApplication {
    }
}
