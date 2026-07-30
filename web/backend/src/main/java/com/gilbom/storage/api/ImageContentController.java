package com.gilbom.storage.api;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.storage.ImageBytesReader;
import com.gilbom.storage.ImageBytesReader.ImageBytes;
import com.gilbom.storage.auth.ImageApiAuthorities;

/**
 * {@code GET /api/v1/images/{imageId}/content?token=...} — 서명 URL로 이미지 열람.
 *
 * <p>브라우저 {@code <img src>}가 직접 호출하는 지점이다. 인증은 세션이 아니라
 * URL 서명이며 {@code SignedImageUrlAuthenticationFilter}가 검증한다.
 */
@RestController
@RequestMapping("/api/v1/images")
public class ImageContentController {

    private final ImageBytesReader imageBytesReader;

    public ImageContentController(ImageBytesReader imageBytesReader) {
        this.imageBytesReader = imageBytesReader;
    }

    @GetMapping("/{imageId}/content")
    @PreAuthorize("hasAuthority('" + ImageApiAuthorities.IMAGE_CONTENT_READ + "')")
    public ResponseEntity<byte[]> read(
            @PathVariable UUID imageId,
            Authentication authentication
    ) {
        requireTokenBoundTo(authentication, imageId);

        ImageBytes image = imageBytesReader.read(imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                // 만료가 짧은 자격증명으로 여는 비공개 이미지다. 디스크 캐시에 남기지 않는다.
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                // 브라우저가 내용을 추측해 다른 형식으로 해석하지 못하게 한다.
                .header("X-Content-Type-Options", "nosniff")
                .body(image.content());
    }

    /**
     * 서명은 imageId에 묶여 있으므로 필터를 통과했다면 경로와 일치한다. 그럼에도
     * 확인하는 이유는, 앞으로 필터가 바뀌더라도 다른 이미지의 토큰으로 열리는 일이
     * 없도록 컨트롤러에서 한 번 더 못 박기 위해서다.
     */
    private static void requireTokenBoundTo(
            Authentication authentication,
            UUID imageId
    ) {
        if (authentication == null
                || !imageId.equals(authentication.getPrincipal())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }
}
