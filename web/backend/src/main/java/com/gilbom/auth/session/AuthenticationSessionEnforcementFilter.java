package com.gilbom.auth.session;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.security.web.SecurityErrorResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class AuthenticationSessionEnforcementFilter
        extends OncePerRequestFilter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuthenticationSessionEnforcementFilter.class);

    /**
     * 거부 사유. 응답은 전부 같은 401/`AUTH_UNAUTHENTICATED`이지만, 로그에는 어느
     * 갈래였는지 남긴다 — 넷이 한 코드로 뭉뚱그려져 있어 로그를 봐도 원인을 가릴 수
     * 없었다. MDC의 `requestId`가 함께 실리므로 사용자가 본 401과 이어붙일 수 있다.
     */
    private enum RejectionReason {
        /** 세션 보안 상태 행이 없다. 다른 곳에서 로그인해 밀렸거나 로그아웃된 세션이다. */
        STATE_MISSING(true),
        /** absolute 또는 challenge 만료다. */
        EXPIRED(true),
        /** 인증 주체가 세션 소유자와 다르거나 세션이 AUTHENTICATED가 아니다. */
        PRINCIPAL_MISMATCH(true),
        /**
         * 세션은 AUTHENTICATED인데 이 요청에 인증 컨텍스트가 실리지 않았다.
         *
         * <p>이것만으로는 **세션을 지우지 않는다.** 컨텍스트가 없다는 것은 공격의 증거가
         * 아니라 이 요청 하나가 인증 없이 들어왔다는 뜻이고, 요청을 거부하는 것으로 이미
         * 데이터는 지켜진다. 세션까지 파괴하면 그 한 건이 곧바로 전면 로그아웃이 된다.
         */
        CONTEXT_MISSING(false);

        private final boolean destroysSession;

        RejectionReason(boolean destroysSession) {
            this.destroysSession = destroysSession;
        }
    }

    private final SessionSecurityStateRepository repository;
    private final SecurityErrorResponseWriter errorWriter;
    private final Clock clock;

    public AuthenticationSessionEnforcementFilter(
            SessionSecurityStateRepository repository,
            SecurityErrorResponseWriter errorWriter,
            Clock clock
    ) {
        this.repository = repository;
        this.errorWriter = errorWriter;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null) {
            filterChain.doFilter(request, response);
            return;
        }

        final Optional<SessionSecurityState> optionalState;
        try {
            optionalState = repository.findBySessionId(session.getId());
        } catch (DataAccessException exception) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            errorWriter.write(request, response, ErrorCode.DB_UNAVAILABLE);
            return;
        }

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        boolean hasAuthentication = isAuthenticated(authentication);

        if (optionalState.isEmpty()) {
            if (hasAuthentication) {
                reject(request, response, session, RejectionReason.STATE_MISSING);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        SessionSecurityState state = optionalState.orElseThrow();
        RejectionReason reason = evaluate(state, authentication, hasAuthentication);
        if (reason != null) {
            reject(request, response, session, reason);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * @return 거부 사유. 통과시켜도 되면 {@code null}
     */
    private RejectionReason evaluate(
            SessionSecurityState state,
            Authentication authentication,
            boolean hasAuthentication
    ) {
        if (state.isExpiredAt(clock.instant())) {
            return RejectionReason.EXPIRED;
        }
        if (hasAuthentication) {
            boolean mismatched =
                    state.authenticationState() != AuthenticationState.AUTHENTICATED
                    || !String.valueOf(state.userId())
                            .equals(authentication.getName());
            return mismatched ? RejectionReason.PRINCIPAL_MISMATCH : null;
        }
        return state.authenticationState() == AuthenticationState.AUTHENTICATED
                ? RejectionReason.CONTEXT_MISSING
                : null;
    }

    /**
     * 모든 갈래가 같은 401을 돌려주되, 세션을 파괴할지는 사유가 정한다.
     *
     * <p>사유만 남긴다. 세션 id·사용자 id는 로그에 넣지 않는다 — MDC의 `requestId`로
     * 이미 요청을 특정할 수 있고, 이 로그는 인증 실패 지점이라 식별자를 남기면
     * 그대로 유출 통로가 된다.
     */
    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            RejectionReason reason
    ) throws IOException {
        LOGGER.warn(
                "Authenticated session request rejected: reason={}, sessionDestroyed={}",
                reason,
                reason.destroysSession
        );

        if (reason.destroysSession) {
            try {
                repository.deleteBySessionId(
                        session.getId(),
                        SessionDeletionReason.ENFORCEMENT_REJECT
                );
            } catch (DataAccessException ignored) {
                // 응답은 고정하고 DB 예외 세부정보를 로그나 본문에 노출하지 않는다.
            }
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 이미 폐기된 경우에도 동일한 401만 반환한다.
            }
        }

        SecurityContextHolder.clearContext();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        errorWriter.write(request, response, ErrorCode.AUTH_UNAUTHENTICATED);
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
