package com.gilbom.storage.auth;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class RobotImageApiKeySecretGenerator {

    static final int SECRET_BYTES = 32;
    static final String NAMESPACE = "gik_";

    private final SecureRandom secureRandom;

    public RobotImageApiKeySecretGenerator() {
        this(new SecureRandom());
    }

    RobotImageApiKeySecretGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return NAMESPACE + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(secret);
    }
}
