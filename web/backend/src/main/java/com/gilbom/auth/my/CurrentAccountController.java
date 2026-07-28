package com.gilbom.auth.my;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.mfa.AuthenticatedUserResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@PreAuthorize("isAuthenticated()")
public class CurrentAccountController {

    private final CurrentAccountService accountService;
    private final MyPasswordChangeService passwordChangeService;

    public CurrentAccountController(
            CurrentAccountService accountService,
            MyPasswordChangeService passwordChangeService
    ) {
        this.accountService = accountService;
        this.passwordChangeService = passwordChangeService;
    }

    @GetMapping(
            path = "/api/v1/auth/me",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AuthenticatedUserResponse> currentUser(
            Authentication authentication,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(accountService.currentUser(authentication, request));
    }

    @GetMapping(
            path = "/api/v1/my/profile",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<MyProfileResponse> profile(
            Authentication authentication,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(accountService.profile(authentication, request));
    }

    @PatchMapping(
            path = "/api/v1/my/password",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody MyPasswordChangeRequest changeRequest,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        passwordChangeService.change(
                changeRequest,
                authentication,
                request,
                response
        );
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
