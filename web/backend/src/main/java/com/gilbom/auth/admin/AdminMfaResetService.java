package com.gilbom.auth.admin;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gilbom.audit.AuditActorType;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditTargetType;
import com.gilbom.auth.admin.AdminDtos.MfaResetRequest;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitExceededException;
import com.gilbom.auth.ratelimit.AuthenticationRateLimiter;
import com.gilbom.auth.ratelimit.RateLimitDecision;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Service
public class AdminMfaResetService {

    private final AdminMfaResetCommitService commitService;
    private final AuthenticationRateLimiter rateLimiter;
    private final AuditLogService auditLogService;

    public AdminMfaResetService(
            AdminMfaResetCommitService commitService,
            AuthenticationRateLimiter rateLimiter,
            AuditLogService auditLogService
    ) {
        this.commitService = commitService;
        this.rateLimiter = rateLimiter;
        this.auditLogService = auditLogService;
    }

    public void reset(
            UUID targetUserId,
            MfaResetRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        if (actorUserId.equals(targetUserId)) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "관리자 초기화 API로 자신의 MFA를 초기화할 수 없습니다."
            );
        }

        RateLimitDecision decision =
                rateLimiter.inspectMfaFailures(actorUserId);
        if (!decision.allowed()) {
            recordFailure(context, actorUserId, targetUserId);
            throw new AuthenticationRateLimitExceededException(
                    decision.retryAfterSeconds()
            );
        }

        boolean completed = commitService.attempt(
                targetUserId,
                request,
                actorUserId,
                context
        );
        if (!completed) {
            var failure = rateLimiter.recordMfaFailure(actorUserId);
            recordFailure(context, actorUserId, targetUserId);
            if (failure.retryAfterSeconds() > 0) {
                throw new AuthenticationRateLimitExceededException(
                        failure.retryAfterSeconds()
                );
            }
            throw new BusinessException(
                    ErrorCode.AUTH_ADMIN_REAUTHENTICATION_FAILED
            );
        }
        rateLimiter.resetMfaFailures(actorUserId);
    }

    private void recordFailure(
            AuditRequestContext context,
            UUID actorUserId,
            UUID targetUserId
    ) {
        auditLogService.appendBestEffort(new AuditLogRecord(
                context,
                actorUserId,
                AuditActorType.USER,
                "ADMIN_MFA_RESET_REAUTH_FAILED",
                AuditTargetType.USER,
                targetUserId,
                null,
                null,
                "ADMIN_REAUTHENTICATION_FAILED",
                AuditResult.DENIED
        ));
    }
}
