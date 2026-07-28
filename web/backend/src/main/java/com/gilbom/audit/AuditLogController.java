package com.gilbom.audit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.AuthenticatedUserIdResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService queryService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public AuditLogController(
            AuditLogQueryService queryService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.queryService = queryService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_LOG_VIEW')")
    public AuditLogListResponse find(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID requestId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,79}$")
            String actionType,
            @RequestParam(required = false)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$")
            String targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) AuditResult result,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant createdTo,
            Authentication authentication,
            HttpServletRequest request
    ) {
        return queryService.find(
                new AuditLogFilter(
                        page,
                        size,
                        requestId,
                        actorUserId,
                        actionType,
                        targetType,
                        targetId,
                        result,
                        createdFrom,
                        createdTo
                ),
                userIdResolver.require(authentication),
                AuditRequestContext.from(request)
        );
    }
}
