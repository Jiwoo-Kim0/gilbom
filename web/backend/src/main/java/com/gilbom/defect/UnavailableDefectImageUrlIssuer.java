package com.gilbom.defect;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * storage 스키마·S3 adapter가 없는 동안의 기본 구현이다.
 *
 * <p>URL을 임의로 만들어 200으로 응답하면 프론트가 깨진 이미지를 정상 응답으로
 * 취급하므로, 발급 불가를 명시적으로 알려 503으로 끝나게 한다. storage 모듈이
 * 추가되면 이 구현을 실제 presigned URL 발급기로 교체한다.
 */
@Component
class UnavailableDefectImageUrlIssuer implements DefectImageUrlIssuer {

    @Override
    public Optional<IssuedImageUrl> issue(UUID imageId) {
        return Optional.empty();
    }
}
