package com.gilbom.common.api;

import java.time.Instant;
import java.util.List;

import com.gilbom.common.error.ErrorCode;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String requestId,
        List<ValidationErrorDetail> details) {

    public ErrorResponse {
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
