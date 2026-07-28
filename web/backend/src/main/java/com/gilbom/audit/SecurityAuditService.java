package com.gilbom.audit;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.gilbom.common.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class SecurityAuditService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SecurityAuditService.class);
    private static final Set<String> DENIAL_FIELDS = Set.of(
            "httpMethod",
            "requestPath",
            "denialType"
    );
    private static final int MAXIMUM_PATH_LENGTH = 500;

    private final AuditLogService auditLogService;

    public SecurityAuditService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public void recordAccessDenied(
            HttpServletRequest request,
            Authentication authentication
    ) {
        recordSafely(
                request,
                authentication,
                AuditAction.ACCESS_DENIED,
                "AUTHORIZATION"
        );
    }

    public void recordCsrfFailed(
            HttpServletRequest request,
            Authentication authentication
    ) {
        recordSafely(
                request,
                authentication,
                AuditAction.CSRF_FAILED,
                "CSRF"
        );
    }

    private void recordSafely(
            HttpServletRequest request,
            Authentication authentication,
            AuditAction action,
            String denialType
    ) {
        try {
            UUID actorUserId = authenticatedUserId(authentication);
            auditLogService.appendBestEffort(new AuditLogRecord(
                    AuditRequestContext.from(request),
                    actorUserId,
                    AuditActorType.USER,
                    action.databaseValue(),
                    AuditTargetType.REQUEST,
                    null,
                    null,
                    AuditSnapshot.allow(
                            DENIAL_FIELDS,
                            Map.of(
                                    "httpMethod", request.getMethod(),
                                    "requestPath", boundedPath(
                                            request.getRequestURI()
                                    ),
                                    "denialType", denialType
                            )
                    ),
                    null,
                    AuditResult.DENIED
            ));
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Security denial audit was unavailable. requestId={}",
                    RequestIdFilter.getOrCreateRequestId(request)
            );
        }
    }

    private static UUID authenticatedUserId(
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String principalName = authentication.getName();
        if (principalName == null || principalName.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(principalName);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String boundedPath(String path) {
        if (path == null) {
            return "";
        }
        return path.substring(
                0,
                Math.min(path.length(), MAXIMUM_PATH_LENGTH)
        );
    }
}
