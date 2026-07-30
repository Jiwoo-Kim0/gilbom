package com.gilbom.storage.api;

import java.util.UUID;

/**
 * {@code POST /api/v1/images} 응답.
 *
 * <p>{@code embedded/comms/uploader.py}가 {@code imageId} 키를 읽는다.
 * 이름을 바꾸면 로봇이 업로드 성공을 실패로 처리하므로 계약으로 고정한다.
 */
public record ImageUploadResponse(
        UUID imageId,
        UUID eventId,
        String objectKey,
        int imageWidth,
        int imageHeight
) {
}
