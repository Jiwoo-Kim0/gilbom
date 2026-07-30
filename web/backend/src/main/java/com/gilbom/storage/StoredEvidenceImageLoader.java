package com.gilbom.storage;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gilbom.common.error.BusinessException;
import com.gilbom.defect.DefectEvidenceImageLoader;
import com.gilbom.storage.ImageBytesReader.ImageBytes;

/**
 * {@link DefectEvidenceImageLoader}를 저장소로 연결한다.
 *
 * <p>읽기 실패를 예외가 아니라 빈 값으로 바꾸는 것이 이 클래스의 역할이다. 보고서
 * 생성은 이미지 한 장 때문에 실패하면 안 되고, 호출부가 매번 예외를 잡게 하면
 * 언젠가 빠뜨린다.
 */
@Component
public class StoredEvidenceImageLoader implements DefectEvidenceImageLoader {

    private static final Logger log =
            LoggerFactory.getLogger(StoredEvidenceImageLoader.class);

    private final ImageBytesReader imageBytesReader;

    public StoredEvidenceImageLoader(ImageBytesReader imageBytesReader) {
        this.imageBytesReader = imageBytesReader;
    }

    @Override
    public Optional<LoadedImage> load(UUID imageId) {
        if (imageId == null) {
            return Optional.empty();
        }
        try {
            ImageBytes image = imageBytesReader.read(imageId);
            return Optional.of(new LoadedImage(
                    image.content(),
                    image.contentType(),
                    image.width(),
                    image.height()));
        } catch (BusinessException exception) {
            // 이미지 식별자만 남긴다. 바이트와 저장소 경로는 로그에 넣지 않는다.
            log.warn("Evidence image {} is not readable: {}",
                    imageId,
                    exception.errorCode());
            return Optional.empty();
        }
    }
}
