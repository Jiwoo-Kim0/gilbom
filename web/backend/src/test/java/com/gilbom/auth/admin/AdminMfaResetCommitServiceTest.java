package com.gilbom.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.admin.AdminDtos.MfaResetRequest;
import com.gilbom.auth.admin.AdminRepository.MfaReauthentication;
import com.gilbom.auth.admin.AdminRepository.UserState;
import com.gilbom.auth.mfa.TotpService;
import com.gilbom.auth.mfa.crypto.EncryptedTotpSecret;
import com.gilbom.auth.mfa.crypto.TotpSecretEncryptionService;
import com.gilbom.auth.password.PasswordHashingService;

@ExtendWith(MockitoExtension.class)
class AdminMfaResetCommitServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString(
            "43000000-0000-4000-8000-000000000001"
    );
    private static final UUID TARGET_ID = UUID.fromString(
            "43000000-0000-4000-8000-000000000002"
    );
    private static final UUID FACTOR_ID = UUID.fromString(
            "43000000-0000-4000-8000-000000000003"
    );
    private static final AuditRequestContext CONTEXT =
            AuditRequestContext.requestIdOnly(UUID.fromString(
                    "43000000-0000-4000-8000-000000000004"
            ));

    @Mock
    private AdminRepository repository;
    @Mock
    private PasswordHashingService passwordHashingService;
    @Mock
    private TotpSecretEncryptionService encryptionService;
    @Mock
    private TotpService totpService;
    @Mock
    private AuditLogService auditLogService;

    private AdminMfaResetCommitService service;

    @BeforeEach
    void setUp() {
        service = new AdminMfaResetCommitService(
                repository,
                passwordHashingService,
                encryptionService,
                totpService,
                auditLogService,
                Clock.fixed(
                        Instant.parse("2026-07-28T00:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    void reauthenticatesRevokesFactorAndSessionsThenAudits() {
        EncryptedTotpSecret encrypted = new EncryptedTotpSecret(
                new byte[17],
                new byte[12],
                1
        );
        MfaReauthentication actor = new MfaReauthentication(
                "stored-hash",
                FACTOR_ID,
                encrypted,
                100L
        );
        byte[] plaintextSecret = new byte[20];
        java.util.Arrays.fill(plaintextSecret, (byte) 7);

        when(repository.findMfaReauthenticationForUpdate(ACTOR_ID))
                .thenReturn(Optional.of(actor));
        when(passwordHashingService.verifyAgainstNullableHash(
                "current-password",
                "stored-hash"
        )).thenReturn(true);
        when(encryptionService.decrypt(
                ACTOR_ID,
                FACTOR_ID,
                encrypted
        )).thenReturn(plaintextSecret);
        when(totpService.verify(
                eq(plaintextSecret),
                eq("123456"),
                any(Instant.class),
                eq(100L)
        )).thenReturn(OptionalLong.of(101L));
        when(repository.advanceTotpStep(
                ACTOR_ID,
                FACTOR_ID,
                101L
        )).thenReturn(1);
        when(repository.findUserForUpdate(TARGET_ID))
                .thenReturn(Optional.of(new UserState(
                        TARGET_ID,
                        "ACTIVE",
                        true
                )));
        when(repository.revokeMfaFactors(TARGET_ID)).thenReturn(1);
        when(repository.revokeAllSessions(TARGET_ID)).thenReturn(2);

        boolean completed = service.attempt(
                TARGET_ID,
                new MfaResetRequest(
                        "current-password",
                        "123456",
                        "분실 신고 확인"
                ),
                ACTOR_ID,
                CONTEXT
        );

        assertThat(completed).isTrue();
        assertThat(plaintextSecret).containsOnly((byte) 0);
        InOrder order = inOrder(repository, auditLogService);
        order.verify(repository).lockLastAdminGuard();
        order.verify(repository)
                .findMfaReauthenticationForUpdate(ACTOR_ID);
        order.verify(repository).advanceTotpStep(
                ACTOR_ID,
                FACTOR_ID,
                101L
        );
        order.verify(repository).findUserForUpdate(TARGET_ID);
        order.verify(repository).revokeMfaFactors(TARGET_ID);
        order.verify(repository).revokeAllSessions(TARGET_ID);
        order.verify(auditLogService).appendRequired(any());

        ArgumentCaptor<AuditLogRecord> audit =
                ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).appendRequired(audit.capture());
        assertThat(audit.getValue().actionType())
                .isEqualTo("AUTH_MFA_RESET_BY_ADMIN");
        assertThat(audit.getValue().actorUserId()).isEqualTo(ACTOR_ID);
        assertThat(audit.getValue().targetId()).isEqualTo(TARGET_ID);
    }
}
