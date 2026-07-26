package com.gilbom.common.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = RequestIdFilter.class.getName() + ".requestId";
    private static final String MDC_KEY = "requestId";

    public static String getOrCreateRequestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(ATTRIBUTE_NAME);
        if (requestId instanceof String value && !value.isBlank()) {
            return value;
        }

        // 필터 밖의 오류 dispatch에서도 공통 응답이 추적 ID를 잃지 않게 한다.
        String generated = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE_NAME, generated);
        return generated;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER_NAME));

        // 헤더·오류 body·로그 MDC가 같은 ID를 사용해 원문 예외 없이도 요청을 추적한다.
        request.setAttribute(ATTRIBUTE_NAME, requestId);
        response.setHeader(HEADER_NAME, requestId);
        MDC.put(MDC_KEY, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String trimmed = candidate.trim();
        try {
            UUID parsed = UUID.fromString(trimmed);
            if (parsed.toString().equalsIgnoreCase(trimmed)) {
                return parsed.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid external request IDs are replaced instead of being reflected.
        }
        return UUID.randomUUID().toString();
    }
}
