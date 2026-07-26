package com.gilbom.security.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.api.ErrorResponse;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        write(request, response, errorCode, errorCode.defaultMessage());
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode,
            String safeMessage
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        String requestId = RequestIdFilter.getOrCreateRequestId(request);
        ErrorResponse body = ErrorResponse.of(
                errorCode,
                safeMessage,
                request.getRequestURI(),
                requestId,
                List.of()
        );

        // Security filter 오류도 ControllerAdvice 오류와 동일한 외부 계약을 사용한다.
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(RequestIdFilter.HEADER_NAME, requestId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
