package com.gilbom.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private final TotpService service = new TotpService(new SecureRandom());

    @Test
    void verifiesTheRfc6238Sha1VectorWithSixDigits() {
        byte[] secret =
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        OptionalLong result = service.verify(
                secret,
                "287082",
                Instant.ofEpochSecond(59),
                null
        );

        assertThat(result).hasValue(1);
    }

    @Test
    void acceptsOneAdjacentStepButRejectsAReusedStep() {
        byte[] secret =
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String previousStepCode = service.codeForTimeStep(secret, 99);

        assertThat(service.verify(
                secret,
                previousStepCode,
                Instant.ofEpochSecond(100 * 30),
                null
        )).hasValue(99);
        assertThat(service.verify(
                secret,
                previousStepCode,
                Instant.ofEpochSecond(100 * 30),
                99L
        )).isEmpty();
    }

    @Test
    void buildsAnInteroperableUriWithoutFormEncodingSpaces() {
        String uri = service.buildOtpAuthUri(
                "admin+robot@example.com",
                "JBSWY3DPEHPK3PXP"
        );

        assertThat(uri)
                .startsWith("otpauth://totp/GILBOM:")
                .contains("admin%2Brobot%40example.com")
                .contains("secret=JBSWY3DPEHPK3PXP")
                .contains("issuer=GILBOM")
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30")
                .doesNotContain("+robot");
    }
}
