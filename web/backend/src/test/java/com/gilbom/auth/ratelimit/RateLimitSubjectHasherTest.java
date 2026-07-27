package com.gilbom.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RateLimitSubjectHasherTest {

    @Test
    void springContextUsesThePropertiesConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    RateLimitKeyProperties.class,
                    RateLimitKeyProperties::new
            );
            context.register(RateLimitSubjectHasher.class);
            context.refresh();

            assertThat(context.getBean(RateLimitSubjectHasher.class)).isNotNull();
        }
    }

    @Test
    void createsStableVersionedHmacWithoutStoringTheRawIp() {
        RateLimitKeyProperties properties = propertiesWithKey((byte) 7);
        properties.setVersion(3);
        RateLimitSubjectHasher hasher =
                new RateLimitSubjectHasher(properties, new SecureRandom());

        RateLimitSubjectHasher.HashedRateLimitSubject first =
                hasher.hash("ip:203.0.113.10");
        RateLimitSubjectHasher.HashedRateLimitSubject second =
                hasher.hash("ip:203.0.113.10");

        assertThat(first.keyVersion()).isEqualTo(3);
        assertThat(first.value()).hasSize(32).isEqualTo(second.value());
        assertThat(new String(first.value()))
                .doesNotContain("203.0.113.10");

        byte[] externallyMutableCopy = first.value();
        externallyMutableCopy[0] ^= 1;
        assertThat(first.value()).isEqualTo(second.value());
    }

    @Test
    void productionRequirementFailsClosedWithoutAnExternalKey() {
        RateLimitKeyProperties properties = new RateLimitKeyProperties();
        properties.setExternalRequired(true);

        assertThatThrownBy(() ->
                new RateLimitSubjectHasher(properties, new SecureRandom()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("External rate-limit HMAC key is required");
    }

    @Test
    void rejectsKeysShorterThanThirtyTwoBytes() {
        RateLimitKeyProperties properties = new RateLimitKeyProperties();
        properties.setBase64(Base64.getEncoder().encodeToString(new byte[31]));

        assertThatThrownBy(() ->
                new RateLimitSubjectHasher(properties, new SecureRandom()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rate-limit HMAC key must contain at least 32 bytes");
    }

    private static RateLimitKeyProperties propertiesWithKey(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        RateLimitKeyProperties properties = new RateLimitKeyProperties();
        properties.setBase64(Base64.getEncoder().encodeToString(key));
        return properties;
    }
}
