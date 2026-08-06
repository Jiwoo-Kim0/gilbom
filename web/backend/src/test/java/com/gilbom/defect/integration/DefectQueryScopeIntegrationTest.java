package com.gilbom.defect.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.BestEffortAuditLogWriter;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.defect.DefectAccessService;
import com.gilbom.defect.DefectCommandService;
import com.gilbom.defect.DefectDtos.DefectStatusUpdateRequest;
import com.gilbom.defect.DefectFilter;
import com.gilbom.defect.DefectImageUrlIssuer;
import com.gilbom.defect.DefectQueryService;
import com.gilbom.defect.DefectRepository;
import com.gilbom.robot.application.RobotScopeService;

/**
 * 결함 API의 객체 범위가 SQL에서 실제로 적용되는지 검증한다.
 *
 * <p>단위 테스트는 조건절의 문법과 조립만 확인할 수 있다. "배정된 로봇에서 파생된
 * 결함만 보이는가"라는 의미는 실제 PostgreSQL에서만 확인되므로 여기서 검증한다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = DefectQueryScopeIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class DefectQueryScopeIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID ADMIN_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000031");
    private static final UUID VIEWER_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000032");
    private static final UUID OPERATOR_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000033");
    private static final UUID EXPIRED_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000034");

    private static final UUID ASSIGNED_ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2131");
    private static final UUID OUT_OF_SCOPE_ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2132");

    private static final UUID VISIBLE_DEFECT_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000001");
    private static final UUID HIDDEN_DEFECT_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000002");
    private static final UUID ORPHAN_DEFECT_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000003");
    private static final UUID MERGED_AWAY_DEFECT_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000004");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_defect_scope_test")
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
    private DefectQueryService queryService;

    @Autowired
    private DefectCommandService commandService;

    @BeforeEach
    void setUpScopeFixtures() {
        createUser(ADMIN_USER_ID, "DEFECT-ADMIN");
        createUser(VIEWER_USER_ID, "DEFECT-VIEWER");
        createUser(OPERATOR_USER_ID, "DEFECT-OPERATOR");
        createUser(EXPIRED_USER_ID, "DEFECT-EXPIRED");
        assignAdminRole(ADMIN_USER_ID);

        createRobot(ASSIGNED_ROBOT_ID, "RBT-031", "Assigned Robot");
        createRobot(OUT_OF_SCOPE_ROBOT_ID, "RBT-032", "Hidden Robot");
        createBootSession(ASSIGNED_ROBOT_ID);
        createBootSession(OUT_OF_SCOPE_ROBOT_ID);

        assignRobot(VIEWER_USER_ID, ASSIGNED_ROBOT_ID, "VIEWER");
        assignRobot(OPERATOR_USER_ID, ASSIGNED_ROBOT_ID, "OPERATOR");
        assignExpiredRobot(EXPIRED_USER_ID, ASSIGNED_ROBOT_ID);

        // 배정된 로봇이 관측한 결함 — VIEWER에게 보여야 한다.
        createDefect(
                VISIBLE_DEFECT_ID,
                "INC-20260729-000101",
                127.01,
                37.51);
        createObservation(VISIBLE_DEFECT_ID, ASSIGNED_ROBOT_ID, 1L, true);

        // 범위 밖 로봇이 관측한 결함 — VIEWER에게 보이지 않아야 한다.
        createDefect(
                HIDDEN_DEFECT_ID,
                "INC-20260729-000102",
                127.02,
                37.52);
        createObservation(HIDDEN_DEFECT_ID, OUT_OF_SCOPE_ROBOT_ID, 1L, true);

        // 관측이 아직 없는 결함 — 시스템 ADMIN에게는 보여야 한다.
        createDefect(
                ORPHAN_DEFECT_ID,
                "INC-20260729-000103",
                127.03,
                37.53);

        // 대표 결함(VISIBLE)에 병합된 결함. 목록에서 빠지고 관측은 대표로 합산된다.
        createDefect(
                MERGED_AWAY_DEFECT_ID,
                "INC-20260729-000104",
                127.011,
                37.511);
        createObservation(MERGED_AWAY_DEFECT_ID, ASSIGNED_ROBOT_ID, 2L, false);
        jdbcTemplate.update("""
                UPDATE defect.defect
                SET merged_into_defect_id = ?
                WHERE defect_id = ?
                """,
                VISIBLE_DEFECT_ID,
                MERGED_AWAY_DEFECT_ID);
    }

    @Test
    @Tag("security-acceptance")
    void appliesRobotDerivedScopeToListMapSummaryAndDetail() {
        var viewerList = queryService.findDefects(
                VIEWER_USER_ID, 0, 100, null, DefectFilter.empty());
        assertThat(viewerList.content())
                .extracting(item -> item.defectId())
                .containsExactly(VISIBLE_DEFECT_ID);
        assertThat(viewerList.totalElements()).isEqualTo(1);

        var viewerMap = queryService.findMapDefects(
                VIEWER_USER_ID, DefectFilter.empty());
        assertThat(viewerMap.features())
                .extracting(feature -> feature.properties().defectId())
                .containsExactly(VISIBLE_DEFECT_ID);

        var viewerSummary = queryService.summarize(
                VIEWER_USER_ID, DefectFilter.empty());
        assertThat(viewerSummary.totalDefects()).isEqualTo(1);

        assertThat(queryService.findDefect(VIEWER_USER_ID, VISIBLE_DEFECT_ID)
                .defect()
                .incidentCode())
                .isEqualTo("INC-20260729-000101");
    }

    @Test
    @Tag("security-acceptance")
    void hidesOutOfScopeDefectAsNotFound() {
        assertThatThrownBy(() -> queryService.findDefect(
                VIEWER_USER_ID, HIDDEN_DEFECT_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));

        // 하위 리소스도 같은 범위를 적용해야 한다.
        assertThatThrownBy(() -> queryService.findObservations(
                VIEWER_USER_ID, HIDDEN_DEFECT_ID, 0, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> queryService.findHistory(
                VIEWER_USER_ID, HIDDEN_DEFECT_ID, 0, 20))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * SYSTEM_ADMIN_SCOPE를 관측 EXISTS 밖에서 먼저 평가하는 이유를 검증한다.
     * 관측이 없는 결함은 로봇으로 파생될 수 없으므로, ADMIN 조건이 EXISTS 안에
     * 갇혀 있으면 ADMIN에게도 보이지 않는다.
     */
    @Test
    @Tag("security-acceptance")
    void systemAdminSeesEveryDefectIncludingOnesWithoutObservations() {
        var adminList = queryService.findDefects(
                ADMIN_USER_ID, 0, 100, null, DefectFilter.empty());

        assertThat(adminList.content())
                .extracting(item -> item.defectId())
                .containsExactlyInAnyOrder(
                        VISIBLE_DEFECT_ID,
                        HIDDEN_DEFECT_ID,
                        ORPHAN_DEFECT_ID);

        assertThat(queryService.findDefect(ADMIN_USER_ID, ORPHAN_DEFECT_ID)
                .defect()
                .incidentCode())
                .isEqualTo("INC-20260729-000103");

        // 반대로 배정 기반 사용자에게는 관측 없는 결함이 보이지 않는다.
        assertThatThrownBy(() -> queryService.findDefect(
                VIEWER_USER_ID, ORPHAN_DEFECT_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @Tag("security-acceptance")
    void expiredAssignmentGrantsNoAccess() {
        var expiredList = queryService.findDefects(
                EXPIRED_USER_ID, 0, 100, null, DefectFilter.empty());
        assertThat(expiredList.content()).isEmpty();
        assertThat(expiredList.totalElements()).isZero();

        assertThatThrownBy(() -> queryService.findDefect(
                EXPIRED_USER_ID, VISIBLE_DEFECT_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void excludesMergedAwayDefectFromCollectionsButKeepsItsObservations() {
        var adminList = queryService.findDefects(
                ADMIN_USER_ID, 0, 100, null, DefectFilter.empty());
        assertThat(adminList.content())
                .extracting(item -> item.defectId())
                .doesNotContain(MERGED_AWAY_DEFECT_ID);

        // 병합된 결함의 관측은 원본 행에 남고, 대표 결함에서 함께 조회된다.
        var observations = queryService.findObservations(
                ADMIN_USER_ID, VISIBLE_DEFECT_ID, 0, 20);
        assertThat(observations.totalElements()).isEqualTo(2);
        assertThat(observations.content())
                .extracting(item -> item.defectId())
                .containsExactlyInAnyOrder(
                        VISIBLE_DEFECT_ID,
                        MERGED_AWAY_DEFECT_ID);

        var detail = queryService.findDefect(
                ADMIN_USER_ID, VISIBLE_DEFECT_ID);
        assertThat(detail.mergedDefectIds())
                .containsExactly(MERGED_AWAY_DEFECT_ID);
    }

    @Test
    @Tag("security-acceptance")
    void requiresOperatorAssignmentToChangeWorkflowStatus() {
        DefectStatusUpdateRequest request = new DefectStatusUpdateRequest(
                "ACCEPTED",
                null,
                null,
                "현장 확인");

        // VIEWER 배정은 상태 변경 범위가 아니므로 존재 여부를 숨겨 404로 끝난다.
        assertThatThrownBy(() -> commandService.changeStatus(
                VIEWER_USER_ID,
                VISIBLE_DEFECT_ID,
                request,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));

        var updated = commandService.changeStatus(
                OPERATOR_USER_ID,
                VISIBLE_DEFECT_ID,
                request,
                AuditRequestContext.requestIdOnly(UUID.randomUUID()));
        assertThat(updated.defect().workflowStatus()).isEqualTo("ACCEPTED");

        // 상태 이력과 감사 로그가 같은 트랜잭션에서 남아야 한다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM defect.defect_status_history
                WHERE defect_id = ?
                  AND change_type = 'STATUS_CHANGED'
                  AND before_status = 'NEW'
                  AND after_status = 'ACCEPTED'
                """,
                Integer.class,
                VISIBLE_DEFECT_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE target_id = ?
                  AND action_type = 'DEFECT_STATUS_CHANGED'
                """,
                Integer.class,
                VISIBLE_DEFECT_ID)).isEqualTo(1);
    }

    @Test
    void filtersByRobotIdThroughObservations() {
        var assignedOnly = queryService.findDefects(
                ADMIN_USER_ID,
                0,
                100,
                null,
                DefectFilter.of(
                        null, null, null, null, null, null, null,
                        null, null, ASSIGNED_ROBOT_ID, null, null));
        assertThat(assignedOnly.content())
                .extracting(item -> item.defectId())
                .containsExactly(VISIBLE_DEFECT_ID);

        var hiddenRobotOnly = queryService.findDefects(
                ADMIN_USER_ID,
                0,
                100,
                null,
                DefectFilter.of(
                        null, null, null, null, null, null, null,
                        null, null, OUT_OF_SCOPE_ROBOT_ID, null, null));
        assertThat(hiddenRobotOnly.content())
                .extracting(item -> item.defectId())
                .containsExactly(HIDDEN_DEFECT_ID);
    }

    @Test
    void filtersByObservationPeriod() {
        // from·to는 Instant로 들어오는데 그대로 바인딩하면 드라이버가 SQL 타입을
        // 정하지 못해 조회 전체가 500으로 죽는다. 화면의 날짜 필터가 이 경로를 탄다.
        var withinPeriod = queryService.findDefects(
                ADMIN_USER_ID,
                0,
                100,
                null,
                DefectFilter.of(
                        null,
                        Instant.parse("2000-01-01T00:00:00Z"),
                        Instant.parse("2100-01-01T00:00:00Z"),
                        null, null, null, null,
                        null, null, null, null, null));
        assertThat(withinPeriod.content()).isNotEmpty();

        var startOnly = queryService.findDefects(
                ADMIN_USER_ID,
                0,
                100,
                null,
                DefectFilter.of(
                        null,
                        Instant.parse("2100-01-01T00:00:00Z"),
                        null,
                        null, null, null, null,
                        null, null, null, null, null));
        assertThat(startOnly.content()).isEmpty();

        // 지도 GeoJSON도 같은 필터를 쓴다. 목록만 고치면 지도가 계속 죽는다.
        var mapWithinPeriod = queryService.findMapDefects(
                ADMIN_USER_ID,
                DefectFilter.of(
                        null,
                        Instant.parse("2000-01-01T00:00:00Z"),
                        null,
                        null, null, null, null,
                        null, null, null, null, null));
        assertThat(mapWithinPeriod.features()).isNotEmpty();
    }

    // ------------------------------------------------------------ fixtures

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
                "Defect scope " + suffix,
                suffix.toLowerCase() + "@example.invalid");
    }

    private void assignAdminRole(UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_role (
                    user_id, role_id, is_primary, is_active, assigned_by
                )
                SELECT ?, role_id, TRUE, TRUE, ?
                FROM auth.role
                WHERE role_code = 'ADMIN'
                ON CONFLICT (user_id, role_id) DO NOTHING
                """,
                userId,
                userId);
    }

    private void createRobot(UUID robotId, String mqttId, String robotName) {
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

    /**
     * V23이 defect_observation에 붙인 (event_id, robot_id) 복합 FK 때문에
     * 관측을 넣기 전에 robot_event가 있어야 하고, robot_event는 boot session을
     * 요구한다.
     */
    private void createBootSession(UUID robotId) {
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_boot_session (
                    boot_id,
                    robot_id,
                    schema_version,
                    session_status,
                    first_received_at,
                    last_received_at
                ) VALUES (?, ?, '2.0', 'ONLINE', NOW(), NOW())
                ON CONFLICT (boot_id) DO NOTHING
                """,
                bootIdOf(robotId),
                robotId);
    }

    private void createRobotEvent(UUID eventId, UUID robotId, long seq) {
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
                    ?, '2.0', ?, ?, ?,
                    'BRAILLE_BLOCK_INCIDENT_DETECTED', 'ai',
                    NOW(), NOW(), '{}'::JSONB
                )
                ON CONFLICT (event_id) DO NOTHING
                """,
                eventId,
                robotId,
                bootIdOf(robotId),
                seq);
    }

    private void createDefect(
            UUID defectId,
            String incidentCode,
            double longitude,
            double latitude
    ) {
        jdbcTemplate.update("""
                INSERT INTO defect.defect (
                    defect_id,
                    incident_code,
                    block_type,
                    defect_type,
                    location,
                    zone_id,
                    first_seen_at,
                    last_seen_at
                ) VALUES (
                    ?, ?, 'LINEAR', 'DAMAGED',
                    ST_SetSRID(ST_MakePoint(?, ?), 4326),
                    'A-01', NOW(), NOW()
                )
                ON CONFLICT (defect_id) DO NOTHING
                """,
                defectId,
                incidentCode,
                longitude,
                latitude);
    }

    private void createObservation(
            UUID defectId,
            UUID robotId,
            long seq,
            boolean representative
    ) {
        UUID eventId = UUID.nameUUIDFromBytes(
                (defectId + "|" + robotId + "|" + seq).getBytes());
        createRobotEvent(eventId, robotId, seq);
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
                    is_representative
                ) VALUES (
                    ?, ?, ?, TRUE,
                    ST_SetSRID(ST_MakePoint(127.01, 37.51), 4326),
                    'A-01', 'BRAILLE_BLOCK', 'LINEAR', 'DAMAGED',
                    0.870, 'tbs-braille-yolo', '1.0.0', ?
                )
                ON CONFLICT (event_id) DO NOTHING
                """,
                eventId,
                robotId,
                defectId,
                representative);
    }

    private void assignRobot(
            UUID userId,
            UUID robotId,
            String assignmentType
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id, robot_id, assignment_type, reason, assigned_by
                ) VALUES (?, ?, ?, 'defect scope integration test', ?)
                ON CONFLICT (user_id, robot_id)
                    WHERE is_active = TRUE
                    DO NOTHING
                """,
                userId,
                robotId,
                assignmentType,
                ADMIN_USER_ID);
    }

    private void assignExpiredRobot(UUID userId, UUID robotId) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id,
                    robot_id,
                    assignment_type,
                    reason,
                    assigned_by,
                    assigned_at,
                    expires_at,
                    updated_at
                ) VALUES (
                    ?, ?, 'OWNER',
                    'expired defect scope integration test',
                    ?,
                    NOW() - INTERVAL '2 days',
                    NOW() - INTERVAL '1 day',
                    NOW()
                )
                ON CONFLICT (user_id, robot_id)
                    WHERE is_active = TRUE
                    DO NOTHING
                """,
                userId,
                robotId,
                ADMIN_USER_ID);
    }

    private static UUID bootIdOf(UUID robotId) {
        return UUID.nameUUIDFromBytes(("boot|" + robotId).getBytes());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            DefectQueryService.class,
            DefectCommandService.class,
            DefectAccessService.class,
            DefectRepository.class,
            RobotScopeService.class,
            AuditLogService.class,
            AuditLogRepository.class,
            BestEffortAuditLogWriter.class
    })
    static class TestApplication {

        /**
         * 운영 기본 구현은 package-private이므로 테스트에서 동등한 빈을 제공한다.
         * 이 테스트는 이미지 URL 발급을 검증하지 않는다.
         */
        @Bean
        DefectImageUrlIssuer defectImageUrlIssuer() {
            return imageId -> Optional.empty();
        }
    }
}
