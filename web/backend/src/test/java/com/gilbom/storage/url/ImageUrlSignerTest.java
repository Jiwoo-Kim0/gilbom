package com.gilbom.storage.url;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gilbom.storage.StorageProperties;
import com.gilbom.storage.url.ImageUrlSigner.SignedImageUrl;

class ImageUrlSignerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");
    private static final UUID IMAGE_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_IMAGE_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void issuesRelativeUrlWithExpiryFromTtl() {
        SignedImageUrl signed = signerAt(NOW).issue(IMAGE_ID);

        // 프론트는 same-origin 상대 경로만 사용한다(vite proxy, Nginx 모두 /api).
        assertThat(signed.url())
                .startsWith("/api/v1/images/" + IMAGE_ID + "/content?token=");
        assertThat(signed.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void acceptsTokenItIssued() {
        ImageUrlSigner signer = signerAt(NOW);

        assertThat(signer.verify(IMAGE_ID, tokenOf(signer.issue(IMAGE_ID))))
                .isTrue();
    }

    @Test
    void rejectsTokenAfterExpiry() {
        String token = tokenOf(signerAt(NOW).issue(IMAGE_ID));

        // 만료 1초 뒤. TTL 5분이므로 5분 1초 후다.
        assertThat(signerAt(NOW.plusSeconds(301)).verify(IMAGE_ID, token))
                .isFalse();
    }

    @Test
    void acceptsTokenOnTheLastSecondOfItsLifetime() {
        String token = tokenOf(signerAt(NOW).issue(IMAGE_ID));

        assertThat(signerAt(NOW.plusSeconds(300)).verify(IMAGE_ID, token))
                .isTrue();
    }

    @Test
    void rejectsTokenIssuedForAnotherImage() {
        ImageUrlSigner signer = signerAt(NOW);
        String token = tokenOf(signer.issue(OTHER_IMAGE_ID));

        // 서명 대상에 imageId가 들어가므로 다른 이미지의 토큰을 옮겨 쓸 수 없다.
        assertThat(signer.verify(IMAGE_ID, token)).isFalse();
    }

    @Test
    void rejectsTamperedExpiryAndSignature() {
        ImageUrlSigner signer = signerAt(NOW);
        String token = tokenOf(signer.issue(IMAGE_ID));
        String signature = token.substring(token.indexOf('.') + 1);

        // 만료만 늘린 토큰
        assertThat(signer.verify(IMAGE_ID, "99999999999." + signature)).isFalse();
        // 서명만 바꾼 토큰
        assertThat(signer.verify(
                IMAGE_ID,
                token.substring(0, token.indexOf('.') + 1) + "AAAA")).isFalse();
    }

    @Test
    void rejectsMalformedTokens() {
        ImageUrlSigner signer = signerAt(NOW);

        assertThat(signer.verify(IMAGE_ID, null)).isFalse();
        assertThat(signer.verify(IMAGE_ID, "")).isFalse();
        assertThat(signer.verify(IMAGE_ID, "no-separator")).isFalse();
        assertThat(signer.verify(IMAGE_ID, ".onlySignature")).isFalse();
        assertThat(signer.verify(IMAGE_ID, "123456789.")).isFalse();
        assertThat(signer.verify(IMAGE_ID, "notANumber.signature")).isFalse();
    }

    @Test
    void differentSigningKeysDoNotAcceptEachOthersTokens() {
        String token = tokenOf(signerAt(NOW).issue(IMAGE_ID));

        ImageUrlSigner otherKey = new ImageUrlSigner(
                properties(Base64.getEncoder().encodeToString(new byte[32])),
                fixedClock(NOW));

        assertThat(otherKey.verify(IMAGE_ID, token)).isFalse();
    }

    private static String tokenOf(SignedImageUrl signed) {
        return signed.url().substring(signed.url().indexOf("token=") + 6);
    }

    private static ImageUrlSigner signerAt(Instant instant) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return new ImageUrlSigner(
                properties(Base64.getEncoder().encodeToString(key)),
                fixedClock(instant));
    }

    private static StorageProperties properties(String keyBase64) {
        StorageProperties properties = new StorageProperties();
        properties.setUrlSigningKeyBase64(keyBase64);
        properties.setUrlTtl(Duration.ofMinutes(5));
        return properties;
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
