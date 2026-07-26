package com.gilbom.auth.mfa.crypto;

import java.util.UUID;

public interface TotpSecretEncryptionService {

    /**
     * TOTP raw secret을 암호화한다. 호출자는 암호화 후 plaintext 배열을 즉시 지워야 한다.
     */
    EncryptedTotpSecret encrypt(UUID userId, UUID factorId, byte[] plaintextSecret);

    /**
     * TOTP 검증에 사용할 raw secret을 반환한다. 호출자는 사용 후 반환 배열을 즉시 지워야 한다.
     */
    byte[] decrypt(UUID userId, UUID factorId, EncryptedTotpSecret encryptedSecret);
}
