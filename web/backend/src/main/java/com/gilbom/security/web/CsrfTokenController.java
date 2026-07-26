package com.gilbom.security.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class CsrfTokenController {

    @GetMapping("/csrf-token")
    public ResponseEntity<CsrfTokenResponse> csrfToken(CsrfToken csrfToken) {
        // 토큰을 읽는 시점에 익명 세션과 결합해 만들고 브라우저 저장소 캐시를 금지한다.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken()));
    }

    public record CsrfTokenResponse(String headerName, String token) {
    }
}
