package com.gilbom.auth.admin;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.gilbom.auth.admin.AdminDtos.PageResponse;
import com.gilbom.auth.admin.AdminDtos.UserResponse;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestBodySizeLimitFilter;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = AdminController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class AdminControllerTest {

    private static final UUID ACTOR_ID = UUID.fromString(
            "41000000-0000-4000-8000-000000000001"
    );
    private static final UUID TARGET_ID = UUID.fromString(
            "41000000-0000-4000-8000-000000000002"
    );
    private static final UUID ROLE_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000003"
    );
    private static final SimpleGrantedAuthority ADMIN_PERMISSION =
            new SimpleGrantedAuthority("ADMIN_SETTINGS_MANAGE");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminManagementService managementService;

    @MockitoBean
    private AdminMfaResetService mfaResetService;

    @MockitoBean
    private SessionSecurityStateRepository sessionRepository;

    @BeforeEach
    void setUpSession() {
        when(sessionRepository.findBySessionId(any()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        ACTOR_ID,
                        AuthenticationState.AUTHENTICATED,
                        Instant.parse("2099-01-01T00:00:00Z"),
                        null,
                        null,
                        0
                )));
    }

    @Test
    void registrationRequestsRequireAdminSettingsPermission()
            throws Exception {
        mockMvc.perform(get("/api/v1/admin/registration-requests")
                        .with(user(ACTOR_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(managementService);
    }

    @Test
    void registrationRequestsArePagedForAdmin() throws Exception {
        when(managementService.findUsers(
                0,
                20,
                "PENDING_APPROVAL"
        )).thenReturn(new PageResponse<>(
                List.of(userResponse("PENDING_APPROVAL", false)),
                0,
                20,
                1,
                1
        ));

        mockMvc.perform(get("/api/v1/admin/registration-requests")
                        .with(user(ACTOR_ID.toString())
                                .authorities(ADMIN_PERMISSION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.content[0].accountStatus",
                        is("PENDING_APPROVAL")
                ))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void approvalRequiresCsrf() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/admin/registration-requests/{userId}/approve",
                        TARGET_ID
                )
                        .with(user(ACTOR_ID.toString())
                                .authorities(ADMIN_PERMISSION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(managementService);
    }

    @Test
    void approvalPassesAuthenticatedActorAndValidatedRequest()
            throws Exception {
        when(managementService.approveRegistration(
                eq(TARGET_ID),
                any(),
                eq(ACTOR_ID),
                any()
        )).thenReturn(userResponse("ACTIVE", true));

        mockMvc.perform(post(
                        "/api/v1/admin/registration-requests/{userId}/approve",
                        TARGET_ID
                )
                        .with(user(ACTOR_ID.toString())
                                .authorities(ADMIN_PERMISSION))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus", is("ACTIVE")))
                .andExpect(jsonPath("$.active", is(true)));

        verify(managementService).approveRegistration(
                eq(TARGET_ID),
                any(),
                eq(ACTOR_ID),
                any()
        );
    }

    private static UserResponse userResponse(
            String status,
            boolean active
    ) {
        return new UserResponse(
                TARGET_ID,
                "USR-ADMIN-TEST",
                "target@example.test",
                "대상 사용자",
                null,
                null,
                null,
                status,
                active,
                false,
                List.of(),
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T00:00:00Z")
        );
    }

    private static String approvalRequest() {
        return """
                {
                  "roleIds": ["%s"],
                  "primaryRoleId": "%s",
                  "reason": "가입 요청 검토 완료"
                }
                """.formatted(ROLE_ID, ROLE_ID);
    }
}
