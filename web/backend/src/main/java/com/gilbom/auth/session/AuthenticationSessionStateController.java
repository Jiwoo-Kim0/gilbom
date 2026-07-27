package com.gilbom.auth.session;

import java.time.Instant;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.model.AuthenticationState;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationSessionStateController {

    private final AuthenticationSessionService sessionService;

    public AuthenticationSessionStateController(
            AuthenticationSessionService sessionService
    ) {
        this.sessionService = sessionService;
    }

    @GetMapping("/session-state")
    public ResponseEntity<AuthenticationSessionStateResponse> sessionState(
            HttpServletRequest request
    ) {
        AuthenticationSessionStateResponse response =
                sessionService.findCurrentState(request)
                        .map(AuthenticationSessionStateResponse::from)
                        .orElseGet(
                                AuthenticationSessionStateResponse::anonymous
                        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    public record AuthenticationSessionStateResponse(
            AuthenticationState authenticationState,
            Instant challengeExpiresAt
    ) {

        private static AuthenticationSessionStateResponse from(
                SessionSecurityState state
        ) {
            return new AuthenticationSessionStateResponse(
                    state.authenticationState(),
                    state.authenticationState().isFullyAuthenticated()
                            ? null
                            : state.challengeExpiresAt()
            );
        }

        private static AuthenticationSessionStateResponse anonymous() {
            return new AuthenticationSessionStateResponse(
                    AuthenticationState.ANONYMOUS,
                    null
            );
        }
    }
}
