package com.gilbom.robot.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.credential.MqttCredentialIssuanceService;
import com.gilbom.robot.data.api.response.MqttCredentialIssuanceResponse;
import com.gilbom.robot.data.application.result.IssuedMqttCredential;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/robots/{robotId}/credentials")
public class MqttCredentialIssuanceController {

    private final MqttCredentialIssuanceService issuanceService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public MqttCredentialIssuanceController(
            MqttCredentialIssuanceService issuanceService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.issuanceService = issuanceService;
        this.userIdResolver = userIdResolver;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROBOT_REGISTER')")
    public ResponseEntity<MqttCredentialIssuanceResponse> issue(
            @PathVariable UUID robotId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        UUID requestId = UUID.fromString(
                RequestIdFilter.getOrCreateRequestId(servletRequest));
        IssuedMqttCredential credential = issuanceService.issue(
                robotId,
                userIdResolver.require(authentication),
                idempotencyKey,
                requestId);

        MqttCredentialIssuanceResponse response =
                new MqttCredentialIssuanceResponse(
                        credential.credentialId(),
                        credential.credentialType(),
                        new MqttCredentialIssuanceResponse.MqttConnection(
                                credential.brokerUri(),
                                credential.username(),
                                credential.password(),
                                credential.clientId()),
                        credential.expiresAt());
        URI location = URI.create(
                "/api/v1/robots/" + robotId
                        + "/credentials/" + credential.credentialId());

        return ResponseEntity
                .created(location)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }
}
