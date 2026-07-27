package com.gilbom.auth.mfa;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaAuthenticationController {

    private final MfaAuthenticationService authenticationService;

    public MfaAuthenticationController(
            MfaAuthenticationService authenticationService
    ) {
        this.authenticationService = authenticationService;
    }

    @PostMapping(
            path = "/totp/enroll",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<TotpEnrollmentResponse> enroll(
            HttpServletRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authenticationService.enroll(request));
    }

    @PostMapping(
            path = "/totp/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<MfaEnrollmentConfirmationResponse>
            confirmEnrollment(
                    @Valid @RequestBody TotpCodeRequest codeRequest,
                    HttpServletRequest request,
                    HttpServletResponse response
            ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authenticationService.confirmEnrollment(
                        codeRequest,
                        request,
                        response
                ));
    }

    @PostMapping(
            path = "/totp/verify",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AuthenticationResponse> verifyTotp(
            @Valid @RequestBody TotpCodeRequest codeRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authenticationService.verifyTotp(
                        codeRequest,
                        request,
                        response
                ));
    }

    @PostMapping(
            path = "/recovery",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AuthenticationResponse> verifyRecoveryCode(
            @Valid @RequestBody RecoveryCodeRequest codeRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authenticationService.verifyRecoveryCode(
                        codeRequest,
                        request,
                        response
                ));
    }
}
