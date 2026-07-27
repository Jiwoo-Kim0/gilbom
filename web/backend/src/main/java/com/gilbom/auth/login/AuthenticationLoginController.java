package com.gilbom.auth.login;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationLoginController {

    private final AuthenticationLoginService loginService;
    private final InitialPasswordChangeService passwordChangeService;

    public AuthenticationLoginController(
            AuthenticationLoginService loginService,
            InitialPasswordChangeService passwordChangeService
    ) {
        this.loginService = loginService;
        this.passwordChangeService = passwordChangeService;
    }

    @PostMapping(
            path = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<LoginChallengeResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(loginService.login(request, servletRequest));
    }

    @PostMapping(
            path = "/password/initial-change",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<LoginChallengeResponse> changeInitialPassword(
            @Valid @RequestBody InitialPasswordChangeRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(passwordChangeService.change(request, servletRequest));
    }
}
