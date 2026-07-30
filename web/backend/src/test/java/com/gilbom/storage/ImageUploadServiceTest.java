package com.gilbom.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.storage.ImageUploadService.UploadOutcome;
import com.gilbom.storage.RobotImageRepository.NewImage;
import com.gilbom.storage.RobotImageRepository.UploadedImage;
import com.gilbom.storage.auth.RobotApiKeyPrincipal;
import com.gilbom.storage.auth.RobotApiKeyRepository;

class ImageUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");
    private static final UUID ROBOT_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_ROBOT_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVENT_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID API_KEY_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final RobotApiKeyPrincipal PRINCIPAL =
            new RobotApiKeyPrincipal(API_KEY_ID, ROBOT_ID, "gik_abcdefgh");

    private RobotImageRepository imageRepository;
    private RobotApiKeyRepository apiKeyRepository;
    private InMemoryObjectStorageClient storage;
    private ImageUploadService service;

    @BeforeEach
    void setUp() {
        imageRepository = mock(RobotImageRepository.class);
        apiKeyRepository = mock(RobotApiKeyRepository.class);
        storage = new InMemoryObjectStorageClient();
        when(imageRepository.findByEventId(any())).thenReturn(Optional.empty());
        service = new ImageUploadService(
                imageRepository,
                apiKeyRepository,
                new ObjectStorageRegistry(List.of(storage)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesJpegAndRecordsMetadata() {
        byte[] content = jpeg(640, 480);

        UploadOutcome outcome =
                service.upload(PRINCIPAL, ROBOT_ID, EVENT_ID, content, null);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.image().width()).isEqualTo(640);
        assertThat(outcome.image().height()).isEqualTo(480);
        assertThat(outcome.image().eventId()).isEqualTo(EVENT_ID);
        // 촬영 시각을 보내지 않으면 수신 시각으로 경로가 정해진다.
        assertThat(outcome.image().objectKey())
                .isEqualTo("robots/" + ROBOT_ID + "/2026/07/30/"
                        + outcome.image().imageId() + ".jpg");
        assertThat(storage.objects).containsKey(outcome.image().objectKey());
        verify(apiKeyRepository).touchLastUsedAt(API_KEY_ID);
    }

    @Test
    void persistsRowThatMatchesTheStoredObject() {
        byte[] content = jpeg(64, 32);

        service.upload(PRINCIPAL, ROBOT_ID, EVENT_ID, content, null);

        NewImage saved = captureSavedImage();
        assertThat(saved.robotId()).isEqualTo(ROBOT_ID);
        assertThat(saved.eventId()).isEqualTo(EVENT_ID);
        assertThat(saved.bucketName())
                .isEqualTo(InMemoryObjectStorageClient.BUCKET);
        assertThat(saved.contentType()).isEqualTo("image/jpeg");
        assertThat(saved.byteSize()).isEqualTo(content.length);
        assertThat(saved.sha256Hex()).hasSize(64);
        assertThat(saved.capturedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsUploadForAnotherRobot() {
        assertThatThrownBy(() -> service.upload(
                PRINCIPAL, OTHER_ROBOT_ID, EVENT_ID, jpeg(8, 8), null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
        assertThat(storage.objects).isEmpty();
    }

    @Test
    void rejectsContentOverTheContractLimit() {
        byte[] oversized = new byte[ImageUploadService.MAX_BYTES + 1];
        oversized[0] = (byte) 0xFF;
        oversized[1] = (byte) 0xD8;
        oversized[2] = (byte) 0xFF;

        assertThatThrownBy(() -> service.upload(
                PRINCIPAL, ROBOT_ID, EVENT_ID, oversized, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
    }

    @Test
    void rejectsNonJpegEvenWhenTheClientClaimsOtherwise() {
        // PNG signature. Content-Type 헤더는 신뢰하지 않고 바이트로 판정한다.
        byte[] png = {
                (byte) 0x89, 'P', 'N', 'G',
                0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        assertThatThrownBy(() -> service.upload(
                PRINCIPAL, ROBOT_ID, EVENT_ID, png, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.IMAGE_UNSUPPORTED_MEDIA_TYPE);
        assertThat(storage.objects).isEmpty();
    }

    @Test
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> service.upload(
                PRINCIPAL, ROBOT_ID, EVENT_ID, new byte[0], null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    void returnsTheExistingImageWhenTheSameFileIsSentAgain() {
        byte[] content = jpeg(100, 50);
        UUID existingImageId = UUID.randomUUID();
        when(imageRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(
                new UploadedImage(
                        existingImageId,
                        ROBOT_ID,
                        sha256HexOf(content),
                        "robots/key.jpg",
                        100,
                        50)));

        UploadOutcome outcome =
                service.upload(PRINCIPAL, ROBOT_ID, EVENT_ID, content, null);

        // QoS 1 재전송과 uploader.py의 재시도가 새 행을 만들면 안 된다.
        assertThat(outcome.created()).isFalse();
        assertThat(outcome.image().imageId()).isEqualTo(existingImageId);
        verify(imageRepository, never()).insert(any());
        assertThat(storage.objects).isEmpty();
    }

    @Test
    void rejectsADifferentImageUnderAnAlreadyUsedEventId() {
        when(imageRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(
                new UploadedImage(
                        UUID.randomUUID(),
                        ROBOT_ID,
                        sha256HexOf(jpeg(10, 10)),
                        "robots/key.jpg",
                        10,
                        10)));

        assertThatThrownBy(() -> service.upload(
                PRINCIPAL, ROBOT_ID, EVENT_ID, jpeg(20, 20), null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_CONFLICT);
    }

    @Test
    void rejectsAnEventIdAlreadyUsedByAnotherRobot() {
        byte[] content = jpeg(10, 10);
        when(imageRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(
                new UploadedImage(
                        UUID.randomUUID(),
                        OTHER_ROBOT_ID,
                        sha256HexOf(content),
                        "robots/key.jpg",
                        10,
                        10)));

        assertThatThrownBy(() -> service.upload(
                PRINCIPAL, ROBOT_ID, EVENT_ID, content, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_CONFLICT);
    }

    @Test
    void usesTheSuppliedCaptureTimeForTheObjectKey() {
        Instant capturedAt = Instant.parse("2026-07-29T23:30:00Z");

        UploadOutcome outcome = service.upload(
                PRINCIPAL, ROBOT_ID, EVENT_ID, jpeg(8, 8), capturedAt);

        assertThat(outcome.image().objectKey())
                .contains("/2026/07/29/");
        assertThat(captureSavedImage().capturedAt()).isEqualTo(capturedAt);
    }

    @Test
    void rejectsACaptureTimeInTheFuture() {
        // V22 ck_robot_image_timestamps가 uploaded_at >= captured_at을 요구한다.
        assertThatThrownBy(() -> service.upload(
                PRINCIPAL,
                ROBOT_ID,
                EVENT_ID,
                jpeg(8, 8),
                NOW.plusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
    }

    private NewImage captureSavedImage() {
        org.mockito.ArgumentCaptor<NewImage> captor =
                org.mockito.ArgumentCaptor.forClass(NewImage.class);
        verify(imageRepository).insert(captor.capture());
        return captor.getValue();
    }

    private static String sha256HexOf(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
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

    private static final class InMemoryObjectStorageClient
            implements ObjectStorageClient {

        private static final String BUCKET = "in-memory";

        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public String bucketName() {
            return BUCKET;
        }

        @Override
        public boolean supports(String bucketName) {
            return BUCKET.equals(bucketName);
        }

        @Override
        public void put(String objectKey, byte[] content, String contentType) {
            objects.put(objectKey, content.clone());
        }

        @Override
        public byte[] read(String bucketName, String objectKey) {
            byte[] content = objects.get(objectKey);
            if (content == null) {
                throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
            }
            return content.clone();
        }
    }
}
