package com.gilbom.auth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthenticationStateMachineTest {

    private final AuthenticationStateMachine stateMachine = new AuthenticationStateMachine();

    @Test
    void passwordVerificationNeverCompletesAuthentication() {
        assertThat(stateMachine.transition(
                AuthenticationState.ANONYMOUS,
                AuthenticationEvent.PASSWORD_VERIFIED_CHANGE_REQUIRED))
                .isEqualTo(AuthenticationState.PASSWORD_CHANGE_REQUIRED);
        assertThat(stateMachine.transition(
                AuthenticationState.ANONYMOUS,
                AuthenticationEvent.PASSWORD_VERIFIED_MFA_ENROLLMENT_REQUIRED))
                .isEqualTo(AuthenticationState.MFA_ENROLLMENT_REQUIRED);
        assertThat(stateMachine.transition(
                AuthenticationState.ANONYMOUS,
                AuthenticationEvent.PASSWORD_VERIFIED_MFA_REQUIRED))
                .isEqualTo(AuthenticationState.MFA_REQUIRED);
    }

    @Test
    void initialPasswordChangeRequiresMfaEnrollment() {
        assertThat(stateMachine.transition(
                AuthenticationState.PASSWORD_CHANGE_REQUIRED,
                AuthenticationEvent.INITIAL_PASSWORD_CHANGED))
                .isEqualTo(AuthenticationState.MFA_ENROLLMENT_REQUIRED);
    }

    @Test
    void onlyMfaWaitingStatesCanBecomeAuthenticated() {
        assertThat(stateMachine.transition(
                AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                AuthenticationEvent.MFA_VERIFIED))
                .isEqualTo(AuthenticationState.AUTHENTICATED);
        assertThat(stateMachine.transition(
                AuthenticationState.MFA_REQUIRED,
                AuthenticationEvent.MFA_VERIFIED))
                .isEqualTo(AuthenticationState.AUTHENTICATED);

        assertThatThrownBy(() -> stateMachine.transition(
                AuthenticationState.ANONYMOUS,
                AuthenticationEvent.MFA_VERIFIED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stateMachine.transition(
                AuthenticationState.PASSWORD_CHANGE_REQUIRED,
                AuthenticationEvent.MFA_VERIFIED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyTheInternalMfaRequiredStageCanConfirmMfaBypass() {
        assertThat(stateMachine.transition(
                AuthenticationState.MFA_REQUIRED,
                AuthenticationEvent.MFA_BYPASS_CONFIRMED))
                .isEqualTo(AuthenticationState.AUTHENTICATED);

        assertThatThrownBy(() -> stateMachine.transition(
                AuthenticationState.ANONYMOUS,
                AuthenticationEvent.MFA_BYPASS_CONFIRMED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stateMachine.transition(
                AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                AuthenticationEvent.MFA_BYPASS_CONFIRMED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidationReturnsEveryStateToAnonymous() {
        for (AuthenticationState state : AuthenticationState.values()) {
            assertThat(stateMachine.transition(state, AuthenticationEvent.INVALIDATED))
                    .isEqualTo(AuthenticationState.ANONYMOUS);
        }
    }

    @Test
    void authenticatedIsTheOnlyFullyAuthenticatedState() {
        for (AuthenticationState state : AuthenticationState.values()) {
            assertThat(state.isFullyAuthenticated())
                    .isEqualTo(state == AuthenticationState.AUTHENTICATED);
        }
    }
}
