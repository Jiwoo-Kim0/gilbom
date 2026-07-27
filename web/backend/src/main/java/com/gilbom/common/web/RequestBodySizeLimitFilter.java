package com.gilbom.common.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.api.ErrorResponse;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 프록시를 우회한 직접 요청과 Content-Length가 없는 streaming 요청에도 전역 본문 상한을 적용한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final long maxBodyBytes;

    public RequestBodySizeLimitFilter(
            ObjectMapper objectMapper,
            @Value("${gilbom.http.max-request-body-size:1MB}") DataSize maxBodySize) {
        long configuredBytes = maxBodySize.toBytes();
        if (configuredBytes <= 0) {
            throw new IllegalArgumentException(
                    "gilbom.http.max-request-body-size must be greater than zero");
        }
        this.objectMapper = objectMapper;
        this.maxBodyBytes = configuredBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            writePayloadTooLarge(request, response);
            return;
        }

        HttpServletRequest limitedRequest =
                new SizeLimitedRequestWrapper(request, maxBodyBytes);
        try {
            filterChain.doFilter(limitedRequest, response);
        } catch (ServletException | IOException exception) {
            if (!response.isCommitted() && RequestBodyTooLargeException.isCausedBy(exception)) {
                writePayloadTooLarge(request, response);
                return;
            }
            throw exception;
        }
    }

    private void writePayloadTooLarge(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        String requestId = RequestIdFilter.getOrCreateRequestId(request);
        ErrorCode errorCode = ErrorCode.COMMON_PAYLOAD_TOO_LARGE;
        ErrorResponse body = ErrorResponse.of(
                errorCode,
                errorCode.defaultMessage(),
                request.getRequestURI(),
                requestId,
                List.of());

        // 이미 설정된 request-id와 보안 헤더는 유지하면서 작성 중인 body만 비운다.
        response.resetBuffer();
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(RequestIdFilter.HEADER_NAME, requestId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static final class SizeLimitedRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBodyBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private SizeLimitedRequestWrapper(HttpServletRequest request, long maxBodyBytes) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called");
            }
            if (inputStream == null) {
                inputStream = new SizeLimitedServletInputStream(
                        super.getInputStream(),
                        maxBodyBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (inputStream != null) {
                throw new IllegalStateException("getInputStream() has already been called");
            }
            if (reader == null) {
                String encoding = getCharacterEncoding();
                if (encoding == null) {
                    encoding = StandardCharsets.UTF_8.name();
                }
                reader = new BufferedReader(new InputStreamReader(getInputStreamInternal(), encoding));
            }
            return reader;
        }

        private ServletInputStream getInputStreamInternal() throws IOException {
            if (inputStream == null) {
                inputStream = new SizeLimitedServletInputStream(
                        super.getInputStream(),
                        maxBodyBytes);
            }
            return inputStream;
        }
    }

    private static final class SizeLimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBodyBytes;
        private long bytesRead;

        private SizeLimitedServletInputStream(
                ServletInputStream delegate,
                long maxBodyBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                recordBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                recordBytes(count);
            }
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = delegate.skip(count);
            if (skipped > 0) {
                recordBytes(skipped);
            }
            return skipped;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void recordBytes(long count) throws RequestBodyTooLargeException {
            bytesRead += count;
            if (bytesRead > maxBodyBytes) {
                throw new RequestBodyTooLargeException();
            }
        }
    }
}
