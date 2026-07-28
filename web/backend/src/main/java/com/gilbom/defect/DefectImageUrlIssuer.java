package com.gilbom.defect;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 결함 근거 이미지의 단기 조회 URL 발급 지점이다.
 *
 * <p>20장 공통 규칙은 파일을 S3 Private에 두고 DB에는 object_key/file_id만 저장한
 * 뒤 조회 시 만료 URL을 발급하도록 정한다. 그 storage 스키마와 S3 adapter는 아직
 * 이 백엔드에 없으므로, 결함 조회 흐름이 저장소 구현에 직접 묶이지 않도록 발급을
 * 이 port로 분리한다. storage 모듈이 추가되면 이 인터페이스 구현만 교체한다.
 */
public interface DefectImageUrlIssuer {

    /**
     * @return 발급된 URL. 저장소 연동이 준비되지 않았으면 빈 값
     */
    Optional<IssuedImageUrl> issue(UUID imageId);

    record IssuedImageUrl(
            String url,
            Instant expiresAt
    ) {
    }
}
