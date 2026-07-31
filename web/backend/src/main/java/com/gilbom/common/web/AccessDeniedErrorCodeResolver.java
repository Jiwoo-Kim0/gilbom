package com.gilbom.common.web;

import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 인증된 사용자의 기능 권한 거부를 공개 API 도메인 오류로 변환한다.
 */
public final class AccessDeniedErrorCodeResolver {

    private static final String REPORTS_PATH = "/api/v1/reports";
    private static final String REPORT_STREAM_PATH =
            "/api/v1/stream/reports";

    private AccessDeniedErrorCodeResolver() {
    }

    public static ErrorCode resolve(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (REPORTS_PATH.equals(path)
                || path.startsWith(REPORTS_PATH + "/")
                || REPORT_STREAM_PATH.equals(path)) {
            return ErrorCode.REPORT_ACCESS_DENIED;
        }
        return ErrorCode.AUTH_FORBIDDEN;
    }
}
