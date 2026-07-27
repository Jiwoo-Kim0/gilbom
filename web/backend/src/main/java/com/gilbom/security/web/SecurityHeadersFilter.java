package com.gilbom.security.web;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 인증 여부나 MVC 진입 여부와 관계없이 모든 백엔드 응답에 공통 브라우저 보안 정책을 적용한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String CONTENT_SECURITY_POLICY = String.join(" ",
            "default-src 'self';",
            "base-uri 'self';",
            "object-src 'none';",
            "frame-ancestors 'none';",
            "form-action 'self';",
            "script-src 'self';",
            "style-src 'self';",
            "img-src 'self' data: blob:;",
            "font-src 'self';",
            "connect-src 'self';",
            "worker-src 'self' blob:;");

    public static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    private static final String HSTS_POLICY = "max-age=31536000";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);

        // HSTS는 평문 HTTP에서 신뢰되지 않으므로 TLS 요청에서만 애플리케이션이 추가한다.
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", HSTS_POLICY);
        }

        filterChain.doFilter(request, response);
    }
}
