package com.gilbom.robot.credential;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class MqttCredentialSecretGenerator {

    static final int SECRET_BYTES = 32;

    private final SecureRandom secureRandom;

    public MqttCredentialSecretGenerator() {
        this(new SecureRandom());
    }

    MqttCredentialSecretGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(secret);
    }
}
