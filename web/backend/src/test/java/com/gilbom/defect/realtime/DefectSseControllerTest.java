package com.gilbom.defect.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.List;
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
import com.gilbom.defect.DefectAccessService;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

/**
 * {@code GET /api/v1/stream/defects}의 HTTP 계약을 검증한다.
 *
 * <p>운영에서 이 스트림에 붙으려면 로그인·MFA를 통과한 세션이 필요해 수동 확인이
 * 번거롭다. MockMvc가 인증을 대신 세워주므로 같은 경로를 재현 가능하게 검사한다.
 *
 * <p>{@code RobotSseControllerTest}와 달리 SSE 서비스를 목킹하지 않고 실제 빈을
 * 사용한다. 계약에서 프론트가 의존하는 값은 이벤트 <em>이름</em>과 직렬화된 JSON
 * 이므로, 서비스를 목으로 대체하면 정작 검증하려는 부분이 빠진다.
 */
@WebMvcTest(controllers = DefectSseController.class)
// DefectSseService는 싱글턴이고 구독을 내부 Map에 보관한다. 컨텍스트를 재사용하면
// 앞선 테스트의 구독이 살아남아 subscriptionCount와 publish 대상이 오염된다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class,
        DefectSseService.class,
        DefectSseControllerTest.FixedClockConfiguration.class
})
class DefectSseControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000061");
    private static final UUID DEFECT_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000061");
    private static final UUID PATROL_AREA_ID =
            UUID.fromString("3c9a2f4e-1c4d-4a5b-9c8d-000000000061");
    private static final UUID OTHER_AREA_ID =
            UUID.fromString("3c9a2f4e-1c4d-4a5b-9c8d-000000000062");
    private static final SimpleGrantedAuthority DEFECT_STATUS_VIEW =
            new SimpleGrantedAuthority("DEFECT_STATUS_VIEW");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DefectSseService sseService;

    @MockitoBean
    private DefectAccessService accessService;

    @MockitoBean
    private SessionSecurityStateRepository sessionSecurityStateRepository;

    @BeforeEach
    void setUpAuthenticatedSessionState() {
        when(sessionSecurityStateRepository.findBySessionId(
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0
                )));
    }

    @Test
    void opensEventStreamWithContractHeadersAndConnectedEvent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/stream/defects")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE,
                        containsString(MediaType.TEXT_EVENT_STREAM_VALUE)))
                // 스트림 응답이 중간 캐시에 남지 않아야 한다.
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")))
                .andReturn();

        // 구독 즉시 CONNECTED가 실려 나가는지 실제 응답 본문으로 확인한다.
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("event:CONNECTED")
                .contains("\"eventType\":\"CONNECTED\"");
        assertThat(sseService.subscriptionCount()).isEqualTo(1);
    }

    /**
     * 계약 24장의 이벤트명과 payload가 그대로 나가는지 확인한다. 프론트는
     * {@code EventSource.addEventListener("defect.updated")}로 구독하므로 이름이
     * 어긋나면 화면이 조용히 갱신되지 않는다.
     */
    @Test
    void writesContractEventNameAndJsonPayloadToStream() throws Exception {
        when(accessService.canView(USER_ID, DEFECT_ID)).thenReturn(true);

        MvcResult result = mockMvc.perform(get("/api/v1/stream/defects")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(request().asyncStarted())
                .andReturn();

        sseService.publish(DefectRealtimeEvent.updated(
                DEFECT_ID,
                "INC-20260729-000001",
                PATROL_AREA_ID,
                "DAMAGED",
                "HIGH",
                "ACCEPTED",
                127.0276,
                37.4979,
                "NEW",
                "ACCEPTED",
                List.of("workflowStatus"),
                Instant.parse("2026-07-29T02:00:00Z")));

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:defect.updated");
        assertThat(body)
                .contains("\"defectId\":\"" + DEFECT_ID + "\"")
                .contains("\"incidentCode\":\"INC-20260729-000001\"")
                .contains("\"workflowStatus\":\"ACCEPTED\"")
                .contains("\"beforeStatus\":\"NEW\"")
                .contains("\"afterStatus\":\"ACCEPTED\"")
                .contains("\"changedFields\":[\"workflowStatus\"]")
                // 20장 공통 규칙: 좌표는 longitude, latitude 순서
                .contains("\"longitude\":127.0276")
                .contains("\"latitude\":37.4979");
    }

    @Test
    void doesNotWriteEventOutsideObjectScope() throws Exception {
        when(accessService.canView(USER_ID, DEFECT_ID)).thenReturn(false);

        MvcResult result = mockMvc.perform(get("/api/v1/stream/defects")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(request().asyncStarted())
                .andReturn();

        sseService.publish(statusEvent(PATROL_AREA_ID));

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("event:defect.updated");
    }

    @Test
    void appliesPatrolAreaQueryFilterToStream() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/stream/defects")
                                .param("patrolAreaId", PATROL_AREA_ID.toString())
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .with(user(USER_ID.toString())
                                        .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 다른 구역 이벤트는 범위 판정까지 가지 않고 걸러진다.
        sseService.publish(statusEvent(OTHER_AREA_ID));

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("event:defect.updated");
        verifyNoInteractions(accessService);
    }

    @Test
    void rejectsUnauthenticatedStreamRequest() throws Exception {
        mockMvc.perform(get("/api/v1/stream/defects")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString(
                        "\"code\":\"AUTH_UNAUTHENTICATED\"")));

        assertThat(sseService.subscriptionCount()).isZero();
    }

    @Test
    void requiresDefectStatusViewPermissionForStream() throws Exception {
        mockMvc.perform(get("/api/v1/stream/defects")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        assertThat(sseService.subscriptionCount()).isZero();
        verifyNoInteractions(accessService);
    }

    private static DefectRealtimeEvent statusEvent(UUID patrolAreaId) {
        return DefectRealtimeEvent.updated(
                DEFECT_ID,
                "INC-20260729-000001",
                patrolAreaId,
                "DAMAGED",
                "HIGH",
                "ACCEPTED",
                127.0276,
                37.4979,
                "NEW",
                "ACCEPTED",
                List.of("workflowStatus"),
                Instant.parse("2026-07-29T02:00:00Z"));
    }

    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    Instant.parse("2026-07-29T02:00:00Z"),
                    ZoneOffset.UTC);
        }
    }
}
