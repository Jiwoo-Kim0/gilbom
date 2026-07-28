package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.BestEffortAuditLogWriter;
import com.gilbom.auth.session.AuthenticationSessionConfiguration;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.api.response.MissionDtos.MissionCreateRequest;
import com.gilbom.robot.data.api.response.MissionDtos.MissionResponse;
import com.gilbom.robot.data.api.response.MissionDtos.MissionStatus;
import com.gilbom.robot.data.api.response.MissionDtos.MissionUpdateRequest;
import com.gilbom.robot.mission.MissionManagementService;
import com.gilbom.robot.mission.MissionQueryService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = MissionManagementIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class MissionManagementIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID OWNER_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_mission_management_test")
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
    private MissionManagementService managementService;

    @Autowired
    private MissionQueryService queryService;

    private UUID robotId;
    private UUID areaId;
    private UUID routeId;

    @BeforeEach
    void createFixture() {
        robotId = UUID.randomUUID();
        areaId = UUID.randomUUID();
        routeId = UUID.randomUUID();
        createUser(OWNER_ID, "MISSION-OWNER");
        createUser(OTHER_USER_ID, "MISSION-OTHER");
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (?, ?, 'Mission Robot', ?, 'ACTIVE', ?)
                """,
                robotId,
                "RBT-" + robotId.toString().substring(0, 8),
                "SN-" + robotId,
                OWNER_ID);
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id,
                    robot_id,
                    assignment_type,
                    reason,
                    assigned_by
                ) VALUES (?, ?, 'OWNER', 'mission test', ?)
                """,
                OWNER_ID,
                robotId,
                OWNER_ID);
        jdbcTemplate.update("""
                INSERT INTO robot.patrol_area (
                    patrol_area_id,
                    area_code,
                    area_name,
                    boundary,
                    lifecycle_status,
                    created_by,
                    updated_by
                ) VALUES (
                    ?,
                    ?,
                    'Mission Area',
                    ST_GeomFromText(
                        'POLYGON(('
                        || '127.00 37.00,'
                        || '127.02 37.00,'
                        || '127.02 37.02,'
                        || '127.00 37.02,'
                        || '127.00 37.00))',
                        4326
                    ),
                    'ACTIVE',
                    ?,
                    ?
                )
                """,
                areaId,
                "AREA-" + areaId.toString().substring(0, 8),
                OWNER_ID,
                OWNER_ID);
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
                    updated_by
                ) VALUES (
                    ?,
                    ?,
                    ?,
                    'Mission Route',
                    1,
                    ST_GeomFromText(
                        'LINESTRING(127.001 37.001,127.019 37.019)',
                        4326
                    ),
                    'ACTIVE',
                    ?,
                    ?
                )
                """,
                routeId,
                areaId,
                "ROUTE-" + routeId.toString().substring(0, 8),
                OWNER_ID,
                OWNER_ID);
    }

    @Test
    void createsUpdatesQueriesHistoriesAndAuditsMission() {
        UUID createRequestId = UUID.randomUUID();
        MissionResponse created = managementService.create(
                new MissionCreateRequest(
                        robotId,
                        areaId,
                        routeId,
                        Instant.now().plusSeconds(3600)),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(createRequestId));

        MissionResponse ready = managementService.update(
                created.missionId(),
                new MissionUpdateRequest(
                        null,
                        null,
                        null,
                        MissionStatus.READY,
                        null),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID()));

        assertThat(created.missionCode()).startsWith("MIS-");
        assertThat(created.status()).isEqualTo(MissionStatus.CREATED);
        assertThat(created.patrolRouteId()).isEqualTo(routeId);
        assertThat(ready.status()).isEqualTo(MissionStatus.READY);
        assertThat(queryService.findMissions(
                OWNER_ID,
                0,
                20,
                robotId,
                areaId,
                routeId,
                MissionStatus.READY,
                null,
                null).content())
                .extracting(MissionResponse::missionId)
                .containsExactly(created.missionId());
        assertThat(queryService.findHistory(
                OWNER_ID,
                created.missionId()).history())
                .extracting(item -> item.afterStatus())
                .containsExactly(
                        MissionStatus.CREATED,
                        MissionStatus.READY);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE target_id = ?
                  AND action_type IN ('MISSION_CREATED', 'MISSION_UPDATED')
                """,
                Integer.class,
                created.missionId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type = 'MISSION_CREATED'
                """,
                Integer.class,
                createRequestId)).isOne();

        Instant startedAt = created.createdAt().plusSeconds(1);
        jdbcTemplate.update("""
                UPDATE robot.mission
                SET status = 'RUNNING',
                    started_at = ?,
                    progress_percent = 50,
                    updated_at = ?
                WHERE mission_id = ?
                """,
                java.time.OffsetDateTime.ofInstant(
                        startedAt,
                        java.time.ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(
                        startedAt.plusSeconds(600),
                        java.time.ZoneOffset.UTC),
                created.missionId());
        assertThat(queryService.findMission(
                OWNER_ID,
                created.missionId()).estimatedCompletionAt())
                .isEqualTo(startedAt.plusSeconds(1200));

        assertThatThrownBy(() -> queryService.findMission(
                OTHER_USER_ID,
                created.missionId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.MISSION_NOT_FOUND));
    }

    @Test
    void rejectsRouteThatDoesNotBelongToArea() {
        UUID otherAreaId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO robot.patrol_area (
                    patrol_area_id,
                    area_code,
                    area_name,
                    boundary,
                    lifecycle_status,
                    created_by,
                    updated_by
                ) VALUES (
                    ?,
                    ?,
                    'Other Area',
                    ST_MakeEnvelope(128, 38, 128.01, 38.01, 4326),
                    'ACTIVE',
                    ?,
                    ?
                )
                """,
                otherAreaId,
                "AREA-" + otherAreaId.toString().substring(0, 8),
                OWNER_ID,
                OWNER_ID);

        assertThatThrownBy(() -> managementService.create(
                new MissionCreateRequest(
                        robotId,
                        otherAreaId,
                        routeId,
                        null),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.PATROL_ROUTE_NOT_FOUND));
    }

    private void createUser(UUID userId, String userCode) {
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
                    ?,
                    '{argon2}$argon2id$mission-integration',
                    ?,
                    ?,
                    'ACTIVE',
                    TRUE,
                    FALSE,
                    NOW(),
                    'ADMIN_CREATED'
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                userId,
                userCode,
                userCode,
                userCode.toLowerCase() + "@example.test");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AuthenticationSessionConfiguration.class,
            AuditLogRepository.class,
            AuditLogService.class,
            BestEffortAuditLogWriter.class,
            MissionQueryService.class,
            MissionManagementService.class
    })
    static class TestApplication {
    }
}
