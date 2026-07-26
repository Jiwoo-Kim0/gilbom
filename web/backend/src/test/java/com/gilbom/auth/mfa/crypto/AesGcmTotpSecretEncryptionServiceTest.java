package com.gilbom.auth.mfa.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AesGcmTotpSecretEncryptionServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID FACTOR_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final byte[] RAW_TOTP_SECRET =
            "twenty-byte-secret!!".getBytes(StandardCharsets.US_ASCII);

    @Test
    void encryptsAndDecryptsWithAes256Gcm() {
        AesGcmTotpSecretEncryptionService service = service(1, keyring(1, (byte) 0x11));

        EncryptedTotpSecret encrypted = service.encrypt(USER_ID, FACTOR_ID, RAW_TOTP_SECRET);
        byte[] decrypted = service.decrypt(USER_ID, FACTOR_ID, encrypted);

        assertThat(encrypted.keyVersion()).isEqualTo(1);
        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.ciphertext()).hasSize(RAW_TOTP_SECRET.length + 16);
        assertThat(encrypted.ciphertext()).isNotEqualTo(RAW_TOTP_SECRET);
        assertThat(decrypted).containsExactly(RAW_TOTP_SECRET);
    }

    @Test
    void createsANewNonceAndCiphertextForEveryEncryption() {
        AesGcmTotpSecretEncryptionService service = service(1, keyring(1, (byte) 0x22));

        EncryptedTotpSecret first = service.encrypt(USER_ID, FACTOR_ID, RAW_TOTP_SECRET);
        EncryptedTotpSecret second = service.encrypt(USER_ID, FACTOR_ID, RAW_TOTP_SECRET);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void rejectsCiphertextMovedToAnotherUserOrFactor() {
        AesGcmTotpSecretEncryptionService service = service(1, keyring(1, (byte) 0x33));
        EncryptedTotpSecret encrypted = service.encrypt(USER_ID, FACTOR_ID, RAW_TOTP_SECRET);

        assertThatThrownBy(() -> service.decrypt(UUID.randomUUID(), FACTOR_ID, encrypted))
                .isInstanceOf(TotpSecretCryptoException.class)
                .hasMessage("TOTP secret cryptographic operation failed");
        assertThatThrownBy(() -> service.decrypt(USER_ID, UUID.randomUUID(), encrypted))
                .isInstanceOf(TotpSecretCryptoException.class)
                .hasMessage("TOTP secret cryptographic operation failed");
    }

    @Test
    void rejectsTamperedCiphertextWithoutLeakingCryptographicDetails() {
        AesGcmTotpSecretEncryptionService service = service(1, keyring(1, (byte) 0x44));
        EncryptedTotpSecret encrypted = service.encrypt(USER_ID, FACTOR_ID, RAW_TOTP_SECRET);
        byte[] tampered = encrypted.ciphertext();
        tampered[0] ^= 0x01;

        EncryptedTotpSecret tamperedValue =
                new EncryptedTotpSecret(tampered, encrypted.nonce(), encrypted.keyVersion());

        assertThatThrownBy(() -> service.decrypt(USER_ID, FACTOR_ID, tamperedValue))
                .isInstanceOf(TotpSecretCryptoException.class)
                .hasMessage("TOTP secret cryptographic operation failed")
                .hasMessageNotContaining("tag")
                .hasMessageNotContaining("ciphertext")
                .hasMessageNotContaining("secret!!");
    }

    @Test
    void decryptsOldKeyVersionWhileEncryptingWithNewActiveVersion() {
        String versionOne = keyring(1, (byte) 0x55);
        EncryptedTotpSecret oldEncrypted =
                service(1, versionOne).encrypt(USER_ID, FACTOR_ID, RAW_TOTP_SECRET);

        String rotatedKeyring = versionOne + ";" + keyring(2, (byte) 0x66);
        AesGcmTotpSecretEncryptionService rotatedService = service(2, rotatedKeyring);
        EncryptedTotpSecret newEncrypted =
                rotatedService.encrypt(USER_ID, FACTOR_ID, RAW_TOTP_SECRET);

        assertThat(rotatedService.decrypt(USER_ID, FACTOR_ID, oldEncrypted))
                .containsExactly(RAW_TOTP_SECRET);
        assertThat(newEncrypted.keyVersion()).isEqualTo(2);
    }

    @Test
    void failsFastWhenEnabledKeyringIsMissingOrInvalid() {
        TotpSecretEncryptionProperties missing = properties(true, 1, "");
        TotpSecretEncryptionProperties shortKey =
                properties(true, 1, "1=" + Base64.getEncoder().encodeToString(new byte[16]));
        TotpSecretEncryptionProperties missingActive =
                properties(true, 2, keyring(1, (byte) 0x77));

        assertThatThrownBy(() -> new AesGcmTotpSecretEncryptionService(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("null");
        assertThatThrownBy(() -> new AesGcmTotpSecretEncryptionService(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new AesGcmTotpSecretEncryptionService(missingActive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active key version");
    }

    @Test
    void disabledLocalConfigurationFailsClosedWhenEncryptionIsRequested() {
        AesGcmTotpSecretEncryptionService service =
                new AesGcmTotpSecretEncryptionService(properties(false, 1, ""));

        assertThatThrownBy(() -> service.encrypt(USER_ID, FACTOR_ID, RAW_TOTP_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void requiredOperationalConfigurationFailsAtStartupWhenDisabled() {
        TotpSecretEncryptionProperties properties = properties(false, 1, "");
        properties.setRequired(true);

        assertThatThrownBy(() -> new AesGcmTotpSecretEncryptionService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required by the active profile");
    }

    private static AesGcmTotpSecretEncryptionService service(
            int activeVersion,
            String keyring
    ) {
        return new AesGcmTotpSecretEncryptionService(
                properties(true, activeVersion, keyring)
        );
    }

    private static TotpSecretEncryptionProperties properties(
            boolean enabled,
            int activeVersion,
            String keyring
    ) {
        TotpSecretEncryptionProperties properties = new TotpSecretEncryptionProperties();
        properties.setEnabled(enabled);
        properties.setActiveKeyVersion(activeVersion);
        properties.setKeyring(keyring);
        return properties;
    }

    private static String keyring(int version, byte fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        return version + "=" + Base64.getEncoder().encodeToString(key);
    }
}
