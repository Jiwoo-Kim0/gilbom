package com.gilbom.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import com.fasterxml.jackson.databind.ObjectMapper;

class RequestBodySizeLimitFilterTest {

    private static final int MAX_BODY_BYTES = 8;

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(
            new ObjectMapper().findAndRegisterModules(),
            DataSize.ofBytes(MAX_BODY_BYTES));

    @Test
    void rejectsDeclaredContentLengthBeforeCallingTheApplication() throws Exception {
        MockHttpServletRequest request = request("123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("COMMON_PAYLOAD_TOO_LARGE");
    }

    @Test
    void countsStreamingBodiesWhenContentLengthIsUnknown() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setMethod("POST");
        request.setRequestURI("/api/v1/test");
        request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (limitedRequest, ignoredResponse) ->
                limitedRequest.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("COMMON_PAYLOAD_TOO_LARGE");
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/test");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
