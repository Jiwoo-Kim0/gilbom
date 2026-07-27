package com.gilbom.auth.mfa;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.mfa.crypto.EncryptedTotpSecret;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class MfaEnrollmentCommitService {

    private final MfaFactorRepository factorRepository;
    private final AuthenticationSessionService sessionService;
    private final AuthenticationAuditService auditService;

    public MfaEnrollmentCommitService(
            MfaFactorRepository factorRepository,
            AuthenticationSessionService sessionService,
            AuthenticationAuditService auditService
    ) {
        this.factorRepository = factorRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
    }

    /**
     * 기존 미확인 factor 교체와 새 암호문 저장, 필수 감사를 한 트랜잭션으로 묶는다.
     */
    @Transactional
    public void commit(
            HttpServletRequest request,
            UUID userId,
            UUID factorId,
            EncryptedTotpSecret encryptedSecret,
            AuthenticationRequestAuditContext auditContext
    ) {
        requireChallenge(
                request,
                userId,
                AuthenticationState.MFA_ENROLLMENT_REQUIRED
        );
        if (factorRepository.hasActiveTotpFactor(userId)) {
            throw unauthenticated();
        }

        factorRepository.revokePendingTotpFactors(userId);
        if (factorRepository.insertPendingTotpFactor(
                factorId,
                userId,
                encryptedSecret
        ) != 1) {
            // 예외로 rollback시켜 직전에 폐기한 pending factor도 복원한다.
            throw unauthenticated();
        }
        auditService.recordMfaEnrollmentStartedRequired(
                auditContext,
                userId
        );
    }

    private void requireChallenge(
            HttpServletRequest request,
            UUID userId,
            AuthenticationState expectedState
    ) {
        SessionSecurityState state =
                sessionService.findCurrentState(request)
                        .orElseThrow(
                                MfaEnrollmentCommitService::unauthenticated
                        );
        if (!state.belongsTo(userId)
                || state.authenticationState() != expectedState) {
            throw unauthenticated();
        }
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(
                ErrorCode.AUTH_UNAUTHENTICATED,
                "인증 정보가 올바르지 않습니다."
        );
    }
}
