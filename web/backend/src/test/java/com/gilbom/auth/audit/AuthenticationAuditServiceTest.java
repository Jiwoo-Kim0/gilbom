package com.gilbom.auth.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

class AuthenticationAuditServiceTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final AuthenticationRequestAuditContext CONTEXT =
            new AuthenticationRequestAuditContext(
                    REQUEST_ID,
                    "203.0.113.13",
                    "test-agent"
            );

    private final AuthenticationAuditRepository repository =
            Mockito.mock(AuthenticationAuditRepository.class);
    private final BestEffortAuthenticationAuditWriter bestEffortWriter =
            Mockito.mock(BestEffortAuthenticationAuditWriter.class);
    private final AuthenticationAuditService service =
            new AuthenticationAuditService(repository, bestEffortWriter);

    @Test
    void requiredLoginSuccessPropagatesAuditFailure() {
        Mockito.doThrow(new DataAccessResourceFailureException(
                "password=must-not-leak"
        )).when(repository).append(Mockito.any());

        assertThatThrownBy(() ->
                service.recordLoginSucceededRequired(CONTEXT, USER_ID))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void bestEffortRateLimitAuditDoesNotChangeTheDenialOutcome() {
        Mockito.doThrow(new DataAccessResourceFailureException(
                "password=must-not-leak"
        )).when(bestEffortWriter)
                .appendInIndependentTransaction(Mockito.any());

        assertThatCode(() ->
                service.recordRateLimitedBestEffort(CONTEXT, USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void loginFailureContainsOnlyKnownInternalUserId() {
        service.recordLoginFailedRequired(CONTEXT, USER_ID);

        verify(repository).append(Mockito.argThat(event ->
                event.action()
                        == AuthenticationAuditAction.LOGIN_FAILED
                        && USER_ID.equals(event.targetId())
                        && event.actorUserId() == null
        ));
    }

    @Test
    void passwordVerificationIsNotRecordedAsCompletedLogin() {
        service.recordPasswordVerifiedRequired(CONTEXT, USER_ID);

        verify(repository).append(Mockito.argThat(event ->
                event.action()
                        == AuthenticationAuditAction.PASSWORD_VERIFIED
                        && event.result()
                            == AuthenticationAuditEvent.Result.SUCCESS
                        && USER_ID.equals(event.targetId())
                        && event.actorUserId() == null
        ));
    }

    @Test
    void initialPasswordChangeUsesDistinctRequiredSuccessAndFailureEvents() {
        service.recordInitialPasswordChangedRequired(CONTEXT, USER_ID);
        service.recordInitialPasswordChangeFailedRequired(CONTEXT, USER_ID);

        verify(repository).append(Mockito.argThat(event ->
                event.action()
                        == AuthenticationAuditAction
                        .INITIAL_PASSWORD_CHANGED
                        && event.result()
                            == AuthenticationAuditEvent.Result.SUCCESS
        ));
        verify(repository).append(Mockito.argThat(event ->
                event.action()
                        == AuthenticationAuditAction
                        .INITIAL_PASSWORD_CHANGE_FAILED
                        && event.result()
                            == AuthenticationAuditEvent.Result.FAILURE
        ));
    }

    @Test
    void myPasswordChangeAndSessionRevocationAreRequiredEvents() {
        service.recordPasswordChangedRequired(CONTEXT, USER_ID);
        service.recordPasswordChangeFailedRequired(CONTEXT, USER_ID);
        service.recordSessionRevokedRequired(
                CONTEXT,
                USER_ID,
                USER_ID
        );

        verify(repository).append(Mockito.argThat(event ->
                event.action()
                        == AuthenticationAuditAction.PASSWORD_CHANGED
                        && USER_ID.equals(event.actorUserId())
                        && event.result()
                            == AuthenticationAuditEvent.Result.SUCCESS
        ));
        verify(repository).append(Mockito.argThat(event ->
                event.action()
                        == AuthenticationAuditAction
                        .PASSWORD_CHANGE_FAILED
                        && event.result()
                            == AuthenticationAuditEvent.Result.FAILURE
        ));
        verify(repository).append(Mockito.argThat(event ->
                event.action()
                        == AuthenticationAuditAction.SESSION_REVOKED
                        && USER_ID.equals(event.targetId())
        ));
    }

    @Test
    void logoutUsesBestEffortPolicy() {
        service.recordLogoutBestEffort(CONTEXT, USER_ID);

        verify(bestEffortWriter).appendInIndependentTransaction(
                Mockito.argThat(event ->
                        event.action()
                                == AuthenticationAuditAction.LOGOUT
                                && USER_ID.equals(event.actorUserId())
                                && event.targetId() == null
                )
        );
    }
}
