package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.security.web.SecurityErrorResponseWriter;

import jakarta.servlet.http.HttpSession;

class AuthenticationSessionEnforcementFilterTest {

    private static final UUID USER_ID =
            UUID.fromString("19000000-0000-4000-8000-000000000001");
    private static final Instant NOW =
            Instant.parse("2026-07-27T06:00:00Z");

    private final SessionSecurityStateRepository repository =
            mock(SessionSecurityStateRepository.class);
    private final AuthenticationSessionEnforcementFilter filter =
            new AuthenticationSessionEnforcementFilter(
                    repository,
                    new SecurityErrorResponseWriter(
                            new ObjectMapper().findAndRegisterModules()),
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        session = request.getSession(true);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void expiredAbsoluteSessionIsDeletedAndRejected() throws Exception {
        when(repository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        NOW,
                        null,
                        null,
                        0
                )));
        SecurityContextHolder.getContext().setAuthentication(
                authenticatedUser(USER_ID)
        );

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(chain.getRequest()).isNull();
        verify(repository).deleteBySessionId(session.getId());
    }

    @Test
    void preAuthenticationChallengeWithoutSecurityContextContinues()
            throws Exception {
        when(repository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.MFA_REQUIRED,
                        NOW.plusSeconds(300),
                        NOW,
                        NOW.plusSeconds(300),
                        0
                )));

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void authenticatedContextMustMatchSessionOwner() throws Exception {
        when(repository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        NOW.plusSeconds(3600),
                        null,
                        null,
                        0
                )));
        SecurityContextHolder.getContext().setAuthentication(
                authenticatedUser(UUID.fromString(
                        "19000000-0000-4000-8000-000000000002"))
        );

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void validAuthenticatedSessionContinues() throws Exception {
        when(repository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        NOW.plusSeconds(3600),
                        null,
                        null,
                        0
                )));
        SecurityContextHolder.getContext().setAuthentication(
                authenticatedUser(USER_ID)
        );

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private static UsernamePasswordAuthenticationToken authenticatedUser(
            UUID userId
    ) {
        return UsernamePasswordAuthenticationToken.authenticated(
                userId.toString(),
                null,
                List.of()
        );
    }
}
