package com.gilbom.storage.api;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.storage.ImageUploadService;
import com.gilbom.storage.ImageUploadService.UploadOutcome;
import com.gilbom.storage.auth.ImageApiAuthorities;
import com.gilbom.storage.auth.RobotApiKeyPrincipal;

/**
 * {@code POST /api/v1/images} — 로봇 증거 이미지 업로드.
 *
 * <p>form 필드 이름은 {@code embedded/comms/uploader.py}가 이미 보내고 있는 값과
 * 같아야 한다: {@code file}, {@code event_id}, {@code robot_id}. 응답 body는
 * {@code imageId}를 담는다.
 *
 * <p>인증은 세션이 아니라 {@code X-API-Key}이며, 권한은
 * {@code RobotApiKeyAuthenticationFilter}가 세운다.
 */
@RestController
@RequestMapping("/api/v1/images")
public class ImageUploadController {

    private final ImageUploadService uploadService;

    public ImageUploadController(ImageUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + ImageApiAuthorities.ROBOT_IMAGE_UPLOAD + "')")
    public ResponseEntity<ImageUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("event_id") String eventId,
            @RequestParam("robot_id") String robotId,
            // 계약에 없는 선택 필드다. 보내지 않으면 서버 수신 시각을 쓴다.
            @RequestParam(name = "captured_at", required = false) String capturedAt,
            Authentication authentication
    ) {
        UploadOutcome outcome = uploadService.upload(
                principal(authentication),
                uuid(robotId, "robot_id"),
                uuid(eventId, "event_id"),
                bytes(file),
                instant(capturedAt));

        ImageUploadResponse body = new ImageUploadResponse(
                outcome.image().imageId(),
                outcome.image().eventId(),
                outcome.image().objectKey(),
                outcome.image().width(),
                outcome.image().height());
        // 201 신규, 200 멱등 재업로드. 로봇 클라이언트는 둘 다 성공으로 처리한다.
        return ResponseEntity
                .status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(body);
    }

    private static RobotApiKeyPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal()
                        instanceof RobotApiKeyPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        }
        return principal;
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "file 필드가 비어 있습니다.");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    exception);
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    field + "는 UUID여야 합니다.");
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "captured_at은 ISO-8601 UTC 시각이어야 합니다.");
        }
    }
}
