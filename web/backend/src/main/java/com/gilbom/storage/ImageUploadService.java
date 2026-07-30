package com.gilbom.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.storage.RobotImageRepository.NewImage;
import com.gilbom.storage.RobotImageRepository.UploadedImage;
import com.gilbom.storage.auth.RobotApiKeyPrincipal;
import com.gilbom.storage.auth.RobotApiKeyRepository;

/**
 * 로봇 증거 이미지 업로드.
 *
 * <p>계약(api-contract.md 8장, authorization-matrix.md 279행)은 JPEG 500KB 이하,
 * API Key의 로봇과 body {@code robot_id} 일치, {@code event_id} 기준 멱등을 요구한다.
 *
 * <p>Incident MQTT Event보다 이미지가 먼저 도착하므로 여기서는 event를 검증하지
 * 않는다. 연결은 {@code IncidentEventIngestionService.linkAvailableImage()}가
 * 나중에 수행한다.
 */
@Service
public class ImageUploadService {

    /** V22 {@code ck_robot_image_content_type}이 JPEG만 허용한다. */
    static final String CONTENT_TYPE = "image/jpeg";

    /** V22 {@code ck_robot_image_size}와 같은 값. 임베디드 계약도 500KB다. */
    static final int MAX_BYTES = 512_000;

    private static final DateTimeFormatter KEY_DATE_PATH =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final RobotImageRepository imageRepository;
    private final RobotApiKeyRepository apiKeyRepository;
    private final ObjectStorageRegistry storageRegistry;
    private final Clock clock;

    // 테스트가 Clock을 주입하는 생성자를 함께 두므로, 컨테이너가 쓸 생성자를 명시한다.
    @Autowired
    public ImageUploadService(
            RobotImageRepository imageRepository,
            RobotApiKeyRepository apiKeyRepository,
            ObjectStorageRegistry storageRegistry
    ) {
        this(imageRepository, apiKeyRepository, storageRegistry, Clock.systemUTC());
    }

    ImageUploadService(
            RobotImageRepository imageRepository,
            RobotApiKeyRepository apiKeyRepository,
            ObjectStorageRegistry storageRegistry,
            Clock clock
    ) {
        this.imageRepository = imageRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.storageRegistry = storageRegistry;
        this.clock = clock;
    }

    @Transactional
    public UploadOutcome upload(
            RobotApiKeyPrincipal principal,
            UUID robotId,
            UUID eventId,
            byte[] content,
            Instant capturedAt
    ) {
        requireOwnRobot(principal, robotId);
        requireAcceptableSize(content);
        String sha256Hex = sha256Hex(content);

        Optional<UploadedImage> existing = imageRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            return new UploadOutcome(
                    reuse(existing.get(), robotId, eventId, sha256Hex),
                    false);
        }

        Dimensions dimensions = readDimensions(content);
        Instant uploadedAt = clock.instant();
        Instant resolvedCapturedAt = resolveCapturedAt(capturedAt, uploadedAt);
        UUID imageId = UUID.randomUUID();
        ObjectStorageClient storage = storageRegistry.primary();
        String objectKey = objectKey(robotId, imageId, resolvedCapturedAt);

        // 저장소에 먼저 쓴다. 이 트랜잭션이 뒤에서 롤백되면 참조되지 않는 객체가
        // 남지만, 반대로 DB 행만 있고 파일이 없는 상태보다 복구가 쉽다.
        storage.put(objectKey, content, CONTENT_TYPE);
        imageRepository.insert(new NewImage(
                imageId,
                robotId,
                eventId,
                storage.bucketName(),
                objectKey,
                CONTENT_TYPE,
                content.length,
                sha256Hex,
                dimensions.width(),
                dimensions.height(),
                resolvedCapturedAt,
                uploadedAt));
        apiKeyRepository.touchLastUsedAt(principal.apiKeyId());

        return new UploadOutcome(
                new StoredImageSummary(
                        imageId,
                        eventId,
                        objectKey,
                        dimensions.width(),
                        dimensions.height()),
                true);
    }

    /**
     * 같은 event_id로 다시 올라온 요청이다. 같은 로봇이 같은 파일을 재전송한
     * 경우에만 기존 결과를 그대로 돌려주고, 그 밖에는 충돌로 막는다. 내용이 다른데
     * 200을 주면 로봇은 성공으로 알고 실제로는 다른 이미지가 연결된다.
     */
    private StoredImageSummary reuse(
            UploadedImage existing,
            UUID robotId,
            UUID eventId,
            String sha256Hex
    ) {
        if (!existing.robotId().equals(robotId)) {
            throw new BusinessException(
                    ErrorCode.COMMON_CONFLICT,
                    "다른 로봇이 이미 사용한 event_id입니다.");
        }
        if (!existing.sha256Hex().equals(sha256Hex)) {
            throw new BusinessException(
                    ErrorCode.COMMON_CONFLICT,
                    "같은 event_id로 다른 이미지를 올릴 수 없습니다.");
        }
        return new StoredImageSummary(
                existing.imageId(),
                eventId,
                existing.objectKey(),
                existing.width(),
                existing.height());
    }

    private static void requireOwnRobot(
            RobotApiKeyPrincipal principal,
            UUID robotId
    ) {
        if (!principal.robotId().equals(robotId)) {
            // 존재 여부를 흘리지 않도록 이유를 세분화하지 않는다.
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireAcceptableSize(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "이미지 본문이 비어 있습니다.");
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
        }
    }

    /**
     * Content-Type 헤더는 클라이언트가 붙이는 값이라 신뢰하지 않는다. JPEG인지는
     * 실제 바이트로 판정하고, 크기는 디코딩된 결과에서 읽는다.
     */
    private static Dimensions readDimensions(byte[] content) {
        if (!isJpeg(content)) {
            throw new BusinessException(ErrorCode.IMAGE_UNSUPPORTED_MEDIA_TYPE);
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.IMAGE_UNSUPPORTED_MEDIA_TYPE,
                    exception);
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new BusinessException(ErrorCode.IMAGE_UNSUPPORTED_MEDIA_TYPE);
        }
        return new Dimensions(image.getWidth(), image.getHeight());
    }

    private static boolean isJpeg(byte[] content) {
        // SOI marker. Content-Type을 위조해도 여기서 걸린다.
        return content.length > 3
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF;
    }

    /**
     * V22 {@code ck_robot_image_timestamps}가 {@code uploaded_at >= captured_at}을
     * 요구한다. 미래 시각을 그대로 넣으면 제약 위반으로 500이 되므로 여기서 막는다.
     *
     * <p>촬영 시각을 보내지 않는 클라이언트가 기본이다. 결함 조회가 쓰는 정본은
     * Incident Event의 {@code evidence.capturedAt}이며 여기 값은 저장소 메타데이터다.
     */
    private static Instant resolveCapturedAt(Instant capturedAt, Instant now) {
        if (capturedAt == null) {
            return now;
        }
        if (capturedAt.isAfter(now)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "촬영 시각이 미래일 수 없습니다.");
        }
        return capturedAt;
    }

    private static String objectKey(
            UUID robotId,
            UUID imageId,
            Instant capturedAt
    ) {
        return "robots/" + robotId + "/"
                + KEY_DATE_PATH.format(capturedAt) + "/"
                + imageId + ".jpg";
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record UploadOutcome(StoredImageSummary image, boolean created) {
    }

    public record StoredImageSummary(
            UUID imageId,
            UUID eventId,
            String objectKey,
            int width,
            int height
    ) {
    }

    private record Dimensions(int width, int height) {
    }
}
