package com.gilbom.storage.url;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.gilbom.defect.DefectImageUrlIssuer;
import com.gilbom.storage.url.ImageUrlSigner.SignedImageUrl;

/**
 * {@link DefectImageUrlIssuer}의 실제 구현이다. 이 빈이 등록되면서 결함 이미지
 * 조회가 503을 멈춘다.
 *
 * <p>발급 시점에는 저장소를 조회하지 않는다. S3 presigned URL도 존재하지 않는 key에
 * 서명할 수 있고, 실제 존재 여부는 가져올 때 판정된다. 같은 의미론을 유지해야
 * S3로 바꿔도 프론트가 보는 동작이 달라지지 않는다. 이미지가 없으면
 * {@code GET /api/v1/images/{id}/content}가 404로 답한다.
 */
@Component
public class SignedUrlDefectImageUrlIssuer implements DefectImageUrlIssuer {

    private final ImageUrlSigner signer;

    public SignedUrlDefectImageUrlIssuer(ImageUrlSigner signer) {
        this.signer = signer;
    }

    @Override
    public Optional<IssuedImageUrl> issue(UUID imageId) {
        if (imageId == null) {
            return Optional.empty();
        }
        SignedImageUrl signed = signer.issue(imageId);
        return Optional.of(
                new IssuedImageUrl(signed.url(), signed.expiresAt()));
    }
}
