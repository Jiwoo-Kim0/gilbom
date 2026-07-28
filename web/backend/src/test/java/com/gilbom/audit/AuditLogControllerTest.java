package com.gilbom.audit;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.web.GlobalExceptionHandler;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.security.config.SecurityConfig;
import com.gilbom.security.web.SecurityErrorResponseWriter;
import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(controllers = AuditLogController.class)
@Import({
        SecurityConfig.class,
        SecurityErrorResponseWriter.class,
        SecurityHeadersFilter.class,
        RequestIdFilter.class,
        GlobalExceptionHandler.class,
        AuthenticatedUserIdResolver.class
})
class AuditLogControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("32000000-0000-4000-8000-000000000001");
    private static final SimpleGrantedAuthority AUDIT_LOG_VIEW =
            new SimpleGrantedAuthority("AUDIT_LOG_VIEW");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogQueryService queryService;

    @MockitoBean
    private SessionSecurityStateRepository sessionStateRepository;

    @BeforeEach
    void setUpAuthenticatedSessionState() {
        when(sessionStateRepository.findBySessionId(any()))
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
    void requiresAuditLogViewPermission() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));

        verifyNoInteractions(queryService);
    }

    @Test
    void returnsPagedAuditRecordsWithPermission() throws Exception {
        when(queryService.find(
                any(AuditLogFilter.class),
                any(UUID.class),
                any(AuditRequestContext.class)
        )).thenReturn(new AuditLogListResponse(
                List.of(new AuditLogItemResponse(
                        7L,
                        UUID.fromString(
                                "32000000-0000-4000-8000-000000000002"
                        ),
                        USER_ID,
                        "USER",
                        "ROBOT_REGISTERED",
                        "ROBOT",
                        UUID.fromString(
                                "32000000-0000-4000-8000-000000000003"
                        ),
                        null,
                        null,
                        null,
                        "SUCCESS",
                        "203.0.113.10",
                        "test-agent",
                        Instant.parse("2026-07-28T01:00:00Z")
                )),
                0,
                20,
                1,
                1
        ));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("actionType", "ROBOT_REGISTERED")
                        .with(user(USER_ID.toString())
                                .authorities(AUDIT_LOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath(
                        "$.content[0].actionType",
                        is("ROBOT_REGISTERED")
                ))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }
}
