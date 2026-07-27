package com.gilbom.auth.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RateLimitSubjectHasher {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RateLimitSubjectHasher.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final int keyVersion;

    @Autowired
    public RateLimitSubjectHasher(RateLimitKeyProperties properties) {
        this(properties, new SecureRandom());
    }

    RateLimitSubjectHasher(
            RateLimitKeyProperties properties,
            SecureRandom secureRandom
    ) {
        if (properties.getVersion() <= 0) {
            throw new IllegalStateException(
                    "Rate-limit HMAC key version must be positive");
        }

        byte[] keyBytes = decodeOrGenerateKey(properties, secureRandom);
        try {
            this.key = new SecretKeySpec(keyBytes, ALGORITHM);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
        this.keyVersion = properties.getVersion();
    }

    public HashedRateLimitSubject hash(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "Rate-limit subject must not be blank");
        }

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return new HashedRateLimitSubject(
                    mac.doFinal(subject.getBytes(StandardCharsets.UTF_8)),
                    keyVersion
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Rate-limit HMAC is unavailable",
                    exception
            );
        }
    }

    private static byte[] decodeOrGenerateKey(
            RateLimitKeyProperties properties,
            SecureRandom secureRandom
    ) {
        if (properties.getBase64().isBlank()) {
            if (properties.isExternalRequired()) {
                throw new IllegalStateException(
                        "External rate-limit HMAC key is required");
            }

            byte[] generated = new byte[MINIMUM_KEY_BYTES];
            secureRandom.nextBytes(generated);
            LOGGER.warn(
                    "External rate-limit HMAC key is absent; "
                            + "using a process-local development key");
            return generated;
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.getBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Rate-limit HMAC key must be valid Base64",
                    exception
            );
        }
        if (decoded.length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "Rate-limit HMAC key must contain at least 32 bytes");
        }
        return decoded;
    }

    public record HashedRateLimitSubject(byte[] value, int keyVersion) {

        public HashedRateLimitSubject {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }
}
