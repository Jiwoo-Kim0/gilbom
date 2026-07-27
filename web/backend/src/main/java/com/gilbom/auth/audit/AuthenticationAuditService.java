package com.gilbom.auth.audit;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.audit.AuthenticationAuditAction.FailurePolicy;
import com.gilbom.auth.audit.AuthenticationAuditEvent.ActorType;
import com.gilbom.auth.audit.AuthenticationAuditEvent.Result;
import com.gilbom.auth.audit.AuthenticationAuditEvent.TargetType;

@Service
public class AuthenticationAuditService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuthenticationAuditService.class);

    private final AuthenticationAuditRepository repository;
    private final BestEffortAuthenticationAuditWriter bestEffortWriter;

    public AuthenticationAuditService(
            AuthenticationAuditRepository repository,
            BestEffortAuthenticationAuditWriter bestEffortWriter
    ) {
        this.repository = repository;
        this.bestEffortWriter = bestEffortWriter;
    }

    @Transactional
    public void recordLoginSucceededRequired(
            AuthenticationRequestAuditContext context,
            UUID userId
    ) {
        appendRequired(event(
                context,
                userId,
                ActorType.USER,
                AuthenticationAuditAction.LOGIN_SUCCEEDED,
                TargetType.USER,
                userId,
                Result.SUCCESS
        ));
    }

    @Transactional
    public void recordLoginFailedRequired(
            AuthenticationRequestAuditContext context,
            UUID knownTargetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.USER,
                AuthenticationAuditAction.LOGIN_FAILED,
                TargetType.USER,
                knownTargetUserId,
                Result.FAILURE
        ));
    }

    @Transactional
    public void recordPasswordVerifiedRequired(
            AuthenticationRequestAuditContext context,
            UUID targetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.USER,
                AuthenticationAuditAction.PASSWORD_VERIFIED,
                TargetType.USER,
                targetUserId,
                Result.SUCCESS
        ));
    }

    @Transactional
    public void recordAccountLockedRequired(
            AuthenticationRequestAuditContext context,
            UUID targetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.SYSTEM,
                AuthenticationAuditAction.ACCOUNT_TEMPORARILY_LOCKED,
                TargetType.USER,
                targetUserId,
                Result.DENIED
        ));
    }

    @Transactional
    public void recordMfaChallengeFailedRequired(
            AuthenticationRequestAuditContext context,
            UUID targetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.USER,
                AuthenticationAuditAction.MFA_CHALLENGE_FAILED,
                TargetType.USER,
                targetUserId,
                Result.FAILURE
        ));
    }

    @Transactional
    public void recordMfaEnrollmentStartedRequired(
            AuthenticationRequestAuditContext context,
            UUID targetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.USER,
                AuthenticationAuditAction.MFA_ENROLLMENT_STARTED,
                TargetType.USER,
                targetUserId,
                Result.SUCCESS
        ));
    }

    @Transactional
    public void recordMfaEnrolledRequired(
            AuthenticationRequestAuditContext context,
            UUID targetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.USER,
                AuthenticationAuditAction.MFA_ENROLLED,
                TargetType.USER,
                targetUserId,
                Result.SUCCESS
        ));
    }

    @Transactional
    public void recordRecoveryCodeUsedRequired(
            AuthenticationRequestAuditContext context,
            UUID targetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.USER,
                AuthenticationAuditAction.RECOVERY_CODE_USED,
                TargetType.USER,
                targetUserId,
                Result.SUCCESS
        ));
    }

    @Transactional
    public void recordInitialPasswordChangedRequired(
            AuthenticationRequestAuditContext context,
            UUID targetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.USER,
                AuthenticationAuditAction.INITIAL_PASSWORD_CHANGED,
                TargetType.USER,
                targetUserId,
                Result.SUCCESS
        ));
    }

    @Transactional
    public void recordInitialPasswordChangeFailedRequired(
            AuthenticationRequestAuditContext context,
            UUID knownTargetUserId
    ) {
        appendRequired(event(
                context,
                null,
                ActorType.USER,
                AuthenticationAuditAction.INITIAL_PASSWORD_CHANGE_FAILED,
                TargetType.USER,
                knownTargetUserId,
                Result.FAILURE
        ));
    }

    @Transactional
    public void recordPasswordChangedRequired(
            AuthenticationRequestAuditContext context,
            UUID userId
    ) {
        appendRequired(event(
                context,
                userId,
                ActorType.USER,
                AuthenticationAuditAction.PASSWORD_CHANGED,
                TargetType.USER,
                userId,
                Result.SUCCESS
        ));
    }

    @Transactional
    public void recordPasswordChangeFailedRequired(
            AuthenticationRequestAuditContext context,
            UUID userId
    ) {
        appendRequired(event(
                context,
                userId,
                ActorType.USER,
                AuthenticationAuditAction.PASSWORD_CHANGE_FAILED,
                TargetType.USER,
                userId,
                Result.FAILURE
        ));
    }

    @Transactional
    public void recordSessionRevokedRequired(
            AuthenticationRequestAuditContext context,
            UUID actorUserId,
            UUID targetUserId
    ) {
        appendRequired(event(
                context,
                actorUserId,
                ActorType.USER,
                AuthenticationAuditAction.SESSION_REVOKED,
                TargetType.USER,
                targetUserId,
                Result.SUCCESS
        ));
    }

    public void recordRateLimitedBestEffort(
            AuthenticationRequestAuditContext context,
            UUID knownTargetUserId
    ) {
        appendBestEffort(event(
                context,
                null,
                ActorType.SYSTEM,
                AuthenticationAuditAction.RATE_LIMITED,
                TargetType.USER,
                knownTargetUserId,
                Result.DENIED
        ));
    }

    public void recordLogoutBestEffort(
            AuthenticationRequestAuditContext context,
            UUID actorUserId
    ) {
        appendBestEffort(event(
                context,
                actorUserId,
                ActorType.USER,
                AuthenticationAuditAction.LOGOUT,
                TargetType.SESSION,
                null,
                Result.SUCCESS
        ));
    }

    @Transactional
    public void appendRequired(AuthenticationAuditEvent event) {
        requirePolicy(event, FailurePolicy.REQUIRED);
        repository.append(event);
    }

    public void appendBestEffort(AuthenticationAuditEvent event) {
        requirePolicy(event, FailurePolicy.BEST_EFFORT);
        try {
            bestEffortWriter.appendInIndependentTransaction(event);
        } catch (RuntimeException exception) {
            // 예외 원문은 SQL·credential을 포함할 수 있으므로 requestId만 기록한다.
            LOGGER.error(
                    "Best-effort authentication audit was unavailable. requestId={}",
                    event.requestContext().requestId()
            );
        }
    }

    private static AuthenticationAuditEvent event(
            AuthenticationRequestAuditContext context,
            UUID actorUserId,
            ActorType actorType,
            AuthenticationAuditAction action,
            TargetType targetType,
            UUID targetId,
            Result result
    ) {
        return new AuthenticationAuditEvent(
                context,
                actorUserId,
                actorType,
                action,
                targetType,
                targetId,
                result
        );
    }

    private static void requirePolicy(
            AuthenticationAuditEvent event,
            FailurePolicy expected
    ) {
        if (event.action().failurePolicy() != expected) {
            throw new IllegalArgumentException(
                    "Audit action failure policy mismatch");
        }
    }
}
