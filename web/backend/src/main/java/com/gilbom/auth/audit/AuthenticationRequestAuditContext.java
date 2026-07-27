package com.gilbom.auth.audit;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;

import com.gilbom.common.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 인증 감사에 허용된 HTTP metadata만 추출한다.
 *
 * 요청 body, query string, Cookie, Authorization, CSRF와 인증 입력값은 받지 않는다.
 */
public record AuthenticationRequestAuditContext(
        UUID requestId,
        String ipAddress,
        String userAgent
) {

    private static final Pattern IP_LITERAL =
            Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final int MAXIMUM_USER_AGENT_LENGTH = 500;

    public AuthenticationRequestAuditContext {
        if (requestId == null) {
            throw new IllegalArgumentException("Request ID must not be null");
        }
        if (ipAddress != null && !IP_LITERAL.matcher(ipAddress).matches()) {
            throw new IllegalArgumentException(
                    "IP address must be a canonical address literal");
        }
        if (userAgent != null
                && userAgent.length() > MAXIMUM_USER_AGENT_LENGTH) {
            throw new IllegalArgumentException("User-Agent is too long");
        }
    }

    public static AuthenticationRequestAuditContext from(
            HttpServletRequest request
    ) {
        UUID requestId = UUID.fromString(
                RequestIdFilter.getOrCreateRequestId(request)
        );
        return new AuthenticationRequestAuditContext(
                requestId,
                safeIpAddress(request.getRemoteAddr()),
                sanitizeUserAgent(request.getHeader(HttpHeaders.USER_AGENT))
        );
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
