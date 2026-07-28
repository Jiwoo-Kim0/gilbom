package com.gilbom.auth.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.account.AccountStatus;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class PublicRegistrationServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final AuditRequestContext AUDIT_CONTEXT =
            AuditRequestContext.requestIdOnly(
                    UUID.fromString(
                            "11000000-0000-4000-8000-000000000002"
                    )
            );

    private final PublicRegistrationRepository repository =
            Mockito.mock(PublicRegistrationRepository.class);
    private final PublicRegistrationIdGenerator idGenerator =
            Mockito.mock(PublicRegistrationIdGenerator.class);
    private final PasswordHashingService passwordHashingService =
            Mockito.mock(PasswordHashingService.class);
    private final AuditLogService auditLogService =
            Mockito.mock(AuditLogService.class);
    private final PublicRegistrationService service =
            new PublicRegistrationService(
                    repository,
                    idGenerator,
                    passwordHashingService,
                    auditLogService
            );

    @Test
    void availabilityUsesTheNormalizedEmail() {
        when(repository.existsByNormalizedEmail("user@example.com"))
                .thenReturn(true);

        EmailAvailabilityResponse response =
                service.checkEmailAvailability("User@Example.COM");

        assertThat(response.available()).isFalse();
        verify(repository).existsByNormalizedEmail("user@example.com");
    }

    @Test
    void createsPendingAccountWithoutRoleOrAuthentication() {
        PublicRegistrationRequest request = new PublicRegistrationRequest(
                "User@Example.COM",
                "exact password",
                "길봄 사용자"
        );
        when(repository.existsByNormalizedEmail("user@example.com"))
                .thenReturn(false);
        when(passwordHashingService.hashNewPassword("exact password"))
                .thenReturn("{argon2}$argon2id$encoded");
        when(idGenerator.nextUserCode()).thenReturn("USR-random");
        when(repository.insertPendingAccount(
                "USR-random",
                "{argon2}$argon2id$encoded",
                "길봄 사용자",
                "User@Example.COM"
        )).thenReturn(Optional.of(USER_ID));

        PublicRegistrationResponse response =
                service.register(request, AUDIT_CONTEXT);

        assertThat(response.accountStatus())
                .isEqualTo(AccountStatus.PENDING_APPROVAL);
        assertThat(response.nextAction()).isEqualTo("LOGIN");
        verify(passwordHashingService).hashNewPassword("exact password");
        verify(auditLogService).appendRequired(Mockito.argThat(record ->
                record.targetId().equals(USER_ID)
                        && record.requestContext().equals(AUDIT_CONTEXT)
                        && record.afterValue().values()
                        .get("accountStatus")
                        .equals("PENDING_APPROVAL")
        ));
    }

    @Test
    void rejectsKnownEmailBeforeHashing() {
        PublicRegistrationRequest request = validRequest();
        when(repository.existsByNormalizedEmail("user@example.com"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.register(request, AUDIT_CONTEXT))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).errorCode()
                ).isEqualTo(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED));

        verifyNoInteractions(passwordHashingService, idGenerator);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void mapsConcurrentEmailUniqueConflictToTheSame409() {
        PublicRegistrationRequest request = validRequest();
        when(repository.existsByNormalizedEmail("user@example.com"))
                .thenReturn(false, true);
        when(passwordHashingService.hashNewPassword("exact password"))
                .thenReturn("{argon2}$argon2id$encoded");
        when(idGenerator.nextUserCode()).thenReturn("USR-random");
        when(repository.insertPendingAccount(
                "USR-random",
                "{argon2}$argon2id$encoded",
                "길봄 사용자",
                "User@Example.COM"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(request, AUDIT_CONTEXT))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).errorCode()
                ).isEqualTo(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED));

        verifyNoInteractions(auditLogService);
    }

    private static PublicRegistrationRequest validRequest() {
        return new PublicRegistrationRequest(
                "User@Example.COM",
                "exact password",
                "길봄 사용자"
        );
    }
}
