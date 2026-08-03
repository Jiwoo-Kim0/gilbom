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
    void acceptsOnlyThePreviousCurrentAndNextTimeSteps() {
        byte[] secret =
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        Instant currentTime = Instant.ofEpochSecond(100 * 30);

        assertThat(service.verify(
                secret,
                service.codeForTimeStep(secret, 99),
                currentTime,
                null
        )).hasValue(99);
        assertThat(service.verify(
                secret,
                service.codeForTimeStep(secret, 100),
                currentTime,
                null
        )).hasValue(100);
        assertThat(service.verify(
                secret,
                service.codeForTimeStep(secret, 101),
                currentTime,
                null
        )).hasValue(101);
        assertThat(service.verify(
                secret,
                service.codeForTimeStep(secret, 98),
                currentTime,
                null
        )).isEmpty();
        assertThat(service.verify(
                secret,
                service.codeForTimeStep(secret, 102),
                currentTime,
                null
        )).isEmpty();
    }

    @Test
    void rejectsAReusedTimeStep() {
        byte[] secret =
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String previousStepCode = service.codeForTimeStep(secret, 99);

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
