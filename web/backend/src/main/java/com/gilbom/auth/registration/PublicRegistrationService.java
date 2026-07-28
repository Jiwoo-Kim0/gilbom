package com.gilbom.auth.registration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.audit.AuditAction;
import com.gilbom.audit.AuditActorType;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditSnapshot;
import com.gilbom.audit.AuditTargetType;
import com.gilbom.auth.account.EmailAddressPolicy;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Service
public class PublicRegistrationService {

    private static final int USER_CODE_INSERT_ATTEMPTS = 3;
    private static final java.util.Set<String> AUDIT_AFTER_FIELDS =
            java.util.Set.of(
                    "accountStatus",
                    "accountOrigin",
                    "roleAssigned"
            );

    private final PublicRegistrationRepository repository;
    private final PublicRegistrationIdGenerator idGenerator;
    private final PasswordHashingService passwordHashingService;
    private final AuditLogService auditLogService;

    public PublicRegistrationService(
            PublicRegistrationRepository repository,
            PublicRegistrationIdGenerator idGenerator,
            PasswordHashingService passwordHashingService,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.passwordHashingService = passwordHashingService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public EmailAvailabilityResponse checkEmailAvailability(String email) {
        String normalizedEmail = EmailAddressPolicy.normalize(email);
        return new EmailAvailabilityResponse(
                !repository.existsByNormalizedEmail(normalizedEmail)
        );
    }

    @Transactional
    public PublicRegistrationResponse register(
            PublicRegistrationRequest request,
            AuditRequestContext auditContext
    ) {
        String normalizedEmail = EmailAddressPolicy.normalize(request.email());
        if (repository.existsByNormalizedEmail(normalizedEmail)) {
            throw duplicateEmail();
        }

        String passwordHash =
                passwordHashingService.hashNewPassword(request.password());

        for (int attempt = 0; attempt < USER_CODE_INSERT_ATTEMPTS; attempt++) {
            Optional<UUID> insertedUserId = repository.insertPendingAccount(
                    idGenerator.nextUserCode(),
                    passwordHash,
                    request.displayName(),
                    request.email()
            );
            if (insertedUserId.isPresent()) {
                UUID userId = insertedUserId.orElseThrow();
                auditLogService.appendRequired(new AuditLogRecord(
                        auditContext,
                        null,
                        AuditActorType.USER,
                        AuditAction.PUBLIC_REGISTRATION_CREATED.databaseValue(),
                        AuditTargetType.USER,
                        userId,
                        null,
                        AuditSnapshot.allow(
                                AUDIT_AFTER_FIELDS,
                                java.util.Map.of(
                                        "accountStatus", "PENDING_APPROVAL",
                                        "accountOrigin", "PUBLIC_REGISTRATION",
                                        "roleAssigned", false
                                )
                        ),
                        null,
                        AuditResult.SUCCESS
                ));
                return PublicRegistrationResponse.pendingApproval();
            }

            // UNIQUE 충돌 후 이메일 존재 여부를 다시 확인해 동시 가입 경쟁을 안전하게 판정한다.
            if (repository.existsByNormalizedEmail(normalizedEmail)) {
                throw duplicateEmail();
            }
        }

        throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
    }

    private static BusinessException duplicateEmail() {
        return new BusinessException(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED);
    }
}
