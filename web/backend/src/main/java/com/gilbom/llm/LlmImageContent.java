package com.gilbom.llm;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * 외부 LLM에 전달할 비식별화 완료 JPEG의 메모리 전용 값이다.
 *
 * <p>Presigned URL, bucket, object key와 로컬 경로는 이 계약에 포함하지 않는다.
 */
public final class LlmImageContent {

    public static final int MAXIMUM_BYTES = 512_000;
    public static final String SUPPORTED_CONTENT_TYPE =
            "image/jpeg";

    private final UUID defectId;
    private final String incidentCode;
    private final byte[] bytes;
    private final String contentType;
    private final int width;
    private final int height;

    public LlmImageContent(
            UUID defectId,
            String incidentCode,
            byte[] bytes,
            String contentType,
            int width,
            int height
    ) {
        this.defectId = Objects.requireNonNull(
                defectId,
                "defectId must not be null");
        this.incidentCode = requiredText(incidentCode);
        if (bytes == null
                || bytes.length == 0
                || bytes.length > MAXIMUM_BYTES
                || !isJpeg(bytes)
                || containsExif(bytes)
                || !SUPPORTED_CONTENT_TYPE.equals(contentType)
                || width < 1
                || height < 1) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.contentType = contentType;
        this.width = width;
        this.height = height;
    }

    public UUID defectId() {
        return defectId;
    }

    public String incidentCode() {
        return incidentCode;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public String contentType() {
        return contentType;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int byteSize() {
        return bytes.length;
    }

    @Override
    public String toString() {
        return "LlmImageContent{"
                + "evidenceIdentity=[REDACTED]"
                + ", bytes=[REDACTED]"
                + ", byteSize=" + bytes.length
                + ", contentType='" + contentType + '\''
                + ", width=" + width
                + ", height=" + height
                + '}';
    }

    private static String requiredText(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 40
                || !value.trim().matches(
                        "^INC-[A-Z0-9-]+$")) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        return value.trim();
    }

    private static boolean isJpeg(byte[] value) {
        return value.length >= 4
                && (value[0] & 0xff) == 0xff
                && (value[1] & 0xff) == 0xd8
                && (value[value.length - 2] & 0xff) == 0xff
                && (value[value.length - 1] & 0xff) == 0xd9;
    }

    private static boolean containsExif(byte[] value) {
        byte[] marker = new byte[] {
                'E', 'x', 'i', 'f', 0, 0
        };
        for (int offset = 0;
                offset <= value.length - marker.length;
                offset++) {
            boolean matches = true;
            for (int index = 0;
                    index < marker.length;
                    index++) {
                if (value[offset + index] != marker[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
