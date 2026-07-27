package com.gilbom.auth.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TotpService {

    static final String ISSUER = "GILBOM";
    static final String ALGORITHM = "SHA1";
    static final int SECRET_LENGTH_BYTES = 20;
    static final int DIGITS = 6;
    static final int TIME_STEP_SECONDS = 30;
    static final int ALLOWED_WINDOW_STEPS = 1;

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int MODULUS = 1_000_000;

    private final SecureRandom secureRandom;

    @Autowired
    public TotpService() {
        this(new SecureRandom());
    }

    TotpService(SecureRandom secureRandom) {
        this.secureRandom =
                Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    public GeneratedTotpSecret generateSecret() {
        byte[] secret = new byte[SECRET_LENGTH_BYTES];
        secureRandom.nextBytes(secret);
        return new GeneratedTotpSecret(
                secret,
                Base32Codec.encodeWithoutPadding(secret)
        );
    }

    public String buildOtpAuthUri(String accountEmail, String base32Secret) {
        if (accountEmail == null || accountEmail.isBlank()) {
            throw new IllegalArgumentException(
                    "TOTP account email must not be blank"
            );
        }
        if (base32Secret == null || base32Secret.isBlank()) {
            throw new IllegalArgumentException(
                    "TOTP Base32 secret must not be blank"
            );
        }

        String label = percentEncode(ISSUER) + ":"
                + percentEncode(accountEmail);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + percentEncode(ISSUER)
                + "&algorithm=" + ALGORITHM
                + "&digits=" + DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    public OptionalLong verify(
            byte[] secret,
            String submittedCode,
            Instant now,
            Long lastSuccessfulTimeStep
    ) {
        requireSecret(secret);
        if (submittedCode == null
                || !submittedCode.matches("^[0-9]{6}$")) {
            return OptionalLong.empty();
        }
        Objects.requireNonNull(now, "now must not be null");
        long currentStep =
                Math.floorDiv(now.getEpochSecond(), TIME_STEP_SECONDS);
        long minimumAllowedStep =
                lastSuccessfulTimeStep == null
                        ? -1
                        : lastSuccessfulTimeStep;

        int[] offsets = {0, -1, 1};
        for (int offset : offsets) {
            long candidateStep = currentStep + offset;
            if (candidateStep < 0 || candidateStep <= minimumAllowedStep) {
                continue;
            }
            String expectedCode = codeForTimeStep(secret, candidateStep);
            if (MessageDigest.isEqual(
                    expectedCode.getBytes(StandardCharsets.US_ASCII),
                    submittedCode.getBytes(StandardCharsets.US_ASCII)
            )) {
                return OptionalLong.of(candidateStep);
            }
        }
        return OptionalLong.empty();
    }

    String codeForTimeStep(byte[] secret, long timeStep) {
        requireSecret(secret);
        if (timeStep < 0) {
            throw new IllegalArgumentException(
                    "TOTP time step must not be negative"
            );
        }

        byte[] counter = ByteBuffer.allocate(Long.BYTES)
                .putLong(timeStep)
                .array();
        byte[] digest = null;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            digest = mac.doFinal(counter);
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return String.format(
                    Locale.ROOT,
                    "%0" + DIGITS + "d",
                    binary % MODULUS
            );
        } catch (GeneralSecurityException exception) {
            throw new TotpComputationException();
        } finally {
            Arrays.fill(counter, (byte) 0);
            if (digest != null) {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }

    private static void requireSecret(byte[] secret) {
        if (secret == null || secret.length < SECRET_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "TOTP secret must be at least 160 bits"
            );
        }
    }

    private static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte valueByte : bytes) {
            int unsigned = valueByte & 0xff;
            if ((unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-'
                    || unsigned == '.'
                    || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(
                        Character.forDigit((unsigned >> 4) & 0xf, 16)
                ));
                encoded.append(Character.toUpperCase(
                        Character.forDigit(unsigned & 0xf, 16)
                ));
            }
        }
        return encoded.toString();
    }

    public record GeneratedTotpSecret(
            byte[] rawSecret,
            String base32Secret
    ) {
        public GeneratedTotpSecret {
            Objects.requireNonNull(rawSecret, "rawSecret must not be null");
            Objects.requireNonNull(
                    base32Secret,
                    "base32Secret must not be null"
            );
            if (rawSecret.length != SECRET_LENGTH_BYTES) {
                throw new IllegalArgumentException(
                        "Generated TOTP secret length is invalid"
                );
            }
        }
    }
}
