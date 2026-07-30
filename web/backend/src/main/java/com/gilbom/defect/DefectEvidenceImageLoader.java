package com.gilbom.defect;

import java.util.Optional;
import java.util.UUID;

/**
 * 결함 근거 이미지의 바이트를 읽어오는 port다.
 *
 * <p>{@link DefectImageUrlIssuer}는 브라우저가 {@code <img src>}로 열 URL을 내주는
 * 반면, 이쪽은 서버 안에서 바이트가 필요한 소비자(LLM 보고서 생성)를 위한 것이다.
 * URL을 발급받아 서버가 자기 자신을 다시 HTTP로 호출하는 왕복을 만들지 않는다.
 *
 * <p>구현은 storage 모듈에 있고 defect는 저장소 종류를 알지 못한다.
 */
public interface DefectEvidenceImageLoader {

    /**
     * @return 이미지 바이트. 이미지가 없거나 저장소에서 읽지 못하면 빈 값
     */
    Optional<LoadedImage> load(UUID imageId);

    record LoadedImage(
            byte[] content,
            String contentType,
            int width,
            int height
    ) {

        public LoadedImage {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
