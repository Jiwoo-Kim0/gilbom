package com.gilbom.common.api;

import java.time.Instant;
import java.util.List;

import com.gilbom.common.error.ErrorCode;

/**
 * 모든 REST 오류가 공유하는 안정적인 외부 계약이다.
 *
 * 성공 응답은 HTTP 상태와 실제 DTO를 그대로 사용하고, 오류만 이 구조로 통일한다.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String requestId,
        List<ValidationErrorDetail> details) {

    public ErrorResponse {
        // 클라이언트가 null과 빈 목록을 따로 분기하지 않도록 항상 배열로 직렬화한다.
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static ErrorResponse of(
            ErrorCode errorCode,
            String message,
            String path,
            String requestId,
            List<ValidationErrorDetail> details) {
        return new ErrorResponse(
                Instant.now(),
                errorCode.httpStatus().value(),
                errorCode.name(),
                message,
                path,
                requestId,
                details);
    }
}
