package com.gilbom.audit;

import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class SecurityAuditServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("31000000-0000-4000-8000-000000000001");

    private final AuditLogService auditLogService =
            Mockito.mock(AuditLogService.class);
    private final SecurityAuditService service =
            new SecurityAuditService(auditLogService);

    @Test
    void accessDenialRecordsOnlySafeRequestMetadata() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("PATCH", "/api/v1/robots/1");
        request.setQueryString("token=must-not-be-recorded");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("User-Agent", "test-agent");
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                USER_ID.toString(),
                null,
                java.util.List.of()
        );

        service.recordAccessDenied(request, authentication);

        verify(auditLogService).appendBestEffort(
                Mockito.argThat(record ->
                        record.actorUserId().equals(USER_ID)
                                && record.actionType()
                                .equals("AUTH_ACCESS_DENIED")
                                && record.result() == AuditResult.DENIED
                                && record.afterValue().values()
                                .get("requestPath")
                                .equals("/api/v1/robots/1")
                                && !record.afterValue().values()
                                .toString()
                                .contains("must-not-be-recorded")
                )
        );
    }

    @Test
    void csrfFailureDoesNotRequireAKnownActor() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");

        service.recordCsrfFailed(request, null);

        verify(auditLogService).appendBestEffort(
                Mockito.argThat(record ->
                        record.actorUserId() == null
                                && record.actionType()
                                .equals("AUTH_CSRF_FAILED")
                                && record.afterValue().values()
                                .get("denialType")
                                .equals("CSRF")
                )
        );
    }

    @Test
    void auditFailureCannotTurnADenialIntoAnApplicationFailure() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/robots");
        Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService)
                .appendBestEffort(Mockito.any());

        assertThatCode(() -> service.recordCsrfFailed(request, null))
                .doesNotThrowAnyException();
    }
}
