package com.gilbom.audit;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;

import com.gilbom.common.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;

public record AuditRequestContext(
        UUID requestId,
        String ipAddress,
        String userAgent
) {

    private static final Pattern IP_LITERAL =
            Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final int MAXIMUM_USER_AGENT_LENGTH = 500;

    public AuditRequestContext {
        if (ipAddress != null && !IP_LITERAL.matcher(ipAddress).matches()) {
            throw new IllegalArgumentException(
                    "IP address must be a canonical address literal");
        }
        if (userAgent != null
                && userAgent.length() > MAXIMUM_USER_AGENT_LENGTH) {
            throw new IllegalArgumentException("User-Agent is too long");
        }
    }

    public static AuditRequestContext from(HttpServletRequest request) {
        return new AuditRequestContext(
                UUID.fromString(RequestIdFilter.getOrCreateRequestId(request)),
                safeIpAddress(request.getRemoteAddr()),
                sanitizeUserAgent(request.getHeader(HttpHeaders.USER_AGENT))
        );
    }

    public static AuditRequestContext requestIdOnly(UUID requestId) {
        return new AuditRequestContext(requestId, null, null);
    }

    public static AuditRequestContext system() {
        return new AuditRequestContext(null, null, null);
    }

    private static String safeIpAddress(String value) {
        if (value == null || !IP_LITERAL.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    private static String sanitizeUserAgent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        StringBuilder sanitized = new StringBuilder(
                Math.min(value.length(), MAXIMUM_USER_AGENT_LENGTH)
        );
        for (int offset = 0;
                offset < value.length()
                        && sanitized.length() < MAXIMUM_USER_AGENT_LENGTH;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            if (sanitized.length() + Character.charCount(codePoint)
                    > MAXIMUM_USER_AGENT_LENGTH) {
                break;
            }
            sanitized.appendCodePoint(codePoint);
        }
        return sanitized.isEmpty() ? null : sanitized.toString();
    }
}
