package com.gilbom.auth.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.model.AuthenticationEvent;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.model.AuthenticationStateMachine;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Service
public class AuthenticationSessionService {

    private final SessionSecurityStateRepository repository;
    private final AuthenticationStateMachine stateMachine;
    private final AuthenticationSessionProperties properties;
    private final CsrfTokenRepository csrfTokenRepository;
    private final CsrfTokenRequestHandler csrfTokenRequestHandler;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;

    public AuthenticationSessionService(
            SessionSecurityStateRepository repository,
            AuthenticationStateMachine stateMachine,
            AuthenticationSessionProperties properties,
            CsrfTokenRepository csrfTokenRepository,
            CsrfTokenRequestHandler csrfTokenRequestHandler,
            SecurityContextRepository securityContextRepository,
            Clock clock
    ) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.properties = properties;
        this.csrfTokenRepository = csrfTokenRepository;
        this.csrfTokenRequestHandler = csrfTokenRequestHandler;
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
    }

    @Transactional
    public ChallengeSession beginChallenge(
            HttpServletRequest request,
            UUID userId,
            AuthenticationEvent passwordVerifiedEvent
    ) {
        requirePasswordVerifiedEvent(passwordVerifiedEvent);
        HttpSession session = requireCurrentSession(request);
        Optional<SessionSecurityState> existing =
                repository.findBySessionId(session.getId());
        AuthenticationState currentState = existing
                .map(SessionSecurityState::authenticationState)
                .orElse(AuthenticationState.ANONYMOUS);
        AuthenticationState nextState = stateMachine.transition(
                currentState,
                passwordVerifiedEvent
        );

        Instant now = clock.instant();
        Instant challengeExpiresAt =
                now.plus(properties.getChallengeTimeout());
        session.setMaxInactiveInterval(properties.idleTimeoutSeconds());

        if (!repository.createChallenge(
                session.getId(),
                userId,
                nextState,
                now,
                challengeExpiresAt
        )) {
            throw unauthenticated();
        }

        return new ChallengeSession(nextState, challengeExpiresAt);
    }

    @Transactional
    public ChallengeSession continueAfterInitialPasswordChange(
            HttpServletRequest request,
            UUID userId
    ) {
        HttpSession session = requireCurrentSession(request);
        SessionSecurityState current = requireUsableState(session, userId);
        AuthenticationState nextState = stateMachine.transition(
                current.authenticationState(),
                AuthenticationEvent.INITIAL_PASSWORD_CHANGED
        );
        Instant now = clock.instant();

        if (!repository.transitionChallenge(
                session.getId(),
                userId,
                current.authenticationState(),
                nextState,
                now
        )) {
            throw unauthenticated();
        }

        return new ChallengeSession(nextState, current.challengeExpiresAt());
    }

    @Transactional
    public AuthenticatedSession completeMfa(
            HttpServletRequest request,
            HttpServletResponse response,
            UUID userId,
            Authentication authentication
    ) {
        HttpSession session = requireCurrentSession(request);
        String previousSessionId = session.getId();
        SessionSecurityState current = requireUsableState(session, userId);
        AuthenticationState nextState = stateMachine.transition(
                current.authenticationState(),
                AuthenticationEvent.MFA_VERIFIED
        );
        requireInternalPrincipal(userId, authentication);

        Instant now = clock.instant();
        Instant absoluteExpiresAt =
                now.plus(properties.getAbsoluteTimeout());
        if (!repository.promoteAuthenticated(
                previousSessionId,
                userId,
                current.authenticationState(),
                now,
                absoluteExpiresAt,
                properties.idleTimeoutSeconds()
        )) {
            throw unauthenticated();
        }

        try {
            session.setMaxInactiveInterval(properties.idleTimeoutSeconds());
            request.changeSessionId();

            SecurityContext securityContext =
                    SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authentication);
            SecurityContextHolder.setContext(securityContext);
            securityContextRepository.saveContext(
                    securityContext,
                    request,
                    response
            );

            csrfTokenRepository.saveToken(null, request, response);
            CsrfToken replacement =
                    csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(replacement, request, response);
            // Spring Security 6의 기본 XOR 처리기를 다시 적용해, 저장소의 원시
            // 토큰이 아니라 다음 요청에서 실제로 검증 가능한 마스킹 값을 노출한다.
            csrfTokenRequestHandler.handle(
                    request,
                    response,
                    () -> replacement
            );
            CsrfToken responseToken = requireResponseCsrfToken(request);

            return new AuthenticatedSession(
                    nextState,
                    absoluteExpiresAt,
                    responseToken.getToken()
            );
        } catch (RuntimeException exception) {
            invalidateQuietly(session);
            SecurityContextHolder.clearContext();
            throw exception;
        }
    }

    private static CsrfToken requireResponseCsrfToken(
            HttpServletRequest request
    ) {
        Object token = request.getAttribute(CsrfToken.class.getName());
        if (token instanceof CsrfToken csrfToken) {
            return csrfToken;
        }
        throw new IllegalStateException(
                "CSRF request handler did not expose a replacement token");
    }

    @Transactional(readOnly = true)
    public Optional<SessionSecurityState> findCurrentState(
            HttpServletRequest request
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        return repository.findBySessionId(session.getId())
                .filter(state -> !state.isExpiredAt(clock.instant()));
    }

    @Transactional
    public ChallengeFailure recordChallengeFailure(
            HttpServletRequest request,
            UUID userId
    ) {
        HttpSession session = requireCurrentSession(request);
        SessionSecurityState current = requireUsableState(session, userId);
        if (current.authenticationState().isFullyAuthenticated()
                || current.authenticationState()
                    == AuthenticationState.ANONYMOUS) {
            throw unauthenticated();
        }

        int maximumFailures = properties.getMaximumChallengeFailures();
        int failureCount = repository.incrementChallengeFailure(
                session.getId(),
                userId,
                current.authenticationState(),
                maximumFailures,
                clock.instant()
        ).orElseThrow(AuthenticationSessionService::unauthenticated);
        boolean invalidated = failureCount >= maximumFailures;
        if (invalidated) {
            repository.deleteBySessionId(session.getId());
            invalidateQuietly(session);
            SecurityContextHolder.clearContext();
        }

        return new ChallengeFailure(
                failureCount,
                Math.max(0, maximumFailures - failureCount),
                invalidated
        );
    }

    @Transactional
    public void revokeCurrentServerSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        // Spring Session 행 삭제와 HttpSession.invalidate()를 같은 DB 트랜잭션에서
        // 실행하면 JDBC 세션 저장소의 별도 삭제가 현재 잠금을 기다릴 수 있다.
        // 여기서는 서버 행 삭제만 커밋하고 클라이언트 세션 만료는 호출자가 뒤이어 한다.
        repository.deleteBySessionId(session.getId());
    }

    @Transactional
    public int invalidateSession(String sessionId) {
        return repository.deleteBySessionId(sessionId);
    }

    @Transactional
    public int invalidateAllUserSessions(UUID userId) {
        return repository.deleteAllByUserId(userId);
    }

    /**
     * 초기 비밀번호 변경 흐름을 이어갈 현재 pre-auth challenge만 남긴다.
     */
    @Transactional
    public int invalidateOtherUserSessions(
            UUID userId,
            HttpServletRequest currentRequest
    ) {
        HttpSession currentSession = requireCurrentSession(currentRequest);
        requireUsableState(currentSession, userId);
        return repository.deleteOtherByUserId(
                currentSession.getId(),
                userId
        );
    }

    @Transactional
    public int invalidateAllUserSessions(
            UUID userId,
            HttpServletRequest currentRequest
    ) {
        HttpSession currentSession = currentRequest.getSession(false);
        if (currentSession != null) {
            Optional<SessionSecurityState> state =
                    repository.findBySessionId(currentSession.getId());
            if (state.filter(value -> value.belongsTo(userId)).isPresent()) {
                invalidateQuietly(currentSession);
                SecurityContextHolder.clearContext();
            }
        }
        return repository.deleteAllByUserId(userId);
    }

    /**
     * 사용자 세션 row가 이미 원자적으로 삭제된 뒤 현재 요청의 브라우저 세션만 끝낸다.
     */
    public void expireCurrentClientSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            invalidateQuietly(session);
        }
        SecurityContextHolder.clearContext();
    }

    private SessionSecurityState requireUsableState(
            HttpSession session,
            UUID userId
    ) {
        SessionSecurityState state = repository.findBySessionId(session.getId())
                .orElseThrow(AuthenticationSessionService::unauthenticated);
        if (!state.belongsTo(userId) || state.isExpiredAt(clock.instant())) {
            throw unauthenticated();
        }
        return state;
    }

    private static HttpSession requireCurrentSession(
            HttpServletRequest request
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw unauthenticated();
        }
        return session;
    }

    private static void requirePasswordVerifiedEvent(
            AuthenticationEvent event
    ) {
        if (event != AuthenticationEvent.PASSWORD_VERIFIED_CHANGE_REQUIRED
                && event != AuthenticationEvent
                .PASSWORD_VERIFIED_MFA_ENROLLMENT_REQUIRED
                && event != AuthenticationEvent
                .PASSWORD_VERIFIED_MFA_REQUIRED) {
            throw new IllegalArgumentException(
                    "A password-verified event is required");
        }
    }

    private static void requireInternalPrincipal(
            UUID userId,
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !userId.toString().equals(authentication.getName())) {
            throw new IllegalArgumentException(
                    "Authenticated principal must use the internal user ID");
        }
    }

    private static void invalidateQuietly(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // 이미 폐기된 세션을 다시 저장하지 않는 것이 목적이므로 별도 처리가 필요 없다.
        }
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
    }

    public record ChallengeSession(
            AuthenticationState authenticationState,
            Instant challengeExpiresAt
    ) {
    }

    public record AuthenticatedSession(
            AuthenticationState authenticationState,
            Instant absoluteExpiresAt,
            String csrfToken
    ) {
    }

    public record ChallengeFailure(
            int failureCount,
            int remainingAttempts,
            boolean sessionInvalidated
    ) {
    }
}
