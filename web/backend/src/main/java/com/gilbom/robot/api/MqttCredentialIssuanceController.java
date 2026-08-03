package com.gilbom.robot.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.credential.MqttCredentialIssuanceService;
import com.gilbom.robot.credential.MqttCredentialRegistryService;
import com.gilbom.robot.data.api.request.MqttCredentialRegistryReconciliationRequest;
import com.gilbom.robot.data.api.request.MqttCredentialRevocationRequest;
import com.gilbom.robot.data.api.response.MqttCredentialIssuanceResponse;
import com.gilbom.robot.data.api.response.MqttCredentialListResponse;
import com.gilbom.robot.data.api.response.MqttCredentialRegistryStatusResponse;
import com.gilbom.robot.data.api.response.MqttCredentialResponse;
import com.gilbom.robot.data.application.result.IssuedMqttCredential;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/robots/{robotId}/credentials")
public class MqttCredentialIssuanceController {

    private final MqttCredentialIssuanceService issuanceService;
    private final MqttCredentialRegistryService registryService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public MqttCredentialIssuanceController(
            MqttCredentialIssuanceService issuanceService,
            MqttCredentialRegistryService registryService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.issuanceService = issuanceService;
        this.registryService = registryService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROBOT_REGISTER')")
    public ResponseEntity<MqttCredentialListResponse> list(
            @PathVariable UUID robotId,
            Authentication authentication
    ) {
        MqttCredentialListResponse response =
                new MqttCredentialListResponse(
                        issuanceService.list(
                                robotId,
                                userIdResolver.require(authentication)));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/registry-status")
    @PreAuthorize("hasAuthority('ROBOT_REGISTER')")
    public ResponseEntity<MqttCredentialRegistryStatusResponse>
            registryStatus(
                    @PathVariable UUID robotId,
                    Authentication authentication
            ) {
        MqttCredentialRegistryStatusResponse response =
                registryService.status(
                        robotId,
                        userIdResolver.require(authentication));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/registry-reconcile")
    @PreAuthorize("hasAuthority('ROBOT_REGISTER')")
    public ResponseEntity<MqttCredentialRegistryStatusResponse>
            reconcileRegistry(
                    @PathVariable UUID robotId,
                    @Valid @RequestBody
                    MqttCredentialRegistryReconciliationRequest request,
                    Authentication authentication,
                    HttpServletRequest servletRequest
            ) {
        UUID requestId = UUID.fromString(
                RequestIdFilter.getOrCreateRequestId(servletRequest));
        MqttCredentialRegistryStatusResponse response =
                registryService.reconcile(
                        robotId,
                        userIdResolver.require(authentication),
                        request.reason(),
                        requestId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
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
                        credential.robotId(),
                        credential.mqttId(),
                        credential.credentialId(),
                        credential.credentialType(),
                        new MqttCredentialIssuanceResponse.MqttConnection(
                                credential.brokerUri(),
                                credential.username(),
                                credential.password(),
                                credential.clientId()),
                        new MqttCredentialIssuanceResponse.ImageUpload(
                                credential.imageUploadUrl(),
                                credential.imageUploadApiKey()),
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

    @PostMapping("/{credentialId}/revoke")
    @PreAuthorize("hasAuthority('ROBOT_REGISTER')")
    public ResponseEntity<MqttCredentialResponse> revoke(
            @PathVariable UUID robotId,
            @PathVariable UUID credentialId,
            @Valid @RequestBody MqttCredentialRevocationRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        UUID requestId = UUID.fromString(
                RequestIdFilter.getOrCreateRequestId(servletRequest));
        MqttCredentialResponse response = issuanceService.revoke(
                robotId,
                credentialId,
                userIdResolver.require(authentication),
                request.reason(),
                requestId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
