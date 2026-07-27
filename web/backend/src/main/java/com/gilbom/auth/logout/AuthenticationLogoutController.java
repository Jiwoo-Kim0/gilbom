package com.gilbom.auth.logout;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
public class AuthenticationLogoutController {

    private final AuthenticationLogoutService logoutService;

    public AuthenticationLogoutController(
            AuthenticationLogoutService logoutService
    ) {
        this.logoutService = logoutService;
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        logoutService.logout(authentication, request, response);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
