package com.gilbom.storage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.defect.DefectReportImageService;
import com.gilbom.defect.DefectReportImageService.DefectReportImage;
import com.gilbom.defect.IncidentEventIngestionService;
import com.gilbom.defect.IncidentEventIngestionService.IncidentIngestionResult;
import com.gilbom.robot.data.mqtt.v2.common.EventSource;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.event.RobotEventIngestionService;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService;
import com.gilbom.storage.ImageBytesReader;
import com.gilbom.storage.ImageUploadService;
import com.gilbom.storage.LocalFileObjectStorageClient;
import com.gilbom.storage.ObjectStorageRegistry;
import com.gilbom.storage.RobotImageRepository;
import com.gilbom.storage.StorageProperties;
import com.gilbom.storage.StoredEvidenceImageLoader;
import com.gilbom.storage.auth.RobotApiKeyPrincipal;
import com.gilbom.storage.auth.RobotApiKeyRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 업로드한 이미지가 Incident Event를 통해 결함 관측에 연결되는지 검증한다.
 *
 * <p>업로드 API와 Incident 수집은 서로 다른 경로이고, 이어주는 것은
 * {@code storage.robot_image.event_id} 하나뿐이다. 로봇은 이미지를 먼저 HTTPS로
 * 올리고 그 다음 MQTT로 Incident를 발행하므로, 두 요청의 {@code event_id}가 어긋나면
 * 이미지가 안 붙는 정도가 아니라 <b>Incident 자체가 409로 거부되어 결함이 생성되지
 * 않는다.</b> 그 결합을 여기서 고정한다.
 *
 * <p>LLM 보고서가 쓰는 {@link DefectReportImageService}도 같은 데이터 위에서 함께
 * 검증한다. 대표 결함 선정과 바이트 적재는 실제 결함·관측·이미지가 모두 있어야
 * 확인할 수 있다.
 */
@Testcontainers(disabledWithoutDocker = true)
class IncidentImageLinkIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID ADMIN_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_ROBOT_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000007");
    private static final UUID AREA_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000003");
    private static final UUID ROUTE_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000004");
    private static final UUID MISSION_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000005");
    private static final UUID BOOT_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000006");
    private static final UUID API_KEY_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000008");

    private static final Instant BASE_TIME =
            Instant.parse("2026-07-28T01:00:00Z");

    @TempDir
    static Path storageRoot;

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_incident_image_link_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static JdbcTemplate jdbcTemplate;
    private static ImageUploadService uploadService;
    private static IncidentEventIngestionService incidentIngestionService;
    private static DefectReportImageService reportImageService;

    @BeforeAll
    static void migrateAndWireServices() {
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
        incidentIngestionService = new IncidentEventIngestionService(
                jdbcTemplate,
                new RobotEventIngestionService(
                        jdbcTemplate,
                        new MqttReplayProtectionService(
                                jdbcTemplate,
                                new SimpleMeterRegistry())),
                objectMapper);

        StorageProperties properties = new StorageProperties();
        properties.setLocalRootDirectory(storageRoot.toString());
        ObjectStorageRegistry registry = new ObjectStorageRegistry(
                List.of(new LocalFileObjectStorageClient(properties)));
        RobotImageRepository imageRepository =
                new RobotImageRepository(jdbcTemplate);
        uploadService = new ImageUploadService(
                imageRepository,
                new RobotApiKeyRepository(jdbcTemplate),
                registry);
        reportImageService = new DefectReportImageService(
                new NamedParameterJdbcTemplate(jdbcTemplate),
                new StoredEvidenceImageLoader(
                        new ImageBytesReader(imageRepository, registry)));
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
                    storage.robot_image,
                    storage.robot_image_api_key,
                    robot.mission_status_history,
                    telemetry.robot_event,
                    telemetry.robot_telemetry,
                    robot.mission,
                    robot.patrol_route,
                    robot.patrol_area,
                    telemetry.robot_status_history,
                    telemetry.robot_current_state,
                    telemetry.robot_boot_session,
                    robot.robot
                CASCADE
                """);
        createAdmin();
        createRobot(ROBOT_ID, "RBT-IMAGE-001", "SN-IMAGE-001");
        createRobot(OTHER_ROBOT_ID, "RBT-IMAGE-002", "SN-IMAGE-002");
        createPatrolAndMission();
    }

    // ------------------------------------------------ 이미지 ↔ Incident 연결

    @Test
    void linksAnUploadedImageToTheDefectObservation() {
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000101");
        UUID imageId = upload(eventId, jpeg(320, 240));

        IncidentIngestionResult result = ingest(incident(eventId, 1, imageId));

        assertThat(result.observationInserted()).isTrue();
        assertThat(observationImageId(eventId)).isEqualTo(imageId);
    }

    @Test
    void rejectsAnIncidentWhoseEventIdDiffersFromTheUploadedImage() {
        UUID uploadEventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000102");
        UUID incidentEventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000103");
        UUID imageId = upload(uploadEventId, jpeg(64, 64));

        // 로봇이 업로드와 Incident에 서로 다른 event_id를 쓴 경우다. 이미지가 다른
        // 사건에 붙는 것을 막으려고 Incident 자체를 거부한다.
        assertThatThrownBy(() -> ingest(incident(incidentEventId, 1, imageId)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.MQTT_EVENT_CONFLICT));
    }

    @Test
    void createsTheDefectWithoutAnImageWhenTheImageIdIsUnknown() {
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000104");

        // 업로드가 실패하면 로봇은 image_id를 null로 두고 Incident를 그대로 발행한다
        // (uploader.py 계약 8.3). 알 수 없는 id를 보내는 경우도 같게 다룬다.
        IncidentIngestionResult result = ingest(incident(
                eventId,
                1,
                UUID.fromString("24000000-0000-4000-8000-0000000001ff")));

        assertThat(result.defectId()).isNotNull();
        assertThat(observationImageId(eventId)).isNull();
    }

    @Test
    void doesNotLinkAnImageThatBelongsToAnotherRobot() {
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000105");
        UUID otherRobotsImage = upload(
                OTHER_ROBOT_ID,
                eventId,
                jpeg(32, 32));

        IncidentIngestionResult result =
                ingest(incident(eventId, 1, otherRobotsImage));

        // 결함은 만들되 남의 로봇 이미지를 붙이지는 않는다.
        assertThat(result.defectId()).isNotNull();
        assertThat(observationImageId(eventId)).isNull();
    }

    // ------------------------------------------------ LLM 보고서용 이미지 조회

    @Test
    void suppliesTheRepresentativeImageBytesForReportGeneration() {
        assignAdminRole(ADMIN_ID);
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000201");
        byte[] content = jpeg(320, 240);
        UUID imageId = upload(eventId, content);
        ingest(incident(eventId, 1, imageId));

        List<DefectReportImage> images = reportImageService.findReportImages(
                ADMIN_ID,
                BASE_TIME,
                BASE_TIME.plusSeconds(3600),
                1);

        assertThat(images).hasSize(1);
        DefectReportImage image = images.getFirst();
        assertThat(image.content()).isEqualTo(content);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(image.width()).isEqualTo(320);
        assertThat(image.height()).isEqualTo(240);
        // 여러 장을 보낼 때 모델이 어느 결함인지 알 수 있어야 한다.
        assertThat(image.incidentCode()).isNotBlank();
        assertThat(image.defectId()).isNotNull();
    }

    @Test
    void returnsNoImagesWhenTheDefectHasNone() {
        assignAdminRole(ADMIN_ID);
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000202");
        ingest(incident(eventId, 1, null));

        // 이미지가 없어도 예외가 아니라 빈 리스트다. 보고서는 텍스트만으로 생성된다.
        assertThat(reportImageService.findReportImages(
                ADMIN_ID,
                BASE_TIME,
                BASE_TIME.plusSeconds(3600),
                1))
                .isEmpty();
    }

    @Test
    void skipsImagesWhoseObjectIsMissingFromStorage() {
        assignAdminRole(ADMIN_ID);
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000203");
        UUID imageId = upload(eventId, jpeg(48, 48));
        ingest(incident(eventId, 1, imageId));
        deleteStoredObject(imageId);

        // 저장소에서 파일이 사라져도 보고서 생성이 실패하면 안 된다.
        assertThat(reportImageService.findReportImages(
                ADMIN_ID,
                BASE_TIME,
                BASE_TIME.plusSeconds(3600),
                1))
                .isEmpty();
    }

    @Test
    void hidesDefectsOutsideTheRequestingUsersRobotScope() {
        UUID viewerId = UUID.fromString(
                "24000000-0000-4000-8000-000000000301");
        createUser(viewerId, "RPT-VIEWER", "rpt-viewer@example.invalid");
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000204");
        UUID imageId = upload(eventId, jpeg(48, 48));
        ingest(incident(eventId, 1, imageId));

        // 배정도 ADMIN 역할도 없는 사용자에게는 결함 이미지가 보이지 않아야 한다.
        assertThat(reportImageService.findReportImages(
                viewerId,
                BASE_TIME,
                BASE_TIME.plusSeconds(3600),
                1))
                .isEmpty();
    }

    // ------------------------------------------------ 단건 결함 보고서용 이미지

    @Test
    void suppliesTheImageOfTheRequestedDefectOnly() {
        assignAdminRole(ADMIN_ID);
        // 위험도가 더 높은 결함을 먼저 만든다. 기간 기준 조회라면 이쪽이 뽑힌다.
        UUID loudEventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000205");
        upload(loudEventId, jpeg(64, 64));
        ingest(incident(loudEventId, 1, uploadedImageId(loudEventId)));

        UUID targetEventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000206");
        byte[] targetContent = jpeg(200, 100);
        UUID targetImageId = upload(targetEventId, targetContent);
        // 2m 밖에 두어 별개 결함이 되게 한다.
        IncidentIngestionResult target = ingest(
                incidentAt(targetEventId, 2, targetImageId, 37.498500, 127.028500));

        List<DefectReportImage> images =
                reportImageService.findReportImagesForDefect(
                        ADMIN_ID,
                        target.defectId());

        assertThat(images).hasSize(1);
        assertThat(images.getFirst().defectId()).isEqualTo(target.defectId());
        assertThat(images.getFirst().content()).isEqualTo(targetContent);
        assertThat(images.getFirst().width()).isEqualTo(200);
    }

    @Test
    void returnsNoImageForADefectThatHasNone() {
        assignAdminRole(ADMIN_ID);
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000207");
        IncidentIngestionResult result = ingest(incident(eventId, 1, null));

        assertThat(reportImageService.findReportImagesForDefect(
                ADMIN_ID,
                result.defectId()))
                .isEmpty();
    }

    @Test
    void hidesASingleDefectOutsideTheRequestingUsersScope() {
        UUID viewerId = UUID.fromString(
                "24000000-0000-4000-8000-000000000302");
        createUser(viewerId, "RPT-VIEWER-2", "rpt-viewer-2@example.invalid");
        UUID eventId = UUID.fromString(
                "24000000-0000-4000-8000-000000000208");
        UUID imageId = upload(eventId, jpeg(48, 48));
        IncidentIngestionResult result =
                ingest(incident(eventId, 1, imageId));

        assertThat(reportImageService.findReportImagesForDefect(
                viewerId,
                result.defectId()))
                .isEmpty();
    }

    @Test
    void rejectsAMissingDefectIdForSingleDefectReports() {
        assertThatThrownBy(() ->
                reportImageService.findReportImagesForDefect(ADMIN_ID, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.REPORT_VALIDATION_FAILED));
    }

    @Test
    void rejectsAnInvertedReportPeriod() {
        assertThatThrownBy(() -> reportImageService.findReportImages(
                ADMIN_ID,
                BASE_TIME.plusSeconds(3600),
                BASE_TIME,
                1))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.REPORT_VALIDATION_FAILED));
    }

    // ------------------------------------------------------------- helpers

    private UUID upload(UUID eventId, byte[] content) {
        return upload(ROBOT_ID, eventId, content);
    }

    private UUID upload(UUID robotId, UUID eventId, byte[] content) {
        createApiKey(robotId);
        return uploadService.upload(
                        new RobotApiKeyPrincipal(
                                API_KEY_ID,
                                robotId,
                                "gik_link-test"),
                        robotId,
                        eventId,
                        content,
                        BASE_TIME)
                .image()
                .imageId();
    }

    private IncidentIngestionResult ingest(IncidentEventPayload payload) {
        return incidentIngestionService.ingest(
                payload,
                "{\"event_type\":\"BRAILLE_BLOCK_INCIDENT_DETECTED\"}",
                BASE_TIME.plusSeconds(10));
    }

    private UUID observationImageId(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT image_id
                FROM defect.defect_observation
                WHERE event_id = ?
                """,
                UUID.class,
                eventId);
    }

    private void deleteStoredObject(UUID imageId) {
        String objectKey = jdbcTemplate.queryForObject("""
                SELECT object_key FROM storage.robot_image WHERE image_id = ?
                """,
                String.class,
                imageId);
        try {
            java.nio.file.Files.delete(storageRoot.resolve(objectKey));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private UUID uploadedImageId(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT image_id FROM storage.robot_image WHERE event_id = ?
                """,
                UUID.class,
                eventId);
    }

    private static IncidentEventPayload incident(
            UUID eventId,
            long sequence,
            UUID imageId
    ) {
        return incidentAt(eventId, sequence, imageId, 37.497900, 127.027600);
    }

    private static IncidentEventPayload incidentAt(
            UUID eventId,
            long sequence,
            UUID imageId,
            double latitude,
            double longitude
    ) {
        return newIncident(eventId, sequence, imageId, latitude, longitude);
    }

    private static IncidentEventPayload newIncident(
            UUID eventId,
            long sequence,
            UUID imageId,
            double latitude,
            double longitude
    ) {
        return new IncidentEventPayload(
                "2.0",
                eventId,
                IncidentEventPayload.EventType
                        .BRAILLE_BLOCK_INCIDENT_DETECTED,
                "RBT-IMAGE-001",
                BOOT_ID,
                sequence,
                EventSource.AI,
                BASE_TIME.plusSeconds(sequence - 1),
                BASE_TIME.plusSeconds(sequence),
                300,
                "MIS-20260728-001",
                new IncidentEventPayload.Location(
                        true,
                        latitude,
                        longitude,
                        "A-01"),
                new IncidentEventPayload.Incident(
                        IncidentEventPayload.AssetType.BRAILLE_BLOCK,
                        IncidentEventPayload.BlockType.LINEAR,
                        IncidentEventPayload.DefectType.DAMAGED,
                        0.87,
                        new IncidentEventPayload.Attributes(0.32)),
                new IncidentEventPayload.Evidence(
                        imageId,
                        BASE_TIME.plusSeconds(sequence - 1),
                        1280,
                        720,
                        List.of(0.16, 0.42, 0.81, 0.95)),
                new IncidentEventPayload.Model(
                        "tbs-braille-yolo",
                        "1.0.0"));
    }

    private static byte[] jpeg(int width, int height) {
        BufferedImage image =
                new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return output.toByteArray();
    }

    private void createApiKey(UUID robotId) {
        jdbcTemplate.update("""
                INSERT INTO storage.robot_image_api_key (
                    robot_id, key_prefix, key_hash
                ) VALUES (
                    ?,
                    ?,
                    MD5(?::text) || MD5(?::text)
                )
                ON CONFLICT DO NOTHING
                """,
                robotId,
                "gik_" + robotId.toString().substring(0, 8),
                robotId.toString(),
                robotId.toString());
    }

    private void createAdmin() {
        createUser(ADMIN_ID, "ADM-IMAGE-LINK", "image-link@example.invalid");
    }

    private void createUser(UUID userId, String userCode, String email) {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id, user_code, password_hash, display_name, email,
                    account_status, is_active, account_origin
                ) VALUES (
                    ?, ?, '{argon2}$argon2id$image-link-test', ?, ?,
                    'ACTIVE', TRUE, 'ADMIN_CREATED'
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                userId,
                userCode,
                userCode,
                email);
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

    private void createRobot(UUID robotId, String mqttId, String serial) {
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id, mqtt_id, robot_name, serial_number,
                    operational_status, registered_by
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """,
                robotId,
                mqttId,
                mqttId,
                serial,
                ADMIN_ID);
        // 이벤트 수집은 등록된 로봇에 현재 상태 행이 있다고 전제한다.
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_current_state (
                    robot_id, connectivity_status
                ) VALUES (?, 'OFFLINE')
                """,
                robotId);
    }

    private void createPatrolAndMission() {
        jdbcTemplate.update("""
                INSERT INTO robot.patrol_area (
                    patrol_area_id, area_code, area_name, boundary,
                    lifecycle_status, created_by, updated_by,
                    created_at, updated_at
                ) VALUES (
                    ?, 'A-01', 'Image Link Area',
                    ST_GeomFromText(
                        'POLYGON(('
                        || '127.02 37.49,'
                        || '127.04 37.49,'
                        || '127.04 37.51,'
                        || '127.02 37.51,'
                        || '127.02 37.49))',
                        4326
                    ),
                    'ACTIVE', ?, ?,
                    '2026-07-28T00:00:00Z', '2026-07-28T00:00:00Z'
                )
                """,
                AREA_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO robot.patrol_route (
                    patrol_route_id, patrol_area_id, route_code, route_name,
                    route_version, route_geometry, lifecycle_status,
                    created_by, updated_by, created_at, updated_at
                ) VALUES (
                    ?, ?, 'A-01-MAIN', 'Image Link Route', 1,
                    ST_GeomFromText(
                        'LINESTRING(127.025 37.495,127.035 37.505)',
                        4326
                    ),
                    'ACTIVE', ?, ?,
                    '2026-07-28T00:00:00Z', '2026-07-28T00:00:00Z'
                )
                """,
                ROUTE_ID,
                AREA_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO robot.mission (
                    mission_id, mission_code, robot_id, patrol_area_id,
                    patrol_route_id, planned_duration_sec, status,
                    created_by, updated_by, created_at, updated_at
                ) VALUES (
                    ?, 'MIS-20260728-001', ?, ?, ?, 8, 'CREATED',
                    ?, ?, '2026-07-28T00:00:00Z', '2026-07-28T00:00:00Z'
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
