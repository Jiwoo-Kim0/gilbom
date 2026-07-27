package com.gilbom.auth.registration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.account.EmailAddressPolicy;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Service
public class PublicRegistrationService {

    private static final int USER_CODE_INSERT_ATTEMPTS = 3;

    private final PublicRegistrationRepository repository;
    private final PublicRegistrationIdGenerator idGenerator;
    private final PasswordHashingService passwordHashingService;

    public PublicRegistrationService(
            PublicRegistrationRepository repository,
            PublicRegistrationIdGenerator idGenerator,
            PasswordHashingService passwordHashingService
    ) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.passwordHashingService = passwordHashingService;
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
            PublicRegistrationRequest request
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
                repository.appendRegistrationAudit(insertedUserId.orElseThrow());
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
