package com.gilbom.auth.mfa.crypto;

import java.util.Arrays;

/**
 * auth.mfa_factor의 secret_ciphertext, secret_nonce, secret_key_version과
 * 그대로 대응하는 암호화 결과다.
 */
public final class EncryptedTotpSecret {

    private final byte[] ciphertext;
    private final byte[] nonce;
    private final int keyVersion;

    public EncryptedTotpSecret(byte[] ciphertext, byte[] nonce, int keyVersion) {
        if (ciphertext == null || ciphertext.length < 17) {
            throw new IllegalArgumentException("ciphertext must include encrypted data and authentication tag");
        }
        if (nonce == null || nonce.length != 12) {
            throw new IllegalArgumentException("AES-GCM nonce must be 12 bytes");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("keyVersion must be positive");
        }

        // 호출자가 배열을 변경해 저장될 암호문이나 nonce를 훼손하지 못하게 복사한다.
        this.ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
        this.nonce = Arrays.copyOf(nonce, nonce.length);
        this.keyVersion = keyVersion;
    }

    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    public int keyVersion() {
        return keyVersion;
    }
}
