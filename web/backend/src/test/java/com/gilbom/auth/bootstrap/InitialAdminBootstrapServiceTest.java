package com.gilbom.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gilbom.audit.AuditLogService;
import com.gilbom.auth.bootstrap.InitialAdminBootstrapRepository.ExistingAccount;
import com.gilbom.auth.password.PasswordHashingService;

class InitialAdminBootstrapServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");

    private final InitialAdminBootstrapRepository repository =
            mock(InitialAdminBootstrapRepository.class);
    private final PasswordHashingService passwordHashingService =
            mock(PasswordHashingService.class);
    private final AuditLogService auditLogService =
            mock(AuditLogService.class);
    private final InitialAdminBootstrapService service =
            new InitialAdminBootstrapService(
                    repository,
                    passwordHashingService,
                    auditLogService
            );

    @Test
    void createsOneActiveAdminWithForcedPasswordChangeAndAudit() {
        InitialAdminBootstrapProperties properties = validProperties();
        when(repository.findByNormalizedEmail("initial-admin@example.com"))
                .thenReturn(Optional.empty());
        when(repository.findBootstrapAccount()).thenReturn(Optional.empty());
        when(passwordHashingService.hashNewPassword("initial password"))
                .thenReturn("{argon2}$argon2id$encoded");
        when(repository.insertIfEmailAbsent(
                "ADM-BOOTSTRAP",
                "{argon2}$argon2id$encoded",
                "Initial Administrator",
                "Initial-Admin@Example.com"
        )).thenReturn(Optional.of(USER_ID));

        InitialAdminBootstrapResult result = service.bootstrap(properties);

        assertThat(result).isEqualTo(InitialAdminBootstrapResult.CREATED);
        verify(repository).assignAdminRole(USER_ID);
        verify(auditLogService).appendRequired(
                org.mockito.ArgumentMatchers.argThat(record ->
                        record.targetId().equals(USER_ID)
                                && record.afterValue().values()
                                .get("accountOrigin")
                                .equals("BOOTSTRAP")
                )
        );
    }

    @Test
    void restartLeavesAnExistingBootstrapAccountUnchanged() {
        InitialAdminBootstrapProperties properties = validProperties();
        ExistingAccount existing = new ExistingAccount(USER_ID, "BOOTSTRAP");
        when(repository.findByNormalizedEmail("initial-admin@example.com"))
                .thenReturn(Optional.of(existing));
        when(repository.isBootstrapOrigin(existing)).thenReturn(true);

        InitialAdminBootstrapResult result = service.bootstrap(properties);

        assertThat(result).isEqualTo(InitialAdminBootstrapResult.ALREADY_PRESENT);
        verifyNoInteractions(passwordHashingService);
        verify(repository, never()).assignAdminRole(USER_ID);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void neverElevatesAPublicAccountThatReservedTheBootstrapEmail() {
        InitialAdminBootstrapProperties properties = validProperties();
        ExistingAccount existing =
                new ExistingAccount(USER_ID, "PUBLIC_REGISTRATION");
        when(repository.findByNormalizedEmail("initial-admin@example.com"))
                .thenReturn(Optional.of(existing));
        when(repository.isBootstrapOrigin(existing)).thenReturn(false);

        assertThatThrownBy(() -> service.bootstrap(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Initial administrator email belongs to another account origin")
                .hasMessageNotContaining(properties.getEmail())
                .hasMessageNotContaining(properties.getPassword());

        verifyNoInteractions(passwordHashingService);
        verify(repository, never()).assignAdminRole(USER_ID);
    }

    @Test
    void rejectsASecondBootstrapIdentity() {
        InitialAdminBootstrapProperties properties = validProperties();
        when(repository.findByNormalizedEmail("initial-admin@example.com"))
                .thenReturn(Optional.empty());
        when(repository.findBootstrapAccount())
                .thenReturn(Optional.of(new ExistingAccount(USER_ID, "BOOTSTRAP")));

        assertThatThrownBy(() -> service.bootstrap(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("An initial administrator already exists under another email");

        verifyNoInteractions(passwordHashingService);
    }

    @Test
    void rejectsBootstrapWhenTheSystemAlreadyHasAnActiveAdministrator() {
        InitialAdminBootstrapProperties properties = validProperties();
        when(repository.findByNormalizedEmail("initial-admin@example.com"))
                .thenReturn(Optional.empty());
        when(repository.findBootstrapAccount()).thenReturn(Optional.empty());
        when(repository.hasActiveAdministrator()).thenReturn(true);

        assertThatThrownBy(() -> service.bootstrap(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("An active administrator already exists");

        verifyNoInteractions(passwordHashingService);
    }

    @Test
    void validatesTheSecretBeforeAccessingTheDatabase() {
        InitialAdminBootstrapProperties properties = validProperties();
        properties.setPassword("short");

        assertThatThrownBy(() -> service.bootstrap(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Initial administrator password length is invalid")
                .hasMessageNotContaining("short");

        verifyNoInteractions(repository, passwordHashingService);
    }

    private static InitialAdminBootstrapProperties validProperties() {
        InitialAdminBootstrapProperties properties =
                new InitialAdminBootstrapProperties();
        properties.setEnabled(true);
        properties.setEmail("Initial-Admin@Example.com");
        properties.setPassword("initial password");
        properties.setDisplayName("Initial Administrator");
        properties.setUserCode("ADM-BOOTSTRAP");
        return properties;
    }
}
