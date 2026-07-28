package com.gilbom.auth.bootstrap;

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
import com.gilbom.auth.bootstrap.InitialAdminBootstrapRepository.ExistingAccount;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.auth.password.PasswordPolicy;

@Service
public class InitialAdminBootstrapService {

    private static final int MAX_DISPLAY_NAME_LENGTH = 100;
    private static final int MAX_USER_CODE_LENGTH = 30;
    private static final java.util.Set<String> AUDIT_AFTER_FIELDS =
            java.util.Set.of(
                    "accountStatus",
                    "accountOrigin",
                    "passwordChangeRequired"
            );

    private final InitialAdminBootstrapRepository repository;
    private final PasswordHashingService passwordHashingService;
    private final AuditLogService auditLogService;

    public InitialAdminBootstrapService(
            InitialAdminBootstrapRepository repository,
            PasswordHashingService passwordHashingService,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.passwordHashingService = passwordHashingService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public InitialAdminBootstrapResult bootstrap(
            InitialAdminBootstrapProperties properties
    ) {
        validateConfiguration(properties);
        String normalizedEmail = EmailAddressPolicy.normalize(properties.getEmail());

        Optional<ExistingAccount> sameEmail =
                repository.findByNormalizedEmail(normalizedEmail);
        if (sameEmail.isPresent()) {
            requireBootstrapOrigin(sameEmail.orElseThrow());
            return InitialAdminBootstrapResult.ALREADY_PRESENT;
        }

        if (repository.findBootstrapAccount().isPresent()) {
            throw new IllegalStateException(
                    "An initial administrator already exists under another email");
        }
        if (repository.hasActiveAdministrator()) {
            throw new IllegalStateException(
                    "An active administrator already exists");
        }

        String passwordHash =
                passwordHashingService.hashNewPassword(properties.getPassword());
        Optional<UUID> insertedUserId = repository.insertIfEmailAbsent(
                properties.getUserCode(),
                passwordHash,
                properties.getDisplayName(),
                properties.getEmail()
        );

        if (insertedUserId.isEmpty()) {
            ExistingAccount concurrentlyInserted =
                    repository.findByNormalizedEmail(normalizedEmail)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Initial administrator bootstrap conflict"
                            ));
            requireBootstrapOrigin(concurrentlyInserted);
            return InitialAdminBootstrapResult.ALREADY_PRESENT;
        }

        UUID userId = insertedUserId.orElseThrow();
        repository.assignAdminRole(userId);
        auditLogService.appendRequired(new AuditLogRecord(
                AuditRequestContext.system(),
                null,
                AuditActorType.SYSTEM,
                AuditAction.INITIAL_ADMIN_CREATED.databaseValue(),
                AuditTargetType.USER,
                userId,
                null,
                AuditSnapshot.allow(
                        AUDIT_AFTER_FIELDS,
                        java.util.Map.of(
                                "accountStatus", "ACTIVE",
                                "accountOrigin", "BOOTSTRAP",
                                "passwordChangeRequired", true
                        )
                ),
                null,
                AuditResult.SUCCESS
        ));
        return InitialAdminBootstrapResult.CREATED;
    }

    private static void validateConfiguration(
            InitialAdminBootstrapProperties properties
    ) {
        if (properties == null || !properties.isEnabled()) {
            throw new IllegalStateException(
                    "Initial administrator bootstrap is not enabled");
        }
        if (!EmailAddressPolicy.hasValidFormat(properties.getEmail())) {
            throw new IllegalStateException(
                    "Initial administrator email format is invalid");
        }
        if (!PasswordPolicy.hasValidLength(properties.getPassword())) {
            throw new IllegalStateException(
                    "Initial administrator password length is invalid");
        }
        requireBoundedValue(
                properties.getDisplayName(),
                MAX_DISPLAY_NAME_LENGTH,
                "display name"
        );
        requireBoundedValue(
                properties.getUserCode(),
                MAX_USER_CODE_LENGTH,
                "user code"
        );
    }

    private static void requireBoundedValue(
            String value,
            int maximumLength,
            String fieldName
    ) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > maximumLength) {
            throw new IllegalStateException(
                    "Initial administrator " + fieldName + " is invalid");
        }
    }

    private void requireBootstrapOrigin(ExistingAccount account) {
        if (!repository.isBootstrapOrigin(account)) {
            // 공개 가입자가 bootstrap 이메일을 선점해도 관리자 승격을 하지 않고 기동을 중단한다.
            throw new IllegalStateException(
                    "Initial administrator email belongs to another account origin");
        }
    }
}
