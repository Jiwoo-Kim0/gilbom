package com.gilbom.storage.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.storage.StorageProperties;

@Service
public class RobotImageApiKeyIssuanceService {

    private static final int PREFIX_LENGTH = 12;
    private static final int COLLISION_RETRY_LIMIT = 5;

    private final JdbcTemplate jdbcTemplate;
    private final RobotImageApiKeySecretGenerator secretGenerator;
    private final StorageProperties storageProperties;

    public RobotImageApiKeyIssuanceService(
            JdbcTemplate jdbcTemplate,
            RobotImageApiKeySecretGenerator secretGenerator,
            StorageProperties storageProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretGenerator = secretGenerator;
        this.storageProperties = storageProperties;
    }

    @Transactional
    public IssuedRobotImageApiKey rotate(UUID robotId) {
        jdbcTemplate.update("""
                UPDATE storage.robot_image_api_key
                SET key_status = 'REVOKED',
                    revoked_at = clock_timestamp()
                WHERE robot_id = ?
                  AND key_status = 'ACTIVE'
                """,
                robotId);

        for (int attempt = 0; attempt < COLLISION_RETRY_LIMIT; attempt++) {
            String apiKey = secretGenerator.generate();
            String keyPrefix = apiKey.substring(
                    0,
                    Math.min(PREFIX_LENGTH, apiKey.length()));
            UUID apiKeyId = UUID.randomUUID();
            int inserted = jdbcTemplate.update("""
                    INSERT INTO storage.robot_image_api_key (
                        api_key_id,
                        robot_id,
                        key_prefix,
                        key_hash
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    apiKeyId,
                    robotId,
                    keyPrefix,
                    sha256Hex(apiKey));
            if (inserted == 1) {
                return new IssuedRobotImageApiKey(
                        apiKeyId,
                        storageProperties.getUploadUrl(),
                        apiKey,
                        keyPrefix);
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique robot image API Key");
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
