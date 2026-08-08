package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    /**
     * 인증 컨텍스트가 실리지 않은 요청 하나가 **세션을 파괴하지 않는다.**
     *
     * <p>이 갈래는 예전에도 401이었지만 세션 행까지 지웠다. 그러면 그 한 건이 곧바로
     * 전면 로그아웃이 된다 — 사용자가 지도 묶음을 눌렀을 때 로그인 화면으로 튕기던
     * 증상이 이 모양이었다. 요청을 거부하는 것으로 데이터는 이미 지켜진다.
     */
    @Test
    void missingSecurityContextIsRejectedWithoutDestroyingTheSession()
            throws Exception {
        when(repository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(new SessionSecurityState(
                        USER_ID,
                        AuthenticationState.AUTHENTICATED,
                        NOW.plusSeconds(3600),
                        null,
                        null,
                        0
                )));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        verify(repository, never()).deleteBySessionId(session.getId());
        // 다음 요청이 컨텍스트를 제대로 싣고 오면 그대로 이어갈 수 있어야 한다.
        assertThat(request.getSession(false)).isNotNull();
    }

    /**
     * 반대로 **정말 수상한** 경우는 그대로 파괴한다. 다른 곳에서 로그인해 밀렸거나
     * 로그아웃된 세션이다.
     */
    @Test
    void missingSecurityStateStillDestroysTheSession() throws Exception {
        when(repository.findBySessionId(session.getId()))
                .thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                authenticatedUser(USER_ID)
        );

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        verify(repository).deleteBySessionId(session.getId());
    }

    @Test
    void mismatchedPrincipalDestroysTheSession() throws Exception {
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

        verify(repository).deleteBySessionId(session.getId());
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
