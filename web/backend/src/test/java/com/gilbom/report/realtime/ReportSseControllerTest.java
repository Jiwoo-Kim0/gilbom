package com.gilbom.report.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.report.ReportAccessService;
import com.gilbom.report.ReportStatus;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = ReportSseController.class)
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class,
        ReportSseService.class,
        ReportSseControllerTest.FixedClockConfiguration.class
})
class ReportSseControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000095");
    private static final UUID REPORT_ID =
            UUID.fromString("28000000-0000-4000-8000-000000000095");
    private static final Instant NOW =
            Instant.parse("2026-07-30T10:00:00Z");
    private static final SimpleGrantedAuthority REPORT_VIEW =
            new SimpleGrantedAuthority("REPORT_VIEW");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReportSseService sseService;

    @MockitoBean
    private ReportAccessService accessService;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @BeforeEach
    void setUpSession() {
        when(sessionStateRepository.findBySessionId(
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0)));
    }

    @Test
    void opensAUserScopedNoStoreEventStream() throws Exception {
        MvcResult result = subscribe(REPORT_ID);

        assertThat(result.getResponse().getContentAsString())
                .contains("event:CONNECTED");
        verify(accessService).requireReport(
                USER_ID,
                REPORT_ID,
                ReportAccessService.VIEW);
        assertThat(sseService.subscriptionCount()).isEqualTo(1);
    }

    @Test
    void sendsOnlyTheMinimumReportChangePayload() throws Exception {
        when(accessService.canReceiveEvent(USER_ID, REPORT_ID))
                .thenReturn(true);
        MvcResult result = subscribe(REPORT_ID);

        sseService.publish(new ReportRealtimeEvent(
                REPORT_ID,
                ReportStatus.APPROVED,
                4,
                false,
                NOW));

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("event:report.updated")
                .contains("\"reportId\":\"" + REPORT_ID + "\"")
                .contains("\"reportStatus\":\"APPROVED\"")
                .contains("\"currentVersionNo\":4")
                .contains("\"deleted\":false")
                .doesNotContain("\"title\"")
                .doesNotContain("\"body\"")
                .doesNotContain("\"statistics\"")
                .doesNotContain("\"createdBy\"")
                .doesNotContain("\"approvedBy\"");
    }

    @Test
    void doesNotSendAnEventOutsideCurrentObjectScope()
            throws Exception {
        when(accessService.canReceiveEvent(USER_ID, REPORT_ID))
                .thenReturn(false);
        MvcResult result = subscribe(null);

        sseService.publish(new ReportRealtimeEvent(
                REPORT_ID,
                ReportStatus.IN_REVIEW,
                2,
                false,
                NOW));

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("event:report.updated");
    }

    @Test
    void writesAHeartbeatComment() throws Exception {
        MvcResult result = subscribe(REPORT_ID);

        sseService.heartbeat();

        assertThat(result.getResponse().getContentAsString())
                .contains(":heartbeat");
    }

    @Test
    void rejectsUnauthenticatedStreamRequest() throws Exception {
        mockMvc.perform(get("/api/v1/stream/reports")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString(
                        "\"code\":\"AUTH_UNAUTHENTICATED\"")));
    }

    @Test
    void requiresReportViewPermission() throws Exception {
        mockMvc.perform(get("/api/v1/stream/reports")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath(
                        "$.code",
                        is("REPORT_ACCESS_DENIED")));
    }

    private MvcResult subscribe(UUID reportId) throws Exception {
        var requestBuilder = get("/api/v1/stream/reports")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .with(user(USER_ID.toString())
                        .authorities(REPORT_VIEW));
        if (reportId != null) {
            requestBuilder.param("reportId", reportId.toString());
        }
        return mockMvc.perform(requestBuilder)
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE,
                        containsString(
                                MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")))
                .andReturn();
    }

    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
