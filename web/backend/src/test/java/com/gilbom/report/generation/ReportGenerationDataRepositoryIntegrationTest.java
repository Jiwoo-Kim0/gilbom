package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportDtos.LlmReportCreateRequest;
import com.gilbom.report.ReportGenerationMethod;
import com.gilbom.report.ReportType;
import com.gilbom.report.generation.ReportGenerationDataRepository.Snapshot;
import com.gilbom.report.generation.ReportGenerationDataService.PreparedCreation;

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
    private static final Instant PERIOD_FROM =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant PERIOD_TO =
            Instant.parse("2026-07-08T00:00:00Z");

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
    private ReportGenerationDataService service;

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
        assertThat(snapshot.typeCounts()).hasSize(4);
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


    // ------------------------------------------------- prepare()
    //
    // prepare()는 HTTP 요청을 서버가 조회한 스냅샷으로 바꾸는 지점이다. 여기서
    // 근거 결함·제목·통계 범위가 한꺼번에 정해지는데, 저장소가 실제 SQL이라
    // mock으로는 검증되지 않는다.

    @Test
    void rejectsLlmReportWithoutTargetDefect() {
        // 기간 전체를 근거로 하는 LLM 보고서는 제품에서 쓰지 않는다.
        // 목록의 "새 보고서" 버튼으로도 이 경로에 들어올 수 있어 서버에서 막는다.
        assertThatThrownBy(() -> service.prepare(ADMIN_ID, llmRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(cause ->
                        ((BusinessException) cause).errorCode())
                .isEqualTo(ErrorCode.REPORT_VALIDATION_FAILED);
    }

    @Test
    void rejectsDefectThatIsNotVisibleToTheActor() {
        // 없는 결함·접근 범위 밖 결함·기간 밖 결함이 모두 같은 응답이어야 한다.
        // 근거 0건인 보고서가 조용히 만들어지던 구멍이다.
        assertThatThrownBy(() ->
                service.prepare(ADMIN_ID, llmRequest(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(cause ->
                        ((BusinessException) cause).errorCode())
                .isEqualTo(ErrorCode.REPORT_VALIDATION_FAILED);
    }

    @Test
    void fixesEvidenceAndTitleToTheRequestedDefect() {
        PreparedCreation prepared =
                service.prepare(ADMIN_ID, llmRequest(DEFECT_ID));

        assertThat(prepared.pipelineRequest().templateTitle())
                .isEqualTo("INC-20260703-000001 보고서");
        assertThat(prepared.pipelineRequest().input().defectEvidence())
                .singleElement()
                .satisfies(evidence ->
                        assertThat(evidence.defectId()).isEqualTo(DEFECT_ID));
        // 감사 근거로 남는 스냅샷도 그 결함 하나여야 한다.
        assertThat(prepared.filterCriteria())
                .containsEntry("defectId", DEFECT_ID.toString());
    }

    @Test
    void keepsStatisticsAcrossTheWholePeriod() {
        // 단건 보고서라고 통계까지 그 결함 하나로 줄이면 "결함 수 1"이 되어
        // 의미가 없어진다. 근거만 좁히고 집계는 기간 전체로 둔다.
        Snapshot wholePeriod = repository.load(
                ADMIN_ID,
                PERIOD_FROM,
                PERIOD_TO,
                ReportGenerationCriteria.from(Map.of()));
        PreparedCreation prepared =
                service.prepare(ADMIN_ID, llmRequest(DEFECT_ID));

        assertThat(prepared.pipelineRequest().input().statistics()
                .aggregate().observationCount())
                .isEqualTo(wholePeriod.aggregate().observationCount());
        assertThat(prepared.pipelineRequest().input().statistics()
                .defectsByStatus())
                .hasSameSizeAs(wholePeriod.statusCounts());
    }

    private static LlmReportCreateRequest llmRequest(UUID defectId) {
        return new LlmReportCreateRequest(
                ReportGenerationMethod.LLM,
                ReportType.PERIODIC,
                PERIOD_FROM,
                PERIOD_TO,
                defectId == null
                        ? Map.of()
                        : Map.of("defectId", defectId.toString()));
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
            ReportGenerationDataService.class,
            PasswordHashingConfiguration.class
    })
    static class TestApplication {
    }
}
