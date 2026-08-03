package com.gilbom.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.account.AccountLoginProtectionRepository;
import com.gilbom.auth.account.AccountLoginProtectionRepository.AccountFailureState;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.model.AuthenticationEvent;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitProperties;
import com.gilbom.auth.ratelimit.AuthenticationRateLimiter;
import com.gilbom.auth.ratelimit.PersistentRateLimitRepository.FailureRateLimitDecision;
import com.gilbom.auth.ratelimit.RateLimitDecision;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.AuthenticationSessionService.AuthenticatedSession;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeFailure;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeSession;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 로그인·MFA API가 인증 입력 검증 전후에 호출하는 공통 방어 orchestration이다.
 */
@Service
public class AuthenticationDefenseService {

    private final AuthenticationRateLimitProperties properties;
    private final AuthenticationRateLimiter rateLimiter;
    private final AccountLoginProtectionRepository accountRepository;
    private final AuthenticationAuditService auditService;
    private final AuthenticationSessionService sessionService;

    public AuthenticationDefenseService(
            AuthenticationRateLimitProperties properties,
            AuthenticationRateLimiter rateLimiter,
            AccountLoginProtectionRepository accountRepository,
            AuthenticationAuditService auditService,
            AuthenticationSessionService sessionService
    ) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.sessionService = sessionService;
    }

    public RateLimitDecision inspectPasswordAttempt(
            String email
    ) {
        return rateLimiter.inspectPasswordFailures(email);
    }

    @Transactional
    public PasswordFailureOutcome recordPasswordFailure(
            String email,
            UUID knownTargetUserId,
            AuthenticationRequestAuditContext context
    ) {
        FailureRateLimitDecision rateLimit =
                rateLimiter.recordPasswordFailure(email);
        Optional<AccountFailureState> accountState =
                knownTargetUserId == null
                        ? Optional.empty()
                        : accountRepository.recordPasswordFailure(
                                knownTargetUserId,
                                properties.getPasswordMaximumFailures(),
                                properties.getPasswordFailureWindow(),
                                properties.getTemporaryLockDuration()
                        );

        auditService.recordLoginFailedRequired(context, knownTargetUserId);
        boolean accountLocked = accountState
                .map(AccountFailureState::temporarilyLocked)
                .orElse(false);
        if (accountLocked) {
            auditService.recordAccountLockedRequired(
                    context,
                    knownTargetUserId
            );
        }

        return new PasswordFailureOutcome(
                rateLimit.failureCount(),
                rateLimit.retryAfterSeconds(),
                accountLocked
        );
    }

    @Transactional
    public ChallengeSession completePasswordVerification(
            String email,
            UUID userId,
            AuthenticationEvent passwordVerifiedEvent,
            HttpServletRequest request,
            AuthenticationRequestAuditContext context
    ) {
        rateLimiter.resetPasswordFailures(email);
        if (accountRepository.resetPasswordFailures(userId) != 1) {
            throw unauthenticated();
        }
        auditService.recordPasswordVerifiedRequired(context, userId);
        return sessionService.beginChallenge(
                request,
                userId,
                passwordVerifiedEvent
        );
    }

    public RateLimitDecision inspectMfaAttempt(
            UUID userId
    ) {
        return rateLimiter.inspectMfaFailures(userId);
    }

    @Transactional
    public MfaFailureOutcome recordMfaFailure(
            HttpServletRequest request,
            UUID userId,
            AuthenticationRequestAuditContext context
    ) {
        // 필수 감사 insert가 실패하면 challenge 상태를 변경하지 않는다.
        auditService.recordMfaChallengeFailedRequired(context, userId);
        ChallengeFailure challengeFailure =
                sessionService.recordChallengeFailure(request, userId);
        FailureRateLimitDecision rateLimit =
                rateLimiter.recordMfaFailure(userId);

        return new MfaFailureOutcome(
                challengeFailure.failureCount(),
                challengeFailure.remainingAttempts(),
                challengeFailure.sessionInvalidated(),
                rateLimit.retryAfterSeconds()
        );
    }

    @Transactional
    public AuthenticatedSession completeMfaAndRecordLogin(
            HttpServletRequest request,
            HttpServletResponse response,
            UUID userId,
            Authentication authentication,
            AuthenticationRequestAuditContext context
    ) {
        if (accountRepository.recordCompletedLogin(userId) != 1) {
            throw unauthenticated();
        }
        rateLimiter.resetMfaFailures(userId);
        // 성공 감사가 저장돼야만 세션 승격·ID/CSRF 교체를 수행한다.
        auditService.recordLoginSucceededRequired(context, userId);
        return sessionService.completeMfa(
                request,
                response,
                userId,
                authentication
        );
    }

    @Transactional
    public AuthenticatedSession completeMfaBypassAndRecordLogin(
            HttpServletRequest request,
            HttpServletResponse response,
            UUID userId,
            Authentication authentication,
            AuthenticationRequestAuditContext context
    ) {
        if (accountRepository.recordCompletedLogin(userId) != 1) {
            throw unauthenticated();
        }
        rateLimiter.resetMfaFailures(userId);
        auditService.recordLoginSucceededRequired(context, userId);
        return sessionService.completeMfaBypass(
                request,
                response,
                userId,
                authentication
        );
    }

    public void logout(
            HttpServletRequest request,
            UUID userId,
            AuthenticationRequestAuditContext context
    ) {
        // 로그아웃 감사 장애가 세션 폐기를 되돌리지 못하게 폐기를 먼저 확정한다.
        sessionService.revokeCurrentServerSession(request);
        sessionService.expireCurrentClientSession(request);
        auditService.recordLogoutBestEffort(context, userId);
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
    }

    public record PasswordFailureOutcome(
            int failureCount,
            long retryAfterSeconds,
            boolean accountTemporarilyLocked
    ) {
    }

    public record MfaFailureOutcome(
            int challengeFailureCount,
            int remainingChallengeAttempts,
            boolean challengeInvalidated,
            long retryAfterSeconds
    ) {
    }
}
