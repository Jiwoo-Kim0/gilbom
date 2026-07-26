package com.gilbom.auth.mfa.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AesGcmTotpSecretEncryptionService implements TotpSecretEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] AAD_DOMAIN =
            "gilbom:totp-secret:v1".getBytes(StandardCharsets.US_ASCII);

    private final boolean enabled;
    private final int activeKeyVersion;
    private final Map<Integer, SecretKey> keys;
    private final SecureRandom secureRandom;

    @Autowired
    public AesGcmTotpSecretEncryptionService(TotpSecretEncryptionProperties properties) {
        this(properties, new SecureRandom());
    }

    AesGcmTotpSecretEncryptionService(
            TotpSecretEncryptionProperties properties,
            SecureRandom secureRandom
    ) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
        if (properties.isRequired() && !properties.isEnabled()) {
            throw configurationError("TOTP secret encryption is required by the active profile");
        }
        this.enabled = properties.isEnabled();
        this.activeKeyVersion = properties.getActiveKeyVersion();
        this.keys = loadKeys(properties);
    }

    @Override
    public EncryptedTotpSecret encrypt(UUID userId, UUID factorId, byte[] plaintextSecret) {
        requireEnabled();
        requireContext(userId, factorId);
        if (plaintextSecret == null || plaintextSecret.length == 0) {
            throw new IllegalArgumentException("plaintextSecret must not be empty");
        }

        byte[] nonce = new byte[GCM_NONCE_BYTES];
        secureRandom.nextBytes(nonce);

        try {
            Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, activeKeyVersion, nonce);
            cipher.updateAAD(buildAdditionalAuthenticatedData(userId, factorId, activeKeyVersion));
            byte[] ciphertext = cipher.doFinal(plaintextSecret);
            return new EncryptedTotpSecret(ciphertext, nonce, activeKeyVersion);
        } catch (GeneralSecurityException exception) {
            // provider 예외나 입력 데이터를 메시지·로그로 전파하지 않는다.
            throw new TotpSecretCryptoException();
        }
    }

    @Override
    public byte[] decrypt(
            UUID userId,
            UUID factorId,
            EncryptedTotpSecret encryptedSecret
    ) {
        requireEnabled();
        requireContext(userId, factorId);
        Objects.requireNonNull(encryptedSecret, "encryptedSecret must not be null");

        int keyVersion = encryptedSecret.keyVersion();
        try {
            Cipher cipher = newCipher(Cipher.DECRYPT_MODE, keyVersion, encryptedSecret.nonce());
            cipher.updateAAD(buildAdditionalAuthenticatedData(userId, factorId, keyVersion));
            return cipher.doFinal(encryptedSecret.ciphertext());
        } catch (GeneralSecurityException exception) {
            // tag 불일치 여부를 구분하지 않아 암호화 상태를 추론할 정보를 주지 않는다.
            throw new TotpSecretCryptoException();
        }
    }

    private Cipher newCipher(int mode, int keyVersion, byte[] nonce)
            throws GeneralSecurityException {
        SecretKey key = keys.get(keyVersion);
        if (key == null) {
            // rotation 중 과거 key를 너무 일찍 제거한 설정 오류도 안전한 단일 오류로 처리한다.
            throw new TotpSecretCryptoException();
        }

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher;
    }

    private static byte[] buildAdditionalAuthenticatedData(
            UUID userId,
            UUID factorId,
            int keyVersion
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(AAD_DOMAIN.length + 16 + 16 + Integer.BYTES);
        buffer.put(AAD_DOMAIN);
        buffer.putLong(userId.getMostSignificantBits());
        buffer.putLong(userId.getLeastSignificantBits());
        buffer.putLong(factorId.getMostSignificantBits());
        buffer.putLong(factorId.getLeastSignificantBits());
        buffer.putInt(keyVersion);
        return buffer.array();
    }

    private Map<Integer, SecretKey> loadKeys(TotpSecretEncryptionProperties properties) {
        if (!properties.isEnabled()) {
            return Map.of();
        }
        if (properties.getActiveKeyVersion() < 1) {
            throw configurationError("active key version must be positive");
        }
        if (properties.getKeyring().isBlank()) {
            throw configurationError("keyring is required when encryption is enabled");
        }

        Map<Integer, SecretKey> parsedKeys = new HashMap<>();
        for (String rawEntry : properties.getKeyring().split(";")) {
            String entry = rawEntry.trim();
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw configurationError("keyring entry format is invalid");
            }

            int version;
            try {
                version = Integer.parseInt(entry.substring(0, separator).trim());
            } catch (NumberFormatException exception) {
                throw configurationError("key version is invalid");
            }
            if (version < 1 || parsedKeys.containsKey(version)) {
                throw configurationError("key versions must be positive and unique");
            }

            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
            } catch (IllegalArgumentException exception) {
                throw configurationError("key material must be valid Base64");
            }
            try {
                if (keyBytes.length != AES_256_KEY_BYTES) {
                    throw configurationError("each TOTP encryption key must be 32 bytes");
                }
                parsedKeys.put(version, new SecretKeySpec(keyBytes, "AES"));
            } finally {
                // SecretKeySpec이 자체 복사한 뒤에는 Base64 decode 임시 배열을 남기지 않는다.
                Arrays.fill(keyBytes, (byte) 0);
            }
        }

        if (!parsedKeys.containsKey(properties.getActiveKeyVersion())) {
            throw configurationError("active key version is missing from keyring");
        }
        return Map.copyOf(parsedKeys);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw configurationError("TOTP secret encryption is disabled");
        }
    }

    private static void requireContext(UUID userId, UUID factorId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(factorId, "factorId must not be null");
    }

    private static IllegalStateException configurationError(String safeMessage) {
        // keyring 원문은 절대 예외 메시지에 포함하지 않는다.
        return new IllegalStateException("Invalid TOTP encryption configuration: " + safeMessage);
    }
}
