package com.gilbom.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * Shared durable claim/replay store for non-secret state-changing requests.
 *
 * <p>The caller must keep claim, domain mutation, response snapshot, and audit
 * in one transaction. This makes a failed request remove its PROCESSING claim
 * through transaction rollback, so the same key can safely be retried.</p>
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyRequestStore {

    private static final String PROCESSING = "PROCESSING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyRequestStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Claim claim(
            Principal principal,
            UUID idempotencyKey,
            byte[] requestFingerprint,
            Endpoint endpoint
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey must not be null");
        Objects.requireNonNull(
                requestFingerprint,
                "requestFingerprint must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (requestFingerprint.length != 32) {
            throw new IllegalArgumentException(
                    "requestFingerprint must be SHA-256");
        }

        byte[] principalHash = sha256(
                principal.type()
                        + "\u0000"
                        + principal.key());
        byte[] keyHash = sha256(idempotencyKey.toString());

        deleteExpired(principalHash, keyHash);
        if (insertProcessing(
                principal,
                principalHash,
                keyHash,
                requestFingerprint,
                endpoint) == 1) {
            return Claim.owner(principalHash, keyHash);
        }

        ExistingRequest existing = findForUpdate(
                principalHash,
                keyHash);
        if (existing == null) {
            throw new IllegalStateException(
                    "Idempotency request disappeared");
        }
        requireSameRequest(
                existing,
                requestFingerprint,
                endpoint);

        if (FAILED.equals(existing.processingStatus())) {
            deleteFailed(principalHash, keyHash);
            if (insertProcessing(
                    principal,
                    principalHash,
                    keyHash,
                    requestFingerprint,
                    endpoint) != 1) {
                throw new IllegalStateException(
                        "Failed idempotency request was not reclaimed");
            }
            return Claim.owner(principalHash, keyHash);
        }
        if (PROCESSING.equals(existing.processingStatus())) {
            throw new BusinessException(
                    ErrorCode.COMMON_CONFLICT,
                    "동일한 요청이 처리 중입니다. 같은 Idempotency-Key로 다시 시도해 주세요.");
        }
        if (!SUCCEEDED.equals(existing.processingStatus())
                || existing.responseStatus() == null
                || existing.responseBody() == null) {
            throw new IllegalStateException(
                    "Completed idempotency response is invalid");
        }
        return Claim.replay(
                principalHash,
                keyHash,
                new StoredResponse(
                        existing.responseStatus(),
                        existing.responseHeaders(),
                        existing.responseBody(),
                        existing.resourceType(),
                        existing.resourceId()));
    }

    public void complete(
            Claim claim,
            int responseStatus,
            Map<String, String> safeResponseHeaders,
            String responseBodyJson,
            String resourceType,
            UUID resourceId
    ) {
        Objects.requireNonNull(claim, "claim must not be null");
        if (!claim.owner()) {
            throw new IllegalArgumentException(
                    "Only an owned idempotency claim can be completed");
        }
        if (responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException(
                    "responseStatus must be a valid HTTP status");
        }
        Objects.requireNonNull(
                safeResponseHeaders,
                "safeResponseHeaders must not be null");
        Objects.requireNonNull(
                responseBodyJson,
                "responseBodyJson must not be null");
        Objects.requireNonNull(
                resourceType,
                "resourceType must not be null");
        Objects.requireNonNull(
                resourceId,
                "resourceId must not be null");

        int updated = jdbcTemplate.update("""
                UPDATE request_control.idempotency_request
                SET processing_status = 'SUCCEEDED',
                    response_status = ?,
                    response_headers = CAST(? AS jsonb),
                    response_body = CAST(? AS jsonb),
                    resource_type = ?,
                    resource_id = ?,
                    updated_at = NOW()
                WHERE principal_key_hash = ?
                  AND idempotency_key_hash = ?
                  AND processing_status = 'PROCESSING'
                """,
                responseStatus,
                json(safeResponseHeaders),
                responseBodyJson,
                resourceType,
                resourceId,
                claim.principalHash(),
                claim.keyHash());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Idempotency response was not completed");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and 10000");
        }
        return jdbcTemplate.update("""
                DELETE FROM request_control.idempotency_request
                WHERE idempotency_request_id IN (
                    SELECT idempotency_request_id
                    FROM request_control.idempotency_request
                    WHERE expires_at <= NOW()
                    ORDER BY expires_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                """,
                batchSize);
    }

    private int insertProcessing(
            Principal principal,
            byte[] principalHash,
            byte[] keyHash,
            byte[] requestFingerprint,
            Endpoint endpoint
    ) {
        return jdbcTemplate.update("""
                INSERT INTO request_control.idempotency_request (
                    principal_type,
                    principal_key_hash,
                    idempotency_key_hash,
                    request_fingerprint,
                    request_method,
                    request_path,
                    lease_expires_at,
                    expires_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    NOW() + INTERVAL '1 minute',
                    NOW() + INTERVAL '24 hours'
                )
                ON CONFLICT (
                    principal_key_hash,
                    idempotency_key_hash
                ) DO NOTHING
                """,
                principal.type(),
                principalHash,
                keyHash,
                requestFingerprint,
                endpoint.method(),
                endpoint.path());
    }

    private void deleteExpired(
            byte[] principalHash,
            byte[] keyHash
    ) {
        jdbcTemplate.update("""
                DELETE FROM request_control.idempotency_request
                WHERE principal_key_hash = ?
                  AND idempotency_key_hash = ?
                  AND expires_at <= NOW()
                """,
                principalHash,
                keyHash);
    }

    private void deleteFailed(
            byte[] principalHash,
            byte[] keyHash
    ) {
        int deleted = jdbcTemplate.update("""
                DELETE FROM request_control.idempotency_request
                WHERE principal_key_hash = ?
                  AND idempotency_key_hash = ?
                  AND processing_status = 'FAILED'
                """,
                principalHash,
                keyHash);
        if (deleted != 1) {
            throw new IllegalStateException(
                    "Failed idempotency request was not deleted");
        }
    }

    private ExistingRequest findForUpdate(
            byte[] principalHash,
            byte[] keyHash
    ) {
        return jdbcTemplate.query("""
                        SELECT
                            request_fingerprint,
                            request_method,
                            request_path,
                            processing_status,
                            response_status,
                            response_headers::text AS response_headers,
                            response_body::text AS response_body,
                            resource_type,
                            resource_id
                        FROM request_control.idempotency_request
                        WHERE principal_key_hash = ?
                          AND idempotency_key_hash = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new ExistingRequest(
                                resultSet.getBytes(
                                        "request_fingerprint"),
                                resultSet.getString("request_method"),
                                resultSet.getString("request_path"),
                                resultSet.getString(
                                        "processing_status"),
                                resultSet.getObject(
                                        "response_status",
                                        Integer.class),
                                resultSet.getString(
                                        "response_headers"),
                                resultSet.getString("response_body"),
                                resultSet.getString("resource_type"),
                                resultSet.getObject(
                                        "resource_id",
                                        UUID.class))
                        : null,
                principalHash,
                keyHash);
    }

    private static void requireSameRequest(
            ExistingRequest existing,
            byte[] requestFingerprint,
            Endpoint endpoint
    ) {
        if (!Arrays.equals(
                existing.requestFingerprint(),
                requestFingerprint)
                || !endpoint.method().equals(
                        existing.requestMethod())
                || !endpoint.path().equals(existing.requestPath())) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Safe response metadata could not be serialized",
                    exception);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    public record Principal(String type, String key) {

        public Principal {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException(
                        "principal type must not be blank");
            }
            type = type.trim().toUpperCase(Locale.ROOT);
            if (!type.equals("USER")
                    && !type.equals("ROBOT")
                    && !type.equals("SYSTEM")) {
                throw new IllegalArgumentException(
                        "unsupported principal type");
            }
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "principal key must not be blank");
            }
            key = key.trim();
        }
    }

    public record Endpoint(String method, String path) {

        public Endpoint {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException(
                        "request method must not be blank");
            }
            method = method.trim().toUpperCase(Locale.ROOT);
            if (path == null
                    || path.isBlank()
                    || !path.startsWith("/")
                    || !path.equals(path.trim())) {
                throw new IllegalArgumentException(
                        "request path must be an absolute canonical path");
            }
        }
    }

    public record Claim(
            byte[] principalHash,
            byte[] keyHash,
            StoredResponse replay,
            boolean owner
    ) {

        public Claim {
            principalHash = principalHash.clone();
            keyHash = keyHash.clone();
        }

        @Override
        public byte[] principalHash() {
            return principalHash.clone();
        }

        @Override
        public byte[] keyHash() {
            return keyHash.clone();
        }

        static Claim owner(
                byte[] principalHash,
                byte[] keyHash
        ) {
            return new Claim(
                    principalHash,
                    keyHash,
                    null,
                    true);
        }

        static Claim replay(
                byte[] principalHash,
                byte[] keyHash,
                StoredResponse response
        ) {
            return new Claim(
                    principalHash,
                    keyHash,
                    response,
                    false);
        }
    }

    public record StoredResponse(
            int status,
            String headersJson,
            String bodyJson,
            String resourceType,
            UUID resourceId
    ) {
    }

    private record ExistingRequest(
            byte[] requestFingerprint,
            String requestMethod,
            String requestPath,
            String processingStatus,
            Integer responseStatus,
            String responseHeaders,
            String responseBody,
            String resourceType,
            UUID resourceId
    ) {
    }
}
