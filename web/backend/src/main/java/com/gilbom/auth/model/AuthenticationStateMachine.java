package com.gilbom.auth.model;

import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * 인증 상태를 임의로 대입하지 않고 검증된 사건에 의해서만 전이시키는 단일 진입점이다.
 */
@Component
public class AuthenticationStateMachine {

    public AuthenticationState transition(
            AuthenticationState currentState,
            AuthenticationEvent event) {
        Objects.requireNonNull(currentState, "currentState must not be null");
        Objects.requireNonNull(event, "event must not be null");

        return switch (event) {
            case PASSWORD_VERIFIED_CHANGE_REQUIRED ->
                    fromAnonymous(currentState, AuthenticationState.PASSWORD_CHANGE_REQUIRED, event);
            case PASSWORD_VERIFIED_MFA_ENROLLMENT_REQUIRED ->
                    fromAnonymous(currentState, AuthenticationState.MFA_ENROLLMENT_REQUIRED, event);
            case PASSWORD_VERIFIED_MFA_REQUIRED ->
                    fromAnonymous(currentState, AuthenticationState.MFA_REQUIRED, event);
            case INITIAL_PASSWORD_CHANGED -> from(
                    currentState,
                    AuthenticationState.PASSWORD_CHANGE_REQUIRED,
                    AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                    event);
            case MFA_VERIFIED -> afterMfaVerification(currentState, event);
            case MFA_RESET -> from(
                    currentState,
                    AuthenticationState.AUTHENTICATED,
                    AuthenticationState.MFA_ENROLLMENT_REQUIRED,
                    event);
            case INVALIDATED -> AuthenticationState.ANONYMOUS;
        };
    }

    private AuthenticationState fromAnonymous(
            AuthenticationState currentState,
            AuthenticationState nextState,
            AuthenticationEvent event) {
        return from(
                currentState,
                AuthenticationState.ANONYMOUS,
                nextState,
                event);
    }

    private AuthenticationState afterMfaVerification(
            AuthenticationState currentState,
            AuthenticationEvent event) {
        if (currentState == AuthenticationState.MFA_ENROLLMENT_REQUIRED
                || currentState == AuthenticationState.MFA_REQUIRED) {
            return AuthenticationState.AUTHENTICATED;
        }
        throw invalidTransition(currentState, event);
    }

    private AuthenticationState from(
            AuthenticationState currentState,
            AuthenticationState requiredState,
            AuthenticationState nextState,
            AuthenticationEvent event) {
        if (currentState == requiredState) {
            return nextState;
        }
        throw invalidTransition(currentState, event);
    }

    private IllegalStateException invalidTransition(
            AuthenticationState currentState,
            AuthenticationEvent event) {
        return new IllegalStateException(
                "Invalid authentication state transition: "
                        + currentState + " + " + event);
    }
}
