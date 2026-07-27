package com.gilbom.auth.mfa;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.AuthenticationDefenseService;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.mfa.AuthenticationUserRepository.AuthenticationUser;
import com.gilbom.auth.mfa.MfaFactorRepository.RecoveryCodeRecord;
import com.gilbom.auth.mfa.RecoveryCodeService.GeneratedRecoveryCode;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.AuthenticationSessionService.AuthenticatedSession;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class MfaAuthenticationCompletionService {

    private final MfaFactorRepository factorRepository;
    private final AuthenticationUserRepository userRepository;
    private final AuthenticationSessionService sessionService;
    private final AuthenticationAuditService auditService;
    private final AuthenticationDefenseService defenseService;

    public MfaAuthenticationCompletionService(
            MfaFactorRepository factorRepository,
            AuthenticationUserRepository userRepository,
            AuthenticationSessionService sessionService,
            AuthenticationAuditService auditService,
            AuthenticationDefenseService defenseService
    ) {
        this.factorRepository = factorRepository;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.defenseService = defenseService;
    }

    @Transactional
    public MfaEnrollmentConfirmationResponse completeEnrollment(
            HttpServletRequest request,
            HttpServletResponse response,
            UUID userId,
            UUID factorId,
            long successfulTimeStep,
            List<GeneratedRecoveryCode> recoveryCodes,
            AuthenticationRequestAuditContext auditContext
    ) {
        requireChallenge(
                request,
                userId,
                AuthenticationState.MFA_ENROLLMENT_REQUIRED
        );
        if (factorRepository.activatePendingFactor(
                userId,
                factorId,
                successfulTimeStep
        ) != 1) {
            throw unauthenticated();
        }
        if (factorRepository.insertRecoveryCodeHashes(
                factorId,
                recoveryCodes
        ) != RecoveryCodeService.CODE_COUNT) {
            throw new IllegalStateException(
                    "Recovery code persistence did not complete"
            );
        }

        auditService.recordMfaEnrolledRequired(auditContext, userId);
        CompletionSnapshot snapshot = completeAuthentication(
                request,
                response,
                userId,
                auditContext
        );
        return new MfaEnrollmentConfirmationResponse(
                snapshot.user().toResponse(),
                snapshot.session().csrfToken(),
                snapshot.session().absoluteExpiresAt(),
                false,
                recoveryCodes.stream()
                        .map(GeneratedRecoveryCode::rawCode)
                        .toList()
        );
    }

    @Transactional
    public AuthenticationResponse completeTotp(
            HttpServletRequest request,
            HttpServletResponse response,
            UUID userId,
            UUID factorId,
            long successfulTimeStep,
            AuthenticationRequestAuditContext auditContext
    ) {
        requireChallenge(
                request,
                userId,
                AuthenticationState.MFA_REQUIRED
        );
        if (factorRepository.advanceActiveTotpTimeStep(
                userId,
                factorId,
                successfulTimeStep
        ) != 1) {
            // 같은 step의 동시 요청은 첫 요청만 성공하고 나머지는 여기서 거부된다.
            throw unauthenticated();
        }

        CompletionSnapshot snapshot = completeAuthentication(
                request,
                response,
                userId,
                auditContext
        );
        return authenticationResponse(snapshot, false);
    }

    @Transactional
    public AuthenticationResponse completeRecovery(
            HttpServletRequest request,
            HttpServletResponse response,
            UUID userId,
            UUID factorId,
            RecoveryCodeRecord recoveryCode,
            AuthenticationRequestAuditContext auditContext
    ) {
        requireChallenge(
                request,
                userId,
                AuthenticationState.MFA_REQUIRED
        );
        if (factorRepository.consumeRecoveryCode(
                userId,
                factorId,
                recoveryCode.recoveryCodeId()
        ) != 1) {
            // used_at IS NULL 조건이 일회성 소비를 원자적으로 보장한다.
            throw unauthenticated();
        }

        auditService.recordRecoveryCodeUsedRequired(
                auditContext,
                userId
        );
        CompletionSnapshot snapshot = completeAuthentication(
                request,
                response,
                userId,
                auditContext
        );
        return authenticationResponse(snapshot, true);
    }

    private CompletionSnapshot completeAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            UUID userId,
            AuthenticationRequestAuditContext auditContext
    ) {
        AuthenticationUser user = userRepository.findActiveUser(userId)
                .orElseThrow(
                        MfaAuthenticationCompletionService::unauthenticated
                );
        Authentication authentication = UsernamePasswordAuthenticationToken
                .authenticated(
                        user.userId().toString(),
                        null,
                        user.permissions().stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                );
        AuthenticatedSession session =
                defenseService.completeMfaAndRecordLogin(
                        request,
                        response,
                        userId,
                        authentication,
                        auditContext
                );
        if (session.authenticationState()
                != AuthenticationState.AUTHENTICATED) {
            throw new IllegalStateException(
                    "MFA completion did not produce an authenticated session"
            );
        }
        return new CompletionSnapshot(user, session);
    }

    private void requireChallenge(
            HttpServletRequest request,
            UUID userId,
            AuthenticationState expectedState
    ) {
        SessionSecurityState state =
                sessionService.findCurrentState(request)
                        .orElseThrow(
                                MfaAuthenticationCompletionService
                                        ::unauthenticated
                        );
        if (!state.belongsTo(userId)
                || state.authenticationState() != expectedState) {
            throw unauthenticated();
        }
    }

    private static AuthenticationResponse authenticationResponse(
            CompletionSnapshot snapshot,
            boolean recoveryCodeUsed
    ) {
        return new AuthenticationResponse(
                snapshot.user().toResponse(),
                snapshot.session().csrfToken(),
                snapshot.session().absoluteExpiresAt(),
                recoveryCodeUsed
        );
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(
                ErrorCode.AUTH_UNAUTHENTICATED,
                "인증 정보가 올바르지 않습니다."
        );
    }

    private record CompletionSnapshot(
            AuthenticationUser user,
            AuthenticatedSession session
    ) {
    }
}
