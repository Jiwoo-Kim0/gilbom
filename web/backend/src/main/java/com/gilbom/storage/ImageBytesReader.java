package com.gilbom.storage;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.storage.RobotImageRepository.StoredImage;

/**
 * 이미지 바이트를 읽는 단일 진입점이다.
 *
 * <p>브라우저 스트리밍({@code GET /api/v1/images/{id}/content})과 LLM 보고서 입력이
 * 모두 여기를 지난다. 저장소 종류와 bucket 분기를 한 곳에만 두기 위함이다.
 */
@Service
public class ImageBytesReader {

    private final RobotImageRepository imageRepository;
    private final ObjectStorageRegistry storageRegistry;

    public ImageBytesReader(
            RobotImageRepository imageRepository,
            ObjectStorageRegistry storageRegistry
    ) {
        this.imageRepository = imageRepository;
        this.storageRegistry = storageRegistry;
    }

    /**
     * @throws BusinessException 이미지가 없거나 조회 가능한 상태가 아니면 404
     */
    @Transactional(readOnly = true)
    public ImageBytes read(UUID imageId) {
        StoredImage image = imageRepository.findAvailable(imageId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
        byte[] content = storageRegistry
                .forBucket(image.bucketName())
                .read(image.bucketName(), image.objectKey());
        return new ImageBytes(
                imageId,
                content,
                image.contentType(),
                image.width(),
                image.height());
    }

    public record ImageBytes(
            UUID imageId,
            byte[] content,
            String contentType,
            int width,
            int height
    ) {

        public ImageBytes {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
