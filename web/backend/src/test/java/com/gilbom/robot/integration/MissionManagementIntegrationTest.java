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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaCreateRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaLifecycleStatus;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaResponse;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaStatusChangeRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolAreaUpdateRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteCreateLifecycleStatus;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteCreateRequest;
import com.gilbom.robot.data.api.response.PatrolCatalogDtos.PatrolRouteResponse;
import com.gilbom.robot.mission.MissionManagementService;
import com.gilbom.robot.mission.MissionQueryService;
import com.gilbom.robot.mission.PatrolAreaManagementService;
import com.gilbom.robot.mission.PatrolCatalogQueryService;
import com.gilbom.robot.mission.PatrolRouteManagementService;

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

    @Autowired
    private PatrolAreaManagementService patrolAreaManagementService;

    @Autowired
    private PatrolCatalogQueryService patrolCatalogQueryService;

    @Autowired
    private PatrolRouteManagementService patrolRouteManagementService;

    @Autowired
    private ObjectMapper objectMapper;

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
    void createsQueryablePatrolAreaAndWritesAudit() throws Exception {
        UUID requestId = UUID.randomUUID();
        String areaCode = "AREA-CREATE-"
                + UUID.randomUUID().toString().substring(0, 8);

        PatrolAreaResponse created = patrolAreaManagementService.create(
                new PatrolAreaCreateRequest(
                        areaCode,
                        "등록 통합 테스트 구역",
                        objectMapper.readTree("""
                                {
                                  "type": "MultiPolygon",
                                  "coordinates": [
                                    [
                                      [
                                        [127.00, 37.00],
                                        [127.02, 37.00],
                                        [127.02, 37.02],
                                        [127.00, 37.02],
                                        [127.00, 37.00]
                                      ]
                                    ]
                                  ]
                                }
                                """),
                        null),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(requestId));

        assertThat(created.areaCode()).isEqualTo(areaCode);
        assertThat(created.areaName()).isEqualTo("등록 통합 테스트 구역");
        assertThat(created.lifecycleStatus()).isEqualTo("ACTIVE");
        assertThat(created.boundary().path("type").asText())
                .isEqualTo("MultiPolygon");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT
                    GeometryType(boundary) = 'MULTIPOLYGON'
                    AND ST_SRID(boundary) = 4326
                    AND ST_IsValid(boundary)
                FROM robot.patrol_area
                WHERE patrol_area_id = ?
                """, Boolean.class, created.patrolAreaId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND actor_user_id = ?
                  AND action_type = 'PATROL_AREA_CREATED'
                  AND target_type = 'PATROL_AREA'
                  AND target_id = ?
                  AND after_value ->> 'areaCode' = ?
                  AND result = 'SUCCESS'
                """,
                Integer.class,
                requestId,
                OWNER_ID,
                created.patrolAreaId(),
                areaCode))
                .isEqualTo(1);
    }

    @Test
    void rejectsDuplicateCodeAndInvalidBoundary() throws Exception {
        String areaCode = "AREA-DUP-"
                + UUID.randomUUID().toString().substring(0, 8);
        PatrolAreaCreateRequest valid = new PatrolAreaCreateRequest(
                areaCode,
                "중복 검사 구역",
                objectMapper.readTree("""
                        {
                          "type": "Polygon",
                          "coordinates": [
                            [
                              [127.00, 37.00],
                              [127.02, 37.00],
                              [127.02, 37.02],
                              [127.00, 37.00]
                            ]
                          ]
                        }
                        """),
                null);
        patrolAreaManagementService.create(
                valid,
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID()));

        assertThatThrownBy(() -> patrolAreaManagementService.create(
                valid,
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.PATROL_AREA_CODE_CONFLICT));

        PatrolAreaCreateRequest selfIntersecting =
                new PatrolAreaCreateRequest(
                        "AREA-INVALID-"
                                + UUID.randomUUID()
                                        .toString()
                                        .substring(0, 8),
                        "자가 교차 구역",
                        objectMapper.readTree("""
                                {
                                  "type": "Polygon",
                                  "coordinates": [
                                    [
                                      [127.00, 37.00],
                                      [127.02, 37.02],
                                      [127.02, 37.00],
                                      [127.00, 37.02],
                                      [127.00, 37.00]
                                    ]
                                  ]
                                }
                                """),
                        null);

        assertThatThrownBy(() -> patrolAreaManagementService.create(
                selfIntersecting,
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.PATROL_AREA_GEOMETRY_INVALID));
    }

    @Test
    void updatesPatrolAreaGeometryAndWritesBeforeAfterAudit()
            throws Exception {
        UUID requestId = UUID.randomUUID();
        PatrolAreaResponse updated = patrolAreaManagementService.update(
                areaId,
                new PatrolAreaUpdateRequest(
                        " 변경된 통합 테스트 구역 ",
                        objectMapper.readTree("""
                                {
                                  "type": "Polygon",
                                  "coordinates": [
                                    [
                                      [126.99, 36.99],
                                      [127.03, 36.99],
                                      [127.03, 37.03],
                                      [126.99, 37.03],
                                      [126.99, 36.99]
                                    ]
                                  ]
                                }
                                """)),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(requestId));

        assertThat(updated.areaName())
                .isEqualTo("변경된 통합 테스트 구역");
        assertThat(updated.lifecycleStatus()).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT
                    ST_SRID(boundary) = 4326
                    AND ST_Covers(
                        boundary,
                        ST_GeomFromText(
                            'LINESTRING('
                            || '127.001 37.001,'
                            || '127.019 37.019)',
                            4326
                        )
                    )
                FROM robot.patrol_area
                WHERE patrol_area_id = ?
                """, Boolean.class, areaId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type = 'PATROL_AREA_UPDATED'
                  AND target_type = 'PATROL_AREA'
                  AND target_id = ?
                  AND before_value ->> 'areaName' = 'Mission Area'
                  AND after_value ->> 'areaName' =
                      '변경된 통합 테스트 구역'
                  AND before_value ->> 'lifecycleStatus' = 'ACTIVE'
                  AND after_value ->> 'lifecycleStatus' = 'ACTIVE'
                  AND result = 'SUCCESS'
                """,
                Integer.class,
                requestId,
                areaId)).isOne();
    }

    @Test
    void changesPatrolAreaStateOneWayAndRemembersPreviousState() {
        UUID inactiveRequestId = UUID.randomUUID();
        PatrolAreaResponse inactive =
                patrolAreaManagementService.changeStatus(
                        areaId,
                        new PatrolAreaStatusChangeRequest(
                                PatrolAreaLifecycleStatus.INACTIVE),
                        OWNER_ID,
                        AuditRequestContext.requestIdOnly(
                                inactiveRequestId));
        PatrolAreaResponse archived =
                patrolAreaManagementService.changeStatus(
                        areaId,
                        new PatrolAreaStatusChangeRequest(
                                PatrolAreaLifecycleStatus.ARCHIVED),
                        OWNER_ID,
                        AuditRequestContext.requestIdOnly(
                                UUID.randomUUID()));

        assertThat(inactive.lifecycleStatus()).isEqualTo("INACTIVE");
        assertThat(archived.lifecycleStatus()).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type = 'PATROL_AREA_UPDATED'
                  AND before_value ->> 'lifecycleStatus' = 'ACTIVE'
                  AND after_value ->> 'lifecycleStatus' = 'INACTIVE'
                """,
                Integer.class,
                inactiveRequestId)).isOne();

        assertThatThrownBy(() ->
                patrolAreaManagementService.changeStatus(
                        areaId,
                        new PatrolAreaStatusChangeRequest(
                                PatrolAreaLifecycleStatus.ACTIVE),
                        OWNER_ID,
                        AuditRequestContext.requestIdOnly(
                                UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.PATROL_AREA_STATE_CONFLICT));
    }

    @Test
    void rejectsPatrolAreaMutationWhileMissionUsesArea() {
        managementService.create(
                new MissionCreateRequest(
                        robotId,
                        areaId,
                        routeId,
                        Instant.now().plusSeconds(3600)),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID()));

        assertThatThrownBy(() -> patrolAreaManagementService.update(
                areaId,
                new PatrolAreaUpdateRequest(
                        "사용 중 변경 금지",
                        null),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.PATROL_AREA_IN_USE));
        assertThatThrownBy(() ->
                patrolAreaManagementService.changeStatus(
                        areaId,
                        new PatrolAreaStatusChangeRequest(
                                PatrolAreaLifecycleStatus.INACTIVE),
                        OWNER_ID,
                        AuditRequestContext.requestIdOnly(
                                UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.PATROL_AREA_IN_USE));
        assertThat(patrolCatalogQueryService.findArea(areaId).areaName())
                .isEqualTo("Mission Area");
        assertThat(patrolCatalogQueryService.findArea(areaId)
                .lifecycleStatus())
                .isEqualTo("ACTIVE");
    }

    @Test
    void rejectsInvalidOrRouteExcludingPatrolAreaBoundary()
            throws Exception {
        PatrolAreaUpdateRequest invalid =
                new PatrolAreaUpdateRequest(
                        null,
                        objectMapper.readTree("""
                                {
                                  "type": "Polygon",
                                  "coordinates": [
                                    [
                                      [127.00, 37.00],
                                      [127.02, 37.02],
                                      [127.02, 37.00],
                                      [127.00, 37.02],
                                      [127.00, 37.00]
                                    ]
                                  ]
                                }
                                """));
        assertThatThrownBy(() -> patrolAreaManagementService.update(
                areaId,
                invalid,
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.PATROL_AREA_GEOMETRY_INVALID));

        PatrolAreaUpdateRequest excludesRoute =
                new PatrolAreaUpdateRequest(
                        null,
                        objectMapper.readTree("""
                                {
                                  "type": "Polygon",
                                  "coordinates": [
                                    [
                                      [127.00, 37.00],
                                      [127.01, 37.00],
                                      [127.01, 37.01],
                                      [127.00, 37.01],
                                      [127.00, 37.00]
                                    ]
                                  ]
                                }
                                """));
        assertThatThrownBy(() -> patrolAreaManagementService.update(
                areaId,
                excludesRoute,
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode
                                                .PATROL_AREA_ROUTE_OUTSIDE_BOUNDARY));
    }

    @Test
    void auditFailureRollsBackPatrolAreaUpdate() {
        jdbcTemplate.execute("""
                CREATE FUNCTION audit.reject_patrol_area_update_test()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.action_type = 'PATROL_AREA_UPDATED'
                    THEN
                        RAISE EXCEPTION 'forced patrol area audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tr_reject_patrol_area_update_test
                BEFORE INSERT ON audit.audit_log
                FOR EACH ROW
                EXECUTE FUNCTION audit.reject_patrol_area_update_test()
                """);
        try {
            assertThatThrownBy(() -> patrolAreaManagementService.update(
                    areaId,
                    new PatrolAreaUpdateRequest(
                            "Rollback 대상 이름",
                            null),
                    OWNER_ID,
                    AuditRequestContext.requestIdOnly(
                            UUID.randomUUID())))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER tr_reject_patrol_area_update_test
                    ON audit.audit_log
                    """);
            jdbcTemplate.execute("""
                    DROP FUNCTION audit.reject_patrol_area_update_test()
                    """);
        }

        assertThat(patrolCatalogQueryService.findArea(areaId).areaName())
                .isEqualTo("Mission Area");
    }

    @Test
    void createsActiveRouteVersionsAndUsesLatestVersionForMission()
            throws Exception {
        String routeCode = "ROUTE-VERSION-"
                + UUID.randomUUID().toString().substring(0, 8);
        UUID firstRequestId = UUID.randomUUID();
        PatrolRouteResponse first = patrolRouteManagementService.create(
                areaId,
                new PatrolRouteCreateRequest(
                        routeCode,
                        "통합 테스트 경로 v1",
                        objectMapper.readTree("""
                                {
                                  "type": "LineString",
                                  "coordinates": [
                                    [127.001, 37.001],
                                    [127.019, 37.019]
                                  ]
                                }
                                """),
                        null),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(firstRequestId));

        UUID secondRequestId = UUID.randomUUID();
        PatrolRouteResponse second = patrolRouteManagementService.create(
                areaId,
                new PatrolRouteCreateRequest(
                        routeCode,
                        "통합 테스트 경로 v2",
                        objectMapper.readTree("""
                                {
                                  "type": "MultiLineString",
                                  "coordinates": [
                                    [
                                      [127.001, 37.001],
                                      [127.010, 37.010]
                                    ],
                                    [
                                      [127.010, 37.010],
                                      [127.019, 37.019]
                                    ]
                                  ]
                                }
                                """),
                        null),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(secondRequestId));

        assertThat(first.routeVersion()).isEqualTo(1);
        assertThat(second.routeVersion()).isEqualTo(2);
        assertThat(second.lifecycleStatus()).isEqualTo("ACTIVE");
        assertThat(second.routeGeometry().path("type").asText())
                .isEqualTo("MultiLineString");
        assertThat(queryService.findMission(
                OWNER_ID,
                managementService.create(
                        new MissionCreateRequest(
                                robotId,
                                areaId,
                                second.patrolRouteId(),
                                Instant.now().plusSeconds(3600)),
                        OWNER_ID,
                        AuditRequestContext.requestIdOnly(
                                UUID.randomUUID()))
                        .missionId())
                .patrolRouteId())
                .isEqualTo(second.patrolRouteId());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status
                FROM robot.patrol_route
                WHERE patrol_route_id = ?
                """, String.class, first.patrolRouteId()))
                .isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT route_name
                FROM robot.patrol_route
                WHERE patrol_route_id = ?
                """, String.class, first.patrolRouteId()))
                .isEqualTo("통합 테스트 경로 v1");
        assertThat(objectMapper.readTree(
                jdbcTemplate.queryForObject("""
                        SELECT ST_AsGeoJSON(route_geometry)
                        FROM robot.patrol_route
                        WHERE patrol_route_id = ?
                        """,
                        String.class,
                        first.patrolRouteId()))
                .path("type").asText())
                .isEqualTo("LineString");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.patrol_route
                WHERE patrol_area_id = ?
                  AND route_code = ?
                  AND lifecycle_status = 'ACTIVE'
                """, Integer.class, areaId, routeCode))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type = 'PATROL_ROUTE_CREATED'
                  AND target_type = 'PATROL_ROUTE'
                  AND target_id = ?
                  AND after_value ->> 'supersededRouteId' = ?
                  AND result = 'SUCCESS'
                """,
                Integer.class,
                secondRequestId,
                second.patrolRouteId(),
                first.patrolRouteId().toString()))
                .isEqualTo(1);
    }

    @Test
    void keepsDraftRouteVersionWhenPublishingActiveVersion()
            throws Exception {
        String routeCode = "ROUTE-DRAFT-"
                + UUID.randomUUID().toString().substring(0, 8);
        PatrolRouteResponse draft = patrolRouteManagementService.create(
                areaId,
                new PatrolRouteCreateRequest(
                        routeCode,
                        "검토 중 경로",
                        objectMapper.readTree("""
                                {
                                  "type": "LineString",
                                  "coordinates": [
                                    [127.001, 37.001],
                                    [127.019, 37.019]
                                  ]
                                }
                                """),
                        PatrolRouteCreateLifecycleStatus.DRAFT),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID()));

        PatrolRouteResponse active = patrolRouteManagementService.create(
                areaId,
                new PatrolRouteCreateRequest(
                        routeCode,
                        "배포 경로",
                        objectMapper.readTree("""
                                {
                                  "type": "LineString",
                                  "coordinates": [
                                    [127.002, 37.002],
                                    [127.018, 37.018]
                                  ]
                                }
                                """),
                        PatrolRouteCreateLifecycleStatus.ACTIVE),
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID()));

        assertThat(draft.routeVersion()).isEqualTo(1);
        assertThat(active.routeVersion()).isEqualTo(2);
        assertThat(active.lifecycleStatus()).isEqualTo("ACTIVE");
        assertThat(patrolCatalogQueryService.findRoute(
                areaId,
                draft.patrolRouteId()))
                .satisfies(storedDraft -> {
                    assertThat(storedDraft.routeName())
                            .isEqualTo("검토 중 경로");
                    assertThat(storedDraft.lifecycleStatus())
                            .isEqualTo("DRAFT");
                    assertThat(storedDraft.routeGeometry()
                            .path("coordinates").get(0).get(0)
                            .asDouble())
                            .isEqualTo(127.001);
                });
    }

    @Test
    void rejectsInvalidAndOutsideAreaRoutes() throws Exception {
        PatrolRouteCreateRequest invalid =
                new PatrolRouteCreateRequest(
                        "ROUTE-INVALID-"
                                + UUID.randomUUID()
                                        .toString()
                                        .substring(0, 8),
                        "점 하나 경로",
                        objectMapper.readTree("""
                                {
                                  "type": "LineString",
                                  "coordinates": [
                                    [127.001, 37.001]
                                  ]
                                }
                                """),
                        null);
        assertThatThrownBy(() -> patrolRouteManagementService.create(
                areaId,
                invalid,
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.PATROL_ROUTE_GEOMETRY_INVALID));

        PatrolRouteCreateRequest outside =
                new PatrolRouteCreateRequest(
                        "ROUTE-OUTSIDE-"
                                + UUID.randomUUID()
                                        .toString()
                                        .substring(0, 8),
                        "구역 밖 경로",
                        objectMapper.readTree("""
                                {
                                  "type": "LineString",
                                  "coordinates": [
                                    [128.001, 38.001],
                                    [128.019, 38.019]
                                  ]
                                }
                                """),
                        null);
        assertThatThrownBy(() -> patrolRouteManagementService.create(
                areaId,
                outside,
                OWNER_ID,
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.PATROL_ROUTE_OUTSIDE_AREA));
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
            MissionManagementService.class,
            PatrolCatalogQueryService.class,
            PatrolAreaManagementService.class,
            PatrolRouteManagementService.class
    })
    static class TestApplication {
    }
}
