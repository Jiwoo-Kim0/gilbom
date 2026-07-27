package com.gilbom.auth.mfa;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gilbom.auth.AuthenticationDefenseService;
import com.gilbom.auth.AuthenticationDefenseService.MfaFailureOutcome;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.mfa.MfaFactorRepository.EnrollmentAccount;
import com.gilbom.auth.mfa.MfaFactorRepository.MfaFactor;
import com.gilbom.auth.mfa.MfaFactorRepository.RecoveryCodeRecord;
import com.gilbom.auth.mfa.RecoveryCodeService.GeneratedRecoveryCode;
import com.gilbom.auth.mfa.TotpService.GeneratedTotpSecret;
import com.gilbom.auth.mfa.crypto.EncryptedTotpSecret;
import com.gilbom.auth.mfa.crypto.TotpSecretCryptoException;
import com.gilbom.auth.mfa.crypto.TotpSecretEncryptionService;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitExceededException;
import com.gilbom.auth.ratelimit.RateLimitDecision;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class MfaAuthenticationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MfaAuthenticationService.class);
    private static final String INVALID_CREDENTIALS_MESSAGE =
            "인증 정보가 올바르지 않습니다.";

    private final MfaFactorRepository factorRepository;
    private final AuthenticationSessionService sessionService;
    private final TotpService totpService;
    private final TotpSecretEncryptionService encryptionService;
    private final RecoveryCodeService recoveryCodeService;
    private final AuthenticationDefenseService defenseService;
    private final AuthenticationAuditService auditService;
    private final MfaEnrollmentCommitService enrollmentCommitService;
    private final MfaAuthenticationCompletionService completionService;
    private final Clock clock;

    public MfaAuthenticationService(
            MfaFactorRepository factorRepository,
            AuthenticationSessionService sessionService,
            TotpService totpService,
            TotpSecretEncryptionService encryptionService,
            RecoveryCodeService recoveryCodeService,
            AuthenticationDefenseService defenseService,
            AuthenticationAuditService auditService,
            MfaEnrollmentCommitService enrollmentCommitService,
            MfaAuthenticationCompletionService completionService,
            Clock clock
    ) {
        this.factorRepository = factorRepository;
        this.sessionService = sessionService;
        this.totpService = totpService;
        this.encryptionService = encryptionService;
        this.recoveryCodeService = recoveryCodeService;
        this.defenseService = defenseService;
        this.auditService = auditService;
        this.enrollmentCommitService = enrollmentCommitService;
        this.completionService = completionService;
        this.clock = clock;
    }

    public TotpEnrollmentResponse enroll(HttpServletRequest request) {
        AuthenticationRequestAuditContext auditContext =
                AuthenticationRequestAuditContext.from(request);
        SessionSecurityState state = requireChallenge(
                request,
                AuthenticationState.MFA_ENROLLMENT_REQUIRED
        );
        UUID userId = state.userId();
        EnrollmentAccount account =
                factorRepository.findEligibleAccount(userId)
                        .filter(ignored ->
                                !factorRepository.hasActiveTotpFactor(userId))
                        .orElseThrow(
                                MfaAuthenticationService::unauthenticated
                        );

        UUID factorId = UUID.randomUUID();
        GeneratedTotpSecret generatedSecret =
                totpService.generateSecret();
        try {
            EncryptedTotpSecret encryptedSecret = encryptSafely(
                    generatedSecret.rawSecret(),
                    userId,
                    factorId,
                    auditContext
            );
            enrollmentCommitService.commit(
                    request,
                    userId,
                    factorId,
                    encryptedSecret,
                    auditContext
            );
            return new TotpEnrollmentResponse(
                    totpService.buildOtpAuthUri(
                            account.email(),
                            generatedSecret.base32Secret()
                    ),
                    generatedSecret.base32Secret(),
                    state.challengeExpiresAt()
            );
        } finally {
            Arrays.fill(generatedSecret.rawSecret(), (byte) 0);
        }
    }

    public MfaEnrollmentConfirmationResponse confirmEnrollment(
            TotpCodeRequest codeRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthenticationRequestAuditContext auditContext =
                AuthenticationRequestAuditContext.from(request);
        SessionSecurityState state = requireChallenge(
                request,
                AuthenticationState.MFA_ENROLLMENT_REQUIRED
        );
        UUID userId = state.userId();
        requireMfaAttemptAllowed(userId, auditContext);

        Optional<MfaFactor> factor = factorRepository.findCurrentTotpFactor(
                userId,
                MfaFactorStatus.PENDING
        );
        if (factor.isEmpty()) {
            rejectMfa(request, userId, auditContext);
        }

        MfaFactor pendingFactor = factor.orElseThrow();
        OptionalLong successfulStep = verifyTotpSafely(
                pendingFactor,
                codeRequest.code(),
                auditContext
        );
        if (successfulStep.isEmpty()) {
            rejectMfa(request, userId, auditContext);
        }

        List<GeneratedRecoveryCode> recoveryCodes =
                generateRecoveryCodesSafely(auditContext);
        return completionService.completeEnrollment(
                request,
                response,
                userId,
                pendingFactor.factorId(),
                successfulStep.orElseThrow(),
                recoveryCodes,
                auditContext
        );
    }

    public AuthenticationResponse verifyTotp(
            TotpCodeRequest codeRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthenticationRequestAuditContext auditContext =
                AuthenticationRequestAuditContext.from(request);
        SessionSecurityState state = requireChallenge(
                request,
                AuthenticationState.MFA_REQUIRED
        );
        UUID userId = state.userId();
        requireMfaAttemptAllowed(userId, auditContext);

        Optional<MfaFactor> factor = factorRepository.findCurrentTotpFactor(
                userId,
                MfaFactorStatus.ACTIVE
        );
        if (factor.isEmpty()) {
            rejectMfa(request, userId, auditContext);
        }

        MfaFactor activeFactor = factor.orElseThrow();
        OptionalLong successfulStep = verifyTotpSafely(
                activeFactor,
                codeRequest.code(),
                auditContext
        );
        if (successfulStep.isEmpty()) {
            rejectMfa(request, userId, auditContext);
        }

        return completionService.completeTotp(
                request,
                response,
                userId,
                activeFactor.factorId(),
                successfulStep.orElseThrow(),
                auditContext
        );
    }

    public AuthenticationResponse verifyRecoveryCode(
            RecoveryCodeRequest codeRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthenticationRequestAuditContext auditContext =
                AuthenticationRequestAuditContext.from(request);
        SessionSecurityState state = requireChallenge(
                request,
                AuthenticationState.MFA_REQUIRED
        );
        UUID userId = state.userId();
        requireMfaAttemptAllowed(userId, auditContext);

        Optional<MfaFactor> factor = factorRepository.findCurrentTotpFactor(
                userId,
                MfaFactorStatus.ACTIVE
        );
        OptionalInt position = recoveryCodeService.parsePosition(
                codeRequest.recoveryCode()
        );
        Optional<RecoveryCodeRecord> recoveryCode =
                factor.isPresent() && position.isPresent()
                        ? factorRepository.findRecoveryCode(
                                factor.get().factorId(),
                                position.getAsInt()
                        )
                        : Optional.empty();

        boolean hashMatches = verifyRecoveryCodeSafely(
                codeRequest.recoveryCode(),
                recoveryCode.map(RecoveryCodeRecord::encodedHash)
                        .orElse(null),
                auditContext
        );
        boolean available = recoveryCode
                .filter(value -> value.isAvailableAt(clock.instant()))
                .isPresent();
        if (factor.isEmpty() || !hashMatches || !available) {
            rejectMfa(request, userId, auditContext);
        }

        return completionService.completeRecovery(
                request,
                response,
                userId,
                factor.orElseThrow().factorId(),
                recoveryCode.orElseThrow(),
                auditContext
        );
    }

    private SessionSecurityState requireChallenge(
            HttpServletRequest request,
            AuthenticationState expectedState
    ) {
        SessionSecurityState state =
                sessionService.findCurrentState(request)
                        .orElseThrow(
                                MfaAuthenticationService::unauthenticated
                        );
        if (state.userId() == null
                || state.authenticationState() != expectedState) {
            throw unauthenticated();
        }
        return state;
    }

    private EncryptedTotpSecret encryptSafely(
            byte[] secret,
            UUID userId,
            UUID factorId,
            AuthenticationRequestAuditContext auditContext
    ) {
        try {
            return encryptionService.encrypt(
                    userId,
                    factorId,
                    secret
            );
        } catch (TotpSecretCryptoException | IllegalStateException exception) {
            logMfaOperationalFailure(
                    auditContext,
                    "TOTP_SECRET_ENCRYPTION"
            );
            throw unavailable();
        }
    }

    private OptionalLong verifyTotpSafely(
            MfaFactor factor,
            String submittedCode,
            AuthenticationRequestAuditContext auditContext
    ) {
        byte[] plaintextSecret = null;
        try {
            plaintextSecret = encryptionService.decrypt(
                    factor.userId(),
                    factor.factorId(),
                    factor.encryptedSecret()
            );
            return totpService.verify(
                    plaintextSecret,
                    submittedCode,
                    clock.instant(),
                    factor.lastSuccessfulTimeStep()
            );
        } catch (TotpSecretCryptoException
                | TotpComputationException
                | IllegalStateException exception) {
            logMfaOperationalFailure(
                    auditContext,
                    "TOTP_SECRET_VERIFICATION"
            );
            throw unavailable();
        } finally {
            if (plaintextSecret != null) {
                Arrays.fill(plaintextSecret, (byte) 0);
            }
        }
    }

    private List<GeneratedRecoveryCode> generateRecoveryCodesSafely(
            AuthenticationRequestAuditContext auditContext
    ) {
        try {
            return recoveryCodeService.generateCodes();
        } catch (RuntimeException exception) {
            logMfaOperationalFailure(
                    auditContext,
                    "RECOVERY_CODE_HASH_CONFIGURATION"
            );
            throw unavailable();
        }
    }

    private boolean verifyRecoveryCodeSafely(
            String submittedCode,
            String storedHash,
            AuthenticationRequestAuditContext auditContext
    ) {
        try {
            return recoveryCodeService.verify(
                    submittedCode,
                    storedHash
            );
        } catch (RuntimeException exception) {
            // hash 형식 오류를 외부에서 계정·코드 상태와 구별할 수 없게 실패로 축약한다.
            logMfaOperationalFailure(
                    auditContext,
                    "RECOVERY_CODE_HASH_CONFIGURATION"
            );
            return false;
        }
    }

    private void requireMfaAttemptAllowed(
            UUID userId,
            AuthenticationRequestAuditContext auditContext
    ) {
        RateLimitDecision decision =
                defenseService.inspectMfaAttempt(userId);
        if (!decision.allowed()) {
            auditService.recordRateLimitedBestEffort(
                    auditContext,
                    userId
            );
            throw new AuthenticationRateLimitExceededException(
                    decision.retryAfterSeconds()
            );
        }
    }

    private void rejectMfa(
            HttpServletRequest request,
            UUID userId,
            AuthenticationRequestAuditContext auditContext
    ) {
        MfaFailureOutcome outcome = defenseService.recordMfaFailure(
                request,
                userId,
                auditContext
        );
        if (outcome.challengeInvalidated()
                || outcome.retryAfterSeconds() > 0) {
            throw new AuthenticationRateLimitExceededException(
                    Math.max(1, outcome.retryAfterSeconds())
            );
        }
        throw unauthenticated();
    }

    private static void logMfaOperationalFailure(
            AuthenticationRequestAuditContext auditContext,
            String category
    ) {
        // TOTP, 복구 코드, hash, keyring과 예외 원문은 로그에 남기지 않는다.
        LOGGER.error(
                "MFA operation failed safely. requestId={} category={}",
                auditContext.requestId(),
                category
        );
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(
                ErrorCode.AUTH_UNAUTHENTICATED,
                INVALID_CREDENTIALS_MESSAGE
        );
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.AUTH_MFA_UNAVAILABLE);
    }
}
