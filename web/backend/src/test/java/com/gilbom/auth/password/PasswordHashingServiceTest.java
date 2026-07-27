package com.gilbom.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordHashingServiceTest {

    @Test
    void springContextUsesThePasswordEncoderConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(
                    PasswordHashingConfiguration.class,
                    PasswordHashingService.class
            );
            context.refresh();

            assertThat(context.getBean(PasswordHashingService.class)).isNotNull();
        }
    }

    @Test
    void hashesEveryNewPasswordWithTheSingleArgon2idConfiguration() {
        PasswordHashingService service = realService();

        String encoded = service.hashNewPassword("Original Password");

        assertThat(encoded)
                .startsWith("{argon2}$argon2id$")
                .contains("m=19456,t=2,p=1");
        assertThat(service.verifyAgainstNullableHash("Original Password", encoded)).isTrue();
        assertThat(service.verifyAgainstNullableHash("original password", encoded)).isFalse();
    }

    @Test
    void realAndDummyHashesUseTheSameArgon2idParameters() {
        PasswordHashingService service = realService();

        String realHash = service.hashNewPassword("same parameter check");
        String dummyHash = service.dummyPasswordHashForTesting();

        assertThat(realHash)
                .startsWith("{argon2}$argon2id$")
                .contains("m=19456,t=2,p=1");
        assertThat(dummyHash)
                .startsWith("{argon2}$argon2id$")
                .contains("m=19456,t=2,p=1");
        assertThat(service.dummyPasswordHashForTesting())
                .isSameAs(dummyHash);
        assertThat(service.verifyAgainstNullableHash(
                "missing account password",
                null
        )).isFalse();
    }

    @Test
    void hashesAndVerifiesEveryCodePointOfAUnicodeMaximumLengthPassword() {
        PasswordHashingService service = realService();
        String first = "한😀".repeat(12);
        String differentLastCodePoint =
                first.substring(0, first.offsetByCodePoints(0, 23)) + "끝";

        assertThat(first.codePointCount(0, first.length())).isEqualTo(24);
        assertThat(differentLastCodePoint.codePointCount(
                0,
                differentLastCodePoint.length()
        )).isEqualTo(24);

        String encoded = service.hashNewPassword(first);

        assertThat(service.verifyAgainstNullableHash(first, encoded)).isTrue();
        assertThat(service.verifyAgainstNullableHash(
                differentLastCodePoint,
                encoded
        )).isFalse();
    }

    @Test
    void enforcesLengthWithoutTrimmingOrCompositionRules() {
        PasswordHashingService service = realService();

        assertThatThrownBy(() -> service.hashNewPassword("a".repeat(9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("password length is invalid");
        assertThatThrownBy(() -> service.hashNewPassword("a".repeat(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("password length is invalid");

        String spaces = service.hashNewPassword("          ");
        assertThat(service.verifyAgainstNullableHash("          ", spaces)).isTrue();
        assertThat(service.verifyAgainstNullableHash("         ", spaces)).isFalse();
    }

    @Test
    void reusesOneDummyHashForEveryMissingAccountVerification() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        String dummyHash = "{argon2}$argon2id$v=19$m=19456,t=2,p=1$dummy";
        when(encoder.encode(any(CharSequence.class))).thenReturn(dummyHash);
        when(encoder.matches(any(CharSequence.class), eq(dummyHash))).thenReturn(false);

        PasswordHashingService service =
                new PasswordHashingService(encoder, new SecureRandom());

        assertThat(service.verifyAgainstNullableHash("first-attempt", null)).isFalse();
        assertThat(service.verifyAgainstNullableHash("second-attempt", null)).isFalse();
        assertThat(service.dummyPasswordHashForTesting()).isEqualTo(dummyHash);

        verify(encoder, times(1)).encode(any(CharSequence.class));
        verify(encoder, times(2)).matches(any(CharSequence.class), eq(dummyHash));
    }

    @Test
    void doesNotFallbackToBcrypt() {
        PasswordHashingService service = realService();

        assertThatThrownBy(() -> service.verifyAgainstNullableHash(
                "safe-test-password",
                "{bcrypt}$2a$10$not-an-accepted-hash"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static PasswordHashingService realService() {
        PasswordEncoder encoder =
                new PasswordHashingConfiguration().passwordEncoder();
        return new PasswordHashingService(encoder, new SecureRandom());
    }
}
