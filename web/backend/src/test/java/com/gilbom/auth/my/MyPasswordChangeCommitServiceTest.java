package com.gilbom.auth.my;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.session.AuthenticationSessionService;

class MyPasswordChangeCommitServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("27000000-0000-4000-8000-000000000001");
    private static final AuthenticationRequestAuditContext CONTEXT =
            new AuthenticationRequestAuditContext(
                    UUID.fromString(
                            "27000000-0000-4000-8000-000000000002"
                    ),
                    "203.0.113.27",
                    null
            );
    private static final String OLD_HASH =
            "{argon2}$argon2id$old";
    private static final String NEW_HASH =
            "{argon2}$argon2id$new";

    private final MyAccountRepository accountRepository =
            Mockito.mock(MyAccountRepository.class);
    private final AuthenticationSessionService sessionService =
            Mockito.mock(AuthenticationSessionService.class);
    private final AuthenticationAuditService auditService =
            Mockito.mock(AuthenticationAuditService.class);
    private final MyPasswordChangeCommitService service =
            new MyPasswordChangeCommitService(
                    accountRepository,
                    sessionService,
                    auditService
            );

    @Test
    void passwordAuditAndEverySessionRevocationCommitTogether() {
        when(accountRepository.updatePassword(
                USER_ID,
                OLD_HASH,
                NEW_HASH
        )).thenReturn(1);
        when(sessionService.invalidateAllUserSessions(USER_ID))
                .thenReturn(2);

        assertThat(service.commit(
                USER_ID,
                OLD_HASH,
                NEW_HASH,
                CONTEXT
        )).isTrue();

        InOrder order = inOrder(
                accountRepository,
                auditService,
                sessionService
        );
        order.verify(accountRepository).updatePassword(
                USER_ID,
                OLD_HASH,
                NEW_HASH
        );
        order.verify(auditService).recordPasswordChangedRequired(
                CONTEXT,
                USER_ID
        );
        order.verify(sessionService).invalidateAllUserSessions(USER_ID);
        order.verify(auditService).recordSessionRevokedRequired(
                CONTEXT,
                USER_ID,
                USER_ID
        );
    }

    @Test
    void anOptimisticHashConflictDoesNotRevokeSessions() {
        when(accountRepository.updatePassword(
                USER_ID,
                OLD_HASH,
                NEW_HASH
        )).thenReturn(0);

        assertThat(service.commit(
                USER_ID,
                OLD_HASH,
                NEW_HASH,
                CONTEXT
        )).isFalse();

        verify(sessionService, never())
                .invalidateAllUserSessions(USER_ID);
        verify(auditService, never())
                .recordPasswordChangedRequired(CONTEXT, USER_ID);
    }

    @Test
    void missingCurrentSessionFailsBeforeTheCommitCanComplete() {
        when(accountRepository.updatePassword(
                USER_ID,
                OLD_HASH,
                NEW_HASH
        )).thenReturn(1);
        when(sessionService.invalidateAllUserSessions(USER_ID))
                .thenReturn(0);

        assertThatThrownBy(() -> service.commit(
                USER_ID,
                OLD_HASH,
                NEW_HASH,
                CONTEXT
        )).isInstanceOf(IllegalStateException.class);

        verify(auditService, never()).recordSessionRevokedRequired(
                CONTEXT,
                USER_ID,
                USER_ID
        );
    }
}
