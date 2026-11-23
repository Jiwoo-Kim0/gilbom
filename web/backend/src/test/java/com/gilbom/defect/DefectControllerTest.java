package com.gilbom.defect;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import com.gilbom.defect.DefectDtos.DefectClusterDetailsResponse;
import com.gilbom.defect.DefectDtos.DefectClusterItemResponse;
import com.gilbom.defect.DefectDtos.DefectExportAcceptedResponse;
import com.gilbom.defect.DefectDtos.DefectListItemResponse;
import com.gilbom.defect.DefectDtos.DefectMapFeature;
import com.gilbom.defect.DefectDtos.DefectMapGeometry;
import com.gilbom.defect.DefectDtos.DefectMapProperties;
import com.gilbom.defect.DefectDtos.DefectMapResponse;
import com.gilbom.defect.DefectDtos.GeoPoint;
import com.gilbom.defect.DefectDtos.PageResponse;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = DefectController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class DefectControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000011");
    private static final UUID DEFECT_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-7e6f5a4b3c2d");
    private static final SimpleGrantedAuthority DEFECT_STATUS_VIEW =
            new SimpleGrantedAuthority("DEFECT_STATUS_VIEW");
    private static final SimpleGrantedAuthority DEFECT_STATUS_UPDATE =
            new SimpleGrantedAuthority("DEFECT_STATUS_UPDATE");
    private static final SimpleGrantedAuthority DEFECT_MERGE =
            new SimpleGrantedAuthority("DEFECT_MERGE");
    private static final SimpleGrantedAuthority DEFECT_EXPORT =
            new SimpleGrantedAuthority("DEFECT_EXPORT");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DefectQueryService queryService;

    @MockitoBean
    private DefectCommandService commandService;

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
    void returnsPagedDefectListWithViewPermission() throws Exception {
        when(queryService.findDefects(
                eq(USER_ID), eq(0), eq(20), eq(null), any()))
                .thenReturn(new PageResponse<>(
                        List.of(listItem()),
                        0,
                        20,
                        1,
                        1));

        mockMvc.perform(get("/api/v1/defects")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].defectId",
                        is(DEFECT_ID.toString())))
                .andExpect(jsonPath("$.content[0].incidentCode",
                        is("INC-20260723-000031")))
                .andExpect(jsonPath("$.content[0].workflowStatus", is("NEW")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void returnsMapAsGeoJsonFeatureCollection() throws Exception {
        when(queryService.findMapDefects(eq(USER_ID), any()))
                .thenReturn(new DefectMapResponse(List.of(new DefectMapFeature(
                        DefectMapGeometry.point(127.0276, 37.4979),
                        new DefectMapProperties(
                                DEFECT_ID,
                                "INC-20260723-000031",
                                "DAMAGED",
                                "HIGH",
                                "NEW",
                                new BigDecimal("0.870"))))));

        mockMvc.perform(get("/api/v1/defects/map")
                        .param("bbox", "126.9,37.4,127.1,37.6")
                        .param("workflowStatuses", "NEW,IN_PROGRESS")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("FeatureCollection")))
                .andExpect(jsonPath("$.features[0].type", is("Feature")))
                .andExpect(jsonPath("$.features[0].geometry.type", is("Point")))
                // 좌표 순서는 [longitude, latitude]다.
                .andExpect(jsonPath("$.features[0].geometry.coordinates[0]",
                        is(127.0276)))
                .andExpect(jsonPath("$.features[0].geometry.coordinates[1]",
                        is(37.4979)))
                .andExpect(jsonPath("$.features[0].properties.defectType",
                        is("DAMAGED")));
    }

    /**
     * 묶음 목록을 결함마다 두 번씩 부르던 것을 한 번으로 모은 지점이다. 10건짜리 묶음
     * 하나에 스무 개가 한꺼번에 나갔고, 그중 하나만 401이어도 화면이 로그인으로 튕겼다.
     */
    @Test
    void returnsClusterDetailsForManyDefectsInOneCall() throws Exception {
        UUID other = UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-7e6f5a4b3c2e");
        when(queryService.findClusterDetails(
                eq(USER_ID), eq(List.of(DEFECT_ID, other))))
                .thenReturn(new DefectClusterDetailsResponse(List.of(
                        new DefectClusterItemResponse(
                                DEFECT_ID,
                                "INC-20260723-000031",
                                "역삼역 3번 출구 앞 보도",
                                "G-01",
                                "/api/v1/images/abc/content?sig=x",
                                Instant.parse("2026-08-07T00:10:00Z")),
                        // 사진이 아직 연결되지 않은 결함은 정상이다. 그 줄만 빈다.
                        new DefectClusterItemResponse(
                                other,
                                "INC-20260723-000032",
                                null,
                                "G-02",
                                null,
                                null))));

        mockMvc.perform(get("/api/v1/defects/cluster-details")
                        .param("defectIds", DEFECT_ID + "," + other)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].incidentCode",
                        is("INC-20260723-000031")))
                .andExpect(jsonPath("$.items[0].locationLabel",
                        is("역삼역 3번 출구 앞 보도")))
                .andExpect(jsonPath("$.items[1].locationLabel").doesNotExist())
                .andExpect(jsonPath("$.items[1].zoneId", is("G-02")))
                .andExpect(jsonPath("$.items[1].imageUrl").doesNotExist());
    }

    @Test
    void rejectsClusterDetailsWithoutDefectIds() throws Exception {
        mockMvc.perform(get("/api/v1/defects/cluster-details")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(queryService);
    }

    /**
     * 상한이 없으면 이 하나의 요청이 만드는 일이 열려 있다. 화면 상한(10)보다 넉넉하되
     * 무제한은 아니다.
     */
    @Test
    void rejectsClusterDetailsBeyondTheBatchLimit() throws Exception {
        String tooMany = java.util.stream.Stream
                .generate(UUID::randomUUID)
                .limit(51)
                .map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(get("/api/v1/defects/cluster-details")
                        .param("defectIds", tooMany)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(queryService);
    }

    @Test
    void requiresDefectStatusViewPermissionForClusterDetails() throws Exception {
        mockMvc.perform(get("/api/v1/defects/cluster-details")
                        .param("defectIds", DEFECT_ID.toString())
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    @Test
    void rejectsUnauthenticatedListRequest() throws Exception {
        mockMvc.perform(get("/api/v1/defects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("AUTH_UNAUTHENTICATED")));

        verifyNoInteractions(queryService);
    }

    @Test
    void requiresDefectStatusViewPermissionToList() throws Exception {
        mockMvc.perform(get("/api/v1/defects")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    @Test
    void deniesStatusChangeWithOnlyViewPermission() throws Exception {
        mockMvc.perform(patch("/api/v1/defects/{defectId}/status", DEFECT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflowStatus":"ACCEPTED","reason":"현장 확인"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(commandService);
    }

    @Test
    void rejectsStatusChangeWithoutCsrfToken() throws Exception {
        mockMvc.perform(patch("/api/v1/defects/{defectId}/status", DEFECT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_UPDATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflowStatus":"ACCEPTED","reason":"현장 확인"}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(commandService);
    }

    @Test
    void rejectsStatusChangeWithoutReason() throws Exception {
        mockMvc.perform(patch("/api/v1/defects/{defectId}/status", DEFECT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_UPDATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflowStatus":"ACCEPTED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("COMMON_VALIDATION_FAILED")));

        verifyNoInteractions(commandService);
    }

    /**
     * 전이 판정은 service가 하고 controller는 409로 옮기기만 한다. 지금 규칙에서
     * 실제로 막히는 요청(NEW로 되돌리기)을 본문에 담아 이야기를 맞춰둔다.
     */
    @Test
    void mapsDisallowedTransitionToConflict() throws Exception {
        when(commandService.changeStatus(
                eq(USER_ID), eq(DEFECT_ID), any(), any()))
                .thenThrow(new BusinessException(
                        ErrorCode.DEFECT_STATE_CONFLICT,
                        "신규 상태로는 되돌릴 수 없습니다."));

        mockMvc.perform(patch("/api/v1/defects/{defectId}/status", DEFECT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_UPDATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflowStatus":"NEW",
                                 "reason":"오탐 판정을 되돌린다"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DEFECT_STATE_CONFLICT")));
    }

    @Test
    void mapsOutOfScopeDetailToSameNotFoundResponse() throws Exception {
        when(queryService.findDefect(USER_ID, DEFECT_ID))
                .thenThrow(new BusinessException(ErrorCode.DEFECT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/defects/{defectId}", DEFECT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("DEFECT_NOT_FOUND")));
    }

    @Test
    void mapsMissingEvidenceImageToNotFound() throws Exception {
        when(queryService.issueImageUrl(USER_ID, DEFECT_ID, null))
                .thenThrow(new BusinessException(ErrorCode.IMAGE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/defects/{defectId}/image-url", DEFECT_ID)
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("IMAGE_NOT_FOUND")));
    }

    @Test
    void requiresDefectMergePermissionToMerge() throws Exception {
        mockMvc.perform(post("/api/v1/defects/merge")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_UPDATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetDefectId":"%s",
                                 "sourceDefectIds":["%s"],
                                 "reason":"동일 위치 중복"}
                                """.formatted(DEFECT_ID, UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(commandService);
    }

    @Test
    void acceptsExportRequestWith202() throws Exception {
        UUID exportJobId =
                UUID.fromString("9f8e7d6c-5b4a-4938-8271-615243342516");
        when(commandService.requestExport(eq(USER_ID), any(), any()))
                .thenReturn(new DefectExportAcceptedResponse(
                        exportJobId,
                        "QUEUED",
                        "CSV",
                        Instant.parse("2026-07-28T01:00:00Z")));

        mockMvc.perform(post("/api/v1/defects/export")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_EXPORT))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"format":"CSV",
                                 "filter":{"workflowStatuses":["NEW"]}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.exportJobId", is(exportJobId.toString())))
                .andExpect(jsonPath("$.jobStatus", is("QUEUED")));
    }

    @Test
    void requiresDefectExportPermissionToExport() throws Exception {
        mockMvc.perform(post("/api/v1/defects/export")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_MERGE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"format":"CSV","filter":{}}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(commandService);
    }

    @Test
    void rejectsMalformedBoundingBoxOnMap() throws Exception {
        mockMvc.perform(get("/api/v1/defects/map")
                        .param("bbox", "126.9,37.4,127.1")
                        .with(user(USER_ID.toString())
                                .authorities(DEFECT_STATUS_VIEW)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("COMMON_INVALID_REQUEST")));
    }

    private static DefectListItemResponse listItem() {
        return new DefectListItemResponse(
                DEFECT_ID,
                "INC-20260723-000031",
                "BRAILLE_BLOCK",
                "LINEAR",
                "DAMAGED",
                "HIGH",
                new BigDecimal("72.50"),
                "NEW",
                null,
                new GeoPoint(127.0276, 37.4979),
                3.8,
                "A-01",
                null,
                null,
                null,
                null,
                Instant.parse("2026-07-23T10:15:34Z"),
                Instant.parse("2026-07-23T10:15:34Z"),
                1,
                new BigDecimal("0.870"),
                false);
    }
}
