package com.gilbom.auth.registration;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PublicRegistrationIdGenerator {

    private static final int RANDOM_BYTES = 18;

    private final SecureRandom secureRandom;

    @Autowired
    public PublicRegistrationIdGenerator() {
        this(new SecureRandom());
    }

    PublicRegistrationIdGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String nextUserCode() {
        byte[] randomBytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        return "USR-" + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
    }
}
