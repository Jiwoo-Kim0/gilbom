package com.gilbom.auth.registration;

import com.gilbom.audit.AuditRequestContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class PublicRegistrationController {

    private final PublicRegistrationService registrationService;

    public PublicRegistrationController(
            PublicRegistrationService registrationService
    ) {
        this.registrationService = registrationService;
    }

    @PostMapping(
            path = "/email-availability",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<EmailAvailabilityResponse> checkEmailAvailability(
            @Valid @RequestBody EmailAvailabilityRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(registrationService.checkEmailAvailability(request.email()));
    }

    @PostMapping(
            path = "/registrations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PublicRegistrationResponse> register(
            @Valid @RequestBody PublicRegistrationRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(registrationService.register(
                        request,
                        AuditRequestContext.from(servletRequest)
                ));
    }
}
