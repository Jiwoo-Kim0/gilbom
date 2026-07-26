package com.gilbom.auth.mfa.crypto;

/**
 * 암호화 데이터나 내부 provider 정보를 외부 메시지에 포함하지 않는 고정 오류다.
 */
public final class TotpSecretCryptoException extends RuntimeException {

    public TotpSecretCryptoException() {
        super("TOTP secret cryptographic operation failed");
    }
}
