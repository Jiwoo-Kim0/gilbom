package com.gilbom.storage.url;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.gilbom.storage.StorageProperties;

/**
 * 이미지 조회 URL에 붙는 만료 서명 토큰을 발급·검증한다.
 *
 * <p>S3 presigned URL과 같은 역할이다. 로컬 디스크 단계에서도 이미지가 인증 없이
 * 열리지 않아야 하고, 프론트는 저장소가 무엇이든 받은 URL을 그대로
 * {@code <img src>}에 넣기만 하면 되도록 계약을 맞춘다.
 *
 * <p>토큰은 {@code <만료 epoch 초>.<base64url HMAC-SHA256>}이며 서명 대상에 imageId를
 * 포함한다. 다른 이미지의 토큰을 가져다 쓰면 검증에 실패한다.
 */
@Component
public class ImageUrlSigner {

    private static final Logger log =
            LoggerFactory.getLogger(ImageUrlSigner.class);

    private static final String ALGORITHM = "HmacSHA256";
    private static final int GENERATED_KEY_BYTES = 32;

    private final SecretKeySpec signingKey;
    private final StorageProperties properties;
    private final Clock clock;

    // 테스트가 Clock을 주입하는 생성자를 함께 두므로, 컨테이너가 쓸 생성자를 명시한다.
    @Autowired
    public ImageUrlSigner(StorageProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ImageUrlSigner(StorageProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = new SecretKeySpec(resolveKey(properties), ALGORITHM);
    }

    private static byte[] resolveKey(StorageProperties properties) {
        String configured = properties.getUrlSigningKeyBase64();
        if (!configured.isBlank()) {
            return Base64.getDecoder().decode(configured);
        }
        // 운영에서는 외부 주입을 강제해야 한다. 여기서 기동을 막지 않는 이유는 로컬
        // 개발과 테스트가 키 없이도 돌아가야 하기 때문이며, 대신 경고를 남긴다.
        log.warn("gilbom.storage.url-signing-key-base64 is not set. "
                + "Generating an ephemeral key: image URLs will stop working "
                + "after a restart and will not validate across instances.");
        byte[] generated = new byte[GENERATED_KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        return generated;
    }

    public SignedImageUrl issue(UUID imageId) {
        Instant expiresAt = clock.instant().plus(properties.getUrlTtl());
        long expiresAtEpochSecond = expiresAt.getEpochSecond();
        String token = expiresAtEpochSecond
                + "."
                + sign(imageId, expiresAtEpochSecond);
        return new SignedImageUrl(
                "/api/v1/images/" + imageId + "/content?token=" + token,
                Instant.ofEpochSecond(expiresAtEpochSecond));
    }

    public boolean verify(UUID imageId, String token) {
        if (token == null) {
            return false;
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return false;
        }
        long expiresAtEpochSecond;
        try {
            expiresAtEpochSecond =
                    Long.parseLong(token.substring(0, separator));
        } catch (NumberFormatException exception) {
            return false;
        }
        if (clock.instant().getEpochSecond() > expiresAtEpochSecond) {
            return false;
        }
        String presented = token.substring(separator + 1);
        String expected = sign(imageId, expiresAtEpochSecond);
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(UUID imageId, long expiresAtEpochSecond) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(signingKey);
            byte[] signature = mac.doFinal(
                    (imageId + ":" + expiresAtEpochSecond)
                            .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException(
                    "HMAC-SHA256 is required", exception);
        }
    }

    public record SignedImageUrl(String url, Instant expiresAt) {
    }
}
