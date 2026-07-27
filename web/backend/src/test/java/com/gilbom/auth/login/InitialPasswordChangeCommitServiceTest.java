package com.gilbom.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeSession;
import com.gilbom.auth.session.SessionSecurityState;

import jakarta.servlet.http.HttpServletRequest;

class InitialPasswordChangeCommitServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("24000000-0000-4000-8000-000000000001");
    private static final Instant NOW =
            Instant.parse("2026-07-27T10:00:00Z");
    private static final AuthenticationRequestAuditContext AUDIT_CONTEXT =
            new AuthenticationRequestAuditContext(
                    UUID.fromString(
                            "24000000-0000-4000-8000-000000000002"
                    ),
                    "203.0.113.24",
                    "test-agent"
            );

    private final LoginAccountRepository accountRepository =
            Mockito.mock(LoginAccountRepository.class);
    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final InitialPasswordChangeCommitService service =
            new InitialPasswordChangeCommitService(
                    accountRepository,
                    sessionService,
                    auditService
            );
    private final HttpServletRequest request =
            Mockito.mock(HttpServletRequest.class);

    @Test
    void updatesRevokesAuditsAndTransitionsInOneOrderedCommit() {
        SessionSecurityState state = passwordChangeState();
        ChallengeSession next = new ChallengeSession(
                AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                NOW.plusSeconds(300)
        );
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(state));
        when(accountRepository.updateInitialPassword(
                USER_ID,
                "old-hash",
                "new-hash"
        )).thenReturn(1);
        when(sessionService.continueAfterInitialPasswordChange(
                request,
                USER_ID
        )).thenReturn(next);

        Optional<ChallengeSession> result = service.commit(
                request,
                USER_ID,
                "old-hash",
                "new-hash",
                AUDIT_CONTEXT
        );

        assertThat(result).contains(next);
        InOrder order = inOrder(
                accountRepository,
                sessionService,
                auditService
        );
        order.verify(sessionService).findCurrentState(request);
        order.verify(accountRepository).updateInitialPassword(
                USER_ID,
                "old-hash",
                "new-hash"
        );
        order.verify(sessionService).invalidateOtherUserSessions(
                USER_ID,
                request
        );
        order.verify(auditService).recordInitialPasswordChangedRequired(
                AUDIT_CONTEXT,
                USER_ID
        );
        order.verify(sessionService).continueAfterInitialPasswordChange(
                request,
                USER_ID
        );
    }

    @Test
    void concurrentOrStaleUpdateDoesNotAuditOrTransition() {
        when(sessionService.findCurrentState(request))
                .thenReturn(Optional.of(passwordChangeState()));
        when(accountRepository.updateInitialPassword(
                USER_ID,
                "old-hash",
                "new-hash"
        )).thenReturn(0);

        Optional<ChallengeSession> result = service.commit(
                request,
                USER_ID,
                "old-hash",
                "new-hash",
                AUDIT_CONTEXT
        );

        assertThat(result).isEmpty();
        verify(sessionService, never()).invalidateOtherUserSessions(
                Mockito.any(),
                Mockito.any()
        );
        verify(auditService, never())
                .recordInitialPasswordChangedRequired(
                        Mockito.any(),
                        Mockito.any()
                );
        verify(sessionService, never())
                .continueAfterInitialPasswordChange(
                        Mockito.any(),
                        Mockito.any()
                );
    }

    private static SessionSecurityState passwordChangeState() {
        return new SessionSecurityState(
                USER_ID,
                AuthenticationState.PASSWORD_CHANGE_REQUIRED,
                NOW.plusSeconds(300),
                NOW,
                NOW.plusSeconds(300),
                0
        );
    }
}
