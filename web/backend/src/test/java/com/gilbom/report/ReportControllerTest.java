package com.gilbom.report;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.report.ReportCommandService.CreatedApiReport;
import com.gilbom.report.ReportDtos.PageResponse;
import com.gilbom.report.ReportDtos.ReportDetailResponse;
import com.gilbom.report.ReportDtos.ReportListItemResponse;
import com.gilbom.report.ReportDtos.ReportVersionResponse;
import com.gilbom.report.ReportDtos.ReportVersionSummaryResponse;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = ReportController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class ReportControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000081");
    private static final UUID REPORT_ID =
            UUID.fromString("28000000-0000-4000-8000-000000000081");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("38000000-0000-4000-8000-000000000081");
    private static final SimpleGrantedAuthority REPORT_VIEW =
            new SimpleGrantedAuthority("REPORT_VIEW");
    private static final SimpleGrantedAuthority REPORT_CREATE =
            new SimpleGrantedAuthority("REPORT_CREATE");
    private static final SimpleGrantedAuthority REPORT_UPDATE =
            new SimpleGrantedAuthority("REPORT_UPDATE");
    private static final SimpleGrantedAuthority REPORT_APPROVE =
            new SimpleGrantedAuthority("REPORT_APPROVE");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportQueryService queryService;

    @MockitoBean
    private ReportCommandService commandService;

    @MockitoBean
    private SessionSecurityStateRepository
            sessionSecurityStateRepository;

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
                        0)));
    }

    @Test
    void listsReportsWithViewPermission() throws Exception {
        when(queryService.findReports(
                eq(USER_ID), eq(0), eq(20), any()))
                .thenReturn(new PageResponse<>(
                        List.of(detail().report()),
                        0,
                        20,
                        1,
                        1));

        mockMvc.perform(get("/api/v1/reports")
                        .param("search", "주간")
                        .param("statuses", "DRAFT")
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath(
                        "$.content[0].reportId",
                        is(REPORT_ID.toString())))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void requiresReportViewPermissionAtApiBoundary()
            throws Exception {
        mockMvc.perform(get("/api/v1/reports")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath(
                        "$.code",
                        is("REPORT_ACCESS_DENIED")));

        verifyNoInteractions(queryService);
    }

    @Test
    void createsManualReportWithRequiredHeaderAndCsrf()
            throws Exception {
        when(commandService.create(
                eq(USER_ID),
                eq(IDEMPOTENCY_KEY),
                any(),
                any()))
                .thenReturn(new CreatedApiReport(
                        detail(),
                        true));

        mockMvc.perform(post("/api/v1/reports")
                        .header(
                                "Idempotency-Key",
                                IDEMPOTENCY_KEY)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_CREATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/reports/" + REPORT_ID))
                .andExpect(jsonPath(
                        "$.reportId",
                        is(REPORT_ID.toString())))
                .andExpect(jsonPath(
                        "$.currentVersion.versionNo",
                        is(1)));
    }

    @Test
    void routesStrictLlmCreateRequestWithoutClientContent()
            throws Exception {
        when(commandService.create(
                eq(USER_ID),
                eq(IDEMPOTENCY_KEY),
                any(),
                any()))
                .thenReturn(new CreatedApiReport(
                        detail(),
                        true));

        mockMvc.perform(post("/api/v1/reports")
                        .header(
                                "Idempotency-Key",
                                IDEMPOTENCY_KEY)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_CREATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "generationMethod": "LLM",
                                  "reportType": "PERIODIC",
                                  "periodFrom":
                                    "2026-07-01T00:00:00Z",
                                  "periodTo":
                                    "2026-07-08T00:00:00Z",
                                  "filterCriteria": {
                                    "defectTypes": ["DAMAGED"]
                                  }
                                }
                                """))
                .andExpect(status().isCreated());

        verify(commandService).create(
                eq(USER_ID),
                eq(IDEMPOTENCY_KEY),
                argThat(request ->
                        request instanceof ReportDtos
                                .LlmReportCreateRequest),
                any());
    }

    @Test
    void rejectsClientSuppliedContentOnLlmCreate()
            throws Exception {
        mockMvc.perform(post("/api/v1/reports")
                        .header(
                                "Idempotency-Key",
                                IDEMPOTENCY_KEY)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_CREATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "generationMethod": "LLM",
                                  "reportType": "PERIODIC",
                                  "periodFrom":
                                    "2026-07-01T00:00:00Z",
                                  "periodTo":
                                    "2026-07-08T00:00:00Z",
                                  "content": "사용자 주입 본문"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(commandService);
    }

    @Test
    void rejectsCreateWithoutIdempotencyKey()
            throws Exception {
        mockMvc.perform(post("/api/v1/reports")
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_CREATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code",
                        is("COMMON_INVALID_REQUEST")));

        verifyNoInteractions(commandService);
    }

    @Test
    void rejectsCreateWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/reports")
                        .header(
                                "Idempotency-Key",
                                IDEMPOTENCY_KEY)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_CREATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(commandService);
    }

    @Test
    void updatesReportAndRequiresExpectedVersion()
            throws Exception {
        when(commandService.update(
                eq(USER_ID),
                eq(REPORT_ID),
                any(),
                any()))
                .thenReturn(detail());

        mockMvc.perform(patch(
                        "/api/v1/reports/{reportId}",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_UPDATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersionNo": 1,
                                  "title": "수정 보고서"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.reportStatus",
                        is("DRAFT")));

        mockMvc.perform(patch(
                        "/api/v1/reports/{reportId}",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_UPDATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수정 보고서"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code",
                        is("COMMON_VALIDATION_FAILED")));
    }

    @Test
    void mapsVersionConflictWithoutReportContent()
            throws Exception {
        when(commandService.update(
                eq(USER_ID),
                eq(REPORT_ID),
                any(),
                any()))
                .thenThrow(new BusinessException(
                        ErrorCode.REPORT_STATE_CONFLICT));

        mockMvc.perform(patch(
                        "/api/v1/reports/{reportId}",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_UPDATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersionNo": 1,
                                  "content": "민감한 보고서 본문"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(
                        "$.code",
                        is("REPORT_STATE_CONFLICT")))
                .andExpect(jsonPath("$.details", hasSize(0)))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath(
                        "$.currentVersionNo").doesNotExist());
    }

    @Test
    void softDeletesWithExpectedVersion() throws Exception {
        mockMvc.perform(delete(
                        "/api/v1/reports/{reportId}",
                        REPORT_ID)
                        .param("expectedVersionNo", "1")
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_UPDATE))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void submitsDraftWithExpectedVersionAndUpdatePermission()
            throws Exception {
        when(commandService.submit(
                eq(USER_ID),
                eq(REPORT_ID),
                any(),
                any()))
                .thenReturn(detail());

        mockMvc.perform(post(
                        "/api/v1/reports/{reportId}/submit",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_UPDATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersionNo":1}
                                """))
                .andExpect(status().isOk());

        verify(commandService).submit(
                eq(USER_ID),
                eq(REPORT_ID),
                argThat(request ->
                        request.expectedVersionNo() == 1),
                any());
    }

    @Test
    void approvesAndRejectsOnlyWithApprovePermission()
            throws Exception {
        when(commandService.approve(
                eq(USER_ID),
                eq(REPORT_ID),
                any(),
                any()))
                .thenReturn(detail());
        when(commandService.reject(
                eq(USER_ID),
                eq(REPORT_ID),
                any(),
                any()))
                .thenReturn(detail());

        mockMvc.perform(post(
                        "/api/v1/reports/{reportId}/approve",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_APPROVE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersionNo": 1,
                                  "reason": "검토 완료"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                        "/api/v1/reports/{reportId}/reject",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_APPROVE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersionNo": 1,
                                  "reason": "통계를 보완하세요."
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                        "/api/v1/reports/{reportId}/approve",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_UPDATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersionNo":1}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath(
                        "$.code",
                        is("REPORT_ACCESS_DENIED")));
    }

    @Test
    void rejectsBlankRejectionReasonAtApiBoundary()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/reports/{reportId}/reject",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_APPROVE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersionNo": 1,
                                  "reason": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code",
                        is("COMMON_VALIDATION_FAILED")));

        verifyNoInteractions(commandService);
    }

    @Test
    void readsVersionListAndSpecificVersion()
            throws Exception {
        ReportVersionResponse version =
                detail().currentVersion();
        when(queryService.findVersions(
                USER_ID,
                REPORT_ID,
                0,
                20))
                .thenReturn(new PageResponse<>(
                        List.of(version.version()),
                        0,
                        20,
                        1,
                        1));
        when(queryService.findVersion(
                USER_ID,
                REPORT_ID,
                1))
                .thenReturn(version);

        mockMvc.perform(get(
                        "/api/v1/reports/{reportId}/versions",
                        REPORT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get(
                        "/api/v1/reports/{reportId}/versions/{versionNo}",
                        REPORT_ID,
                        1)
                        .with(user(USER_ID.toString())
                                .authorities(REPORT_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo", is(1)))
                .andExpect(jsonPath(
                        "$.generationMethod",
                        is("MANUAL")));
    }

    private static String createBody() {
        return """
                {
                  "generationMethod": "MANUAL",
                  "reportType": "PERIODIC",
                  "title": "주간 보고서",
                  "summary": "주간 요약",
                  "periodFrom": "2026-07-01T00:00:00Z",
                  "periodTo": "2026-07-07T23:59:59Z",
                  "content": "보고서 본문",
                  "statistics": {}
                }
                """;
    }

    private static ReportDetailResponse detail() {
        Instant createdAt =
                Instant.parse("2026-07-30T00:00:00Z");
        ReportListItemResponse item =
                new ReportListItemResponse(
                        REPORT_ID,
                        "RPT-TEST-081",
                        "주간 보고서",
                        "주간 요약",
                        ReportType.PERIODIC,
                        ReportStatus.DRAFT,
                        ReportGenerationMethod.MANUAL,
                        Instant.parse(
                                "2026-07-01T00:00:00Z"),
                        Instant.parse(
                                "2026-07-07T23:59:59Z"),
                        1,
                        null,
                        ReportCreatorType.USER,
                        USER_ID,
                        "보고서 작성자",
                        USER_ID,
                        "보고서 작성자",
                        null,
                        null,
                        null,
                        createdAt,
                        createdAt);
        ReportVersionSummaryResponse summary =
                new ReportVersionSummaryResponse(
                        REPORT_ID,
                        1,
                        "주간 보고서",
                        "주간 요약",
                        item.periodFrom(),
                        item.periodTo(),
                        ReportGenerationMethod.MANUAL,
                        null,
                        null,
                        null,
                        null,
                        ReportCreatorType.USER,
                        USER_ID,
                        "보고서 작성자",
                        createdAt);
        return new ReportDetailResponse(
                item,
                Map.of(),
                new ReportVersionResponse(
                        summary,
                        "보고서 본문",
                        Map.of(),
                        ReportContent.emptyEvidence()),
                null,
                null);
    }
}
