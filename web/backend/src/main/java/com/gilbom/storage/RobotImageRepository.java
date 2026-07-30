package com.gilbom.storage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code storage.robot_image} 접근. 이미지 메타데이터의 정본이다.
 */
@Repository
public class RobotImageRepository {

    private final JdbcTemplate jdbcTemplate;

    public RobotImageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 업로드 멱등성의 자연 키는 {@code event_id}다. V22의 부분 unique index가
     * 이를 강제하고, 재업로드는 새 행을 만드는 대신 이 조회로 기존 행을 돌려준다.
     */
    public Optional<UploadedImage> findByEventId(UUID eventId) {
        return Optional.ofNullable(jdbcTemplate.query("""
                        SELECT image_id, robot_id, sha256_hex, object_key,
                               image_width, image_height
                        FROM storage.robot_image
                        WHERE event_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? new UploadedImage(
                                resultSet.getObject("image_id", UUID.class),
                                resultSet.getObject("robot_id", UUID.class),
                                resultSet.getString("sha256_hex"),
                                resultSet.getString("object_key"),
                                resultSet.getInt("image_width"),
                                resultSet.getInt("image_height"))
                        : null,
                eventId));
    }

    public void insert(NewImage image) {
        jdbcTemplate.update("""
                INSERT INTO storage.robot_image (
                    image_id,
                    robot_id,
                    event_id,
                    bucket_name,
                    object_key,
                    content_type,
                    byte_size,
                    sha256_hex,
                    image_width,
                    image_height,
                    captured_at,
                    uploaded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                image.imageId(),
                image.robotId(),
                image.eventId(),
                image.bucketName(),
                image.objectKey(),
                image.contentType(),
                image.byteSize(),
                image.sha256Hex(),
                image.width(),
                image.height(),
                OffsetDateTime.ofInstant(image.capturedAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(image.uploadedAt(), ZoneOffset.UTC));
    }

    /**
     * 조회 가능한 이미지만 돌려준다. 격리·삭제된 이미지는 없는 것으로 취급한다.
     */
    public Optional<StoredImage> findAvailable(UUID imageId) {
        return Optional.ofNullable(jdbcTemplate.query("""
                        SELECT bucket_name, object_key, content_type,
                               image_width, image_height
                        FROM storage.robot_image
                        WHERE image_id = ?
                          AND image_status = 'AVAILABLE'
                        """,
                resultSet -> resultSet.next()
                        ? new StoredImage(
                                imageId,
                                resultSet.getString("bucket_name"),
                                resultSet.getString("object_key"),
                                resultSet.getString("content_type"),
                                resultSet.getInt("image_width"),
                                resultSet.getInt("image_height"))
                        : null,
                imageId));
    }

    public record NewImage(
            UUID imageId,
            UUID robotId,
            UUID eventId,
            String bucketName,
            String objectKey,
            String contentType,
            int byteSize,
            String sha256Hex,
            int width,
            int height,
            Instant capturedAt,
            /**
             * DB 기본값 NOW()를 쓰지 않고 앱이 명시한다. PostgreSQL의 NOW()는
             * 트랜잭션 시작 시각이라 그보다 뒤에 계산한 captured_at이 더 미래가 될 수
             * 있고, 그러면 ck_robot_image_timestamps(uploaded_at >= captured_at)에
             * 걸린다. 두 값을 같은 시계에서 뽑아야 순서가 보장된다.
             */
            Instant uploadedAt
    ) {
    }

    public record UploadedImage(
            UUID imageId,
            UUID robotId,
            String sha256Hex,
            String objectKey,
            int width,
            int height
    ) {
    }

    public record StoredImage(
            UUID imageId,
            String bucketName,
            String objectKey,
            String contentType,
            int width,
            int height
    ) {
    }
}
