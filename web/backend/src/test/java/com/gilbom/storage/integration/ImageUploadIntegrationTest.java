package com.gilbom.storage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import com.gilbom.storage.ImageBytesReader;
import com.gilbom.storage.ImageUploadService;
import com.gilbom.storage.ImageUploadService.UploadOutcome;
import com.gilbom.storage.LocalFileObjectStorageClient;
import com.gilbom.storage.ObjectStorageRegistry;
import com.gilbom.storage.RobotImageRepository;
import com.gilbom.storage.StorageProperties;
import com.gilbom.storage.auth.RobotApiKeyPrincipal;
import com.gilbom.storage.auth.RobotApiKeyRepository;

/**
 * 이미지 업로드·조회 경로를 실제 PostgreSQL과 파일 저장소에서 검증한다.
 *
 * <p>단위 테스트는 서비스의 판단만 확인한다. {@code storage.robot_image}의 CHECK
 * 제약(JPEG content_type, 1~512000 byte, uploaded_at >= captured_at)과 V31의 API Key
 * 스키마가 실제로 우리가 넣는 값을 받아들이는지는 DB에서만 확인된다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = ImageUploadIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class ImageUploadIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID ADMIN_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000041");
    private static final UUID ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2141");
    private static final UUID EVENT_ID =
            UUID.fromString("9a1e0000-0000-4000-8000-000000000041");

    /** scripts/issue-robot-image-api-key.py가 만드는 것과 같은 형태의 값이다. */
    private static final String API_KEY = "gik_integration-test-key";
    private static final String API_KEY_PREFIX = "gik_integrat";

    @TempDir
    static Path storageRoot;

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_image_upload_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.default-schema", () -> "flyway_history");
        registry.add(
                "spring.flyway.placeholders.applicationRole",
                DATABASE::getUsername);
        registry.add(
                "gilbom.storage.local-root-directory",
                () -> storageRoot.toString());
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ImageUploadService uploadService;

    @Autowired
    private ImageBytesReader imageBytesReader;

    @Autowired
    private RobotApiKeyRepository apiKeyRepository;

    @BeforeEach
    void setUpFixtures() {
        jdbcTemplate.update("DELETE FROM storage.robot_image");
        jdbcTemplate.update("DELETE FROM storage.robot_image_api_key");
        createAdminUser();
        createRobot();
        createApiKey();
    }

    @Test
    void resolvesTheRobotFromItsApiKeyHash() {
        RobotApiKeyPrincipal principal =
                apiKeyRepository.findActiveByHash(sha256Hex(API_KEY))
                        .orElseThrow();

        assertThat(principal.robotId()).isEqualTo(ROBOT_ID);
        assertThat(principal.keyPrefix()).isEqualTo(API_KEY_PREFIX);
    }

    @Test
    void doesNotResolveRevokedKeys() {
        jdbcTemplate.update("""
                UPDATE storage.robot_image_api_key
                SET key_status = 'REVOKED', revoked_at = NOW()
                WHERE robot_id = ?
                """,
                ROBOT_ID);

        assertThat(apiKeyRepository.findActiveByHash(sha256Hex(API_KEY)))
                .isEmpty();
    }

    @Test
    void doesNotResolveExpiredKeys() {
        jdbcTemplate.update("""
                UPDATE storage.robot_image_api_key
                SET issued_at = NOW() - INTERVAL '2 days',
                    expires_at = NOW() - INTERVAL '1 day'
                WHERE robot_id = ?
                """,
                ROBOT_ID);

        assertThat(apiKeyRepository.findActiveByHash(sha256Hex(API_KEY)))
                .isEmpty();
    }

    @Test
    void storesTheImageAndSatisfiesEveryColumnConstraint() {
        byte[] content = jpeg(320, 240);

        UploadOutcome outcome = uploadService.upload(
                principal(), ROBOT_ID, EVENT_ID, content, null);

        assertThat(outcome.created()).isTrue();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT bucket_name FROM storage.robot_image WHERE image_id = ?
                """, String.class, outcome.image().imageId()))
                .isEqualTo("local-disk");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT image_status FROM storage.robot_image WHERE image_id = ?
                """, String.class, outcome.image().imageId()))
                .isEqualTo("AVAILABLE");
        // 업로드 성공은 키 사용 기록을 남긴다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT last_used_at IS NOT NULL
                FROM storage.robot_image_api_key WHERE robot_id = ?
                """, Boolean.class, ROBOT_ID))
                .isTrue();
    }

    @Test
    void writesTheObjectWhereTheRowSaysItIs() {
        byte[] content = jpeg(64, 64);

        UploadOutcome outcome = uploadService.upload(
                principal(), ROBOT_ID, EVENT_ID, content, null);

        Path stored = storageRoot.resolve(outcome.image().objectKey());
        assertThat(stored).exists();
        assertThat(read(stored)).isEqualTo(content);
    }

    @Test
    void readsBackTheSameBytesThroughTheStorageAdapter() {
        byte[] content = jpeg(128, 96);
        UploadOutcome outcome = uploadService.upload(
                principal(), ROBOT_ID, EVENT_ID, content, null);

        ImageBytesReader.ImageBytes image =
                imageBytesReader.read(outcome.image().imageId());

        assertThat(image.content()).isEqualTo(content);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(image.width()).isEqualTo(128);
        assertThat(image.height()).isEqualTo(96);
    }

    @Test
    void reuploadingTheSameEventDoesNotCreateASecondRow() {
        byte[] content = jpeg(48, 48);
        UUID first = uploadService
                .upload(principal(), ROBOT_ID, EVENT_ID, content, null)
                .image()
                .imageId();

        UploadOutcome second = uploadService.upload(
                principal(), ROBOT_ID, EVENT_ID, content, null);

        // uploader.py는 타임아웃에 최대 3회 재시도한다. 그때마다 행이 늘면 안 된다.
        assertThat(second.created()).isFalse();
        assertThat(second.image().imageId()).isEqualTo(first);
        assertThat(rowCount()).isEqualTo(1);
    }

    private RobotApiKeyPrincipal principal() {
        return apiKeyRepository.findActiveByHash(sha256Hex(API_KEY))
                .orElseThrow();
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM storage.robot_image", Integer.class);
    }

    private void createAdminUser() {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id, user_code, password_hash, display_name, email,
                    account_status, is_active, password_change_required,
                    password_changed_at, account_origin
                ) VALUES (
                    ?, 'IMG-ADMIN', '{argon2}$argon2id$integration-test-image',
                    'Image Admin', 'image-admin@gilbom.test',
                    'ACTIVE', TRUE, FALSE, NOW(), 'ADMIN_CREATED'
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                ADMIN_USER_ID);
    }

    private void createRobot() {
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id, mqtt_id, robot_name, serial_number,
                    operational_status, registered_by
                ) VALUES (?, 'RBT-041', 'Image Robot', 'SN-RBT-041', 'ACTIVE', ?)
                ON CONFLICT (robot_id) DO NOTHING
                """,
                ROBOT_ID,
                ADMIN_USER_ID);
    }

    private void createApiKey() {
        jdbcTemplate.update("""
                INSERT INTO storage.robot_image_api_key (
                    robot_id, key_prefix, key_hash
                ) VALUES (?, ?, ?)
                """,
                ROBOT_ID,
                API_KEY_PREFIX,
                sha256Hex(API_KEY));
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            StorageProperties.class,
            LocalFileObjectStorageClient.class,
            ObjectStorageRegistry.class,
            RobotImageRepository.class,
            RobotApiKeyRepository.class,
            ImageBytesReader.class,
            ImageUploadService.class
    })
    static class TestApplication {
    }
}
