package com.gilbom.auth.admin;

import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.audit.AuditActorType;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditSnapshot;
import com.gilbom.audit.AuditTargetType;
import com.gilbom.auth.admin.AdminDtos.MfaResetRequest;
import com.gilbom.auth.admin.AdminRepository.MfaReauthentication;
import com.gilbom.auth.mfa.TotpService;
import com.gilbom.auth.mfa.crypto.TotpSecretEncryptionService;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Service
public class AdminMfaResetCommitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AdminMfaResetCommitService.class
    );

    private final AdminRepository repository;
    private final PasswordHashingService passwordHashingService;
    private final TotpSecretEncryptionService encryptionService;
    private final TotpService totpService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminMfaResetCommitService(
            AdminRepository repository,
            PasswordHashingService passwordHashingService,
            TotpSecretEncryptionService encryptionService,
            TotpService totpService,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.repository = repository;
        this.passwordHashingService = passwordHashingService;
        this.encryptionService = encryptionService;
        this.totpService = totpService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public boolean attempt(
            UUID targetUserId,
            MfaResetRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        repository.lockLastAdminGuard();
        MfaReauthentication actor =
                repository.findMfaReauthenticationForUpdate(actorUserId)
                        .orElse(null);
        if (actor == null
                || !verifyPassword(
                        request.currentPassword(),
                        actor.passwordHash()
                )) {
            return false;
        }

        OptionalLong successfulStep = verifyTotp(
                actorUserId,
                actor,
                request.totpCode(),
                context
        );
        if (successfulStep.isEmpty()
                || repository.advanceTotpStep(
                        actorUserId,
                        actor.factorId(),
                        successfulStep.orElseThrow()
                ) != 1) {
            return false;
        }

        repository.findUserForUpdate(targetUserId).orElseThrow(
                () -> new BusinessException(
                        ErrorCode.AUTH_USER_NOT_FOUND
                )
        );
        int revokedFactors = repository.revokeMfaFactors(targetUserId);
        if (revokedFactors == 0) {
            throw new BusinessException(
                    ErrorCode.COMMON_CONFLICT,
                    "초기화할 MFA factor가 없습니다."
            );
        }
        int revokedSessions =
                repository.revokeAllSessions(targetUserId);

        auditLogService.appendRequired(new AuditLogRecord(
                context,
                actorUserId,
                AuditActorType.USER,
                "AUTH_MFA_RESET_BY_ADMIN",
                AuditTargetType.USER,
                targetUserId,
                AuditSnapshot.allow(
                        Set.of("mfaEnrolled"),
                        Map.of("mfaEnrolled", true)
                ),
                AuditSnapshot.allow(
                        Set.of(
                                "mfaEnrolled",
                                "revokedFactorCount",
                                "revokedSessionCount"
                        ),
                        Map.of(
                                "mfaEnrolled", false,
                                "revokedFactorCount", revokedFactors,
                                "revokedSessionCount", revokedSessions
                        )
                ),
                request.reason().trim(),
                AuditResult.SUCCESS
        ));
        return true;
    }

    private boolean verifyPassword(
            String rawPassword,
            String passwordHash
    ) {
        try {
            return passwordHashingService.verifyAgainstNullableHash(
                    rawPassword,
                    passwordHash
            );
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private OptionalLong verifyTotp(
            UUID actorUserId,
            MfaReauthentication actor,
            String submittedCode,
            AuditRequestContext context
    ) {
        byte[] secret = null;
        try {
            secret = encryptionService.decrypt(
                    actorUserId,
                    actor.factorId(),
                    actor.encryptedSecret()
            );
            return totpService.verify(
                    secret,
                    submittedCode,
                    clock.instant(),
                    actor.lastSuccessfulTotpStep()
            );
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Admin MFA reauthentication failed safely. requestId={}",
                    context.requestId()
            );
            return OptionalLong.empty();
        } finally {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
        }
    }
}
