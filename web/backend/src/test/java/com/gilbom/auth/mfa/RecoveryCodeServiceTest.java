package com.gilbom.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.gilbom.auth.mfa.RecoveryCodeService.GeneratedRecoveryCode;
import com.gilbom.auth.password.PasswordHashingService;

class RecoveryCodeServiceTest {

    private final PasswordHashingService passwordHashingService =
            Mockito.mock(PasswordHashingService.class);
    private final RecoveryCodeService service;

    RecoveryCodeServiceTest() {
        when(passwordHashingService.hashNewPassword(anyString()))
                .thenAnswer(invocation ->
                        "{argon2}$argon2id$"
                                + invocation.getArgument(0, String.class));
        service = new RecoveryCodeService(
                passwordHashingService,
                new SecureRandom()
        );
    }

    @Test
    void issuesExactlyTenUniquePositionedCodesAndStoresOnlyHashes() {
        List<GeneratedRecoveryCode> codes = service.generateCodes();

        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes.stream()
                .map(GeneratedRecoveryCode::rawCode)
                .toList())).hasSize(10);
        for (int index = 0; index < codes.size(); index++) {
            GeneratedRecoveryCode code = codes.get(index);
            assertThat(code.position()).isEqualTo(index + 1);
            assertThat(code.rawCode())
                    .matches(RecoveryCodeService.CODE_PATTERN);
            assertThat(code.encodedHash())
                    .startsWith("{argon2}$argon2id$");
            assertThat(service.parsePosition(code.rawCode()))
                    .hasValue(index + 1);
        }
    }

    @Test
    void malformedCodesDoNotExposeAPosition() {
        assertThat(service.parsePosition(
                "01-AAAA-AAAA-AAAA-AAA1"
        )).isEmpty();
        assertThat(service.parsePosition(
                "11-AAAA-AAAA-AAAA-AAAA"
        )).isEmpty();
        assertThat(service.parsePosition(null)).isEmpty();
    }
}
