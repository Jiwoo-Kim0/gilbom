package com.gilbom.common.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON object field order and transport whitespace do not affect a request
 * fingerprint. Array order is retained because it can carry business meaning.
 */
@Component
public class CanonicalRequestFingerprint {

    private final ObjectMapper objectMapper;

    public CanonicalRequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
    }

    public byte[] hash(Object normalizedRequestBody) {
        Objects.requireNonNull(
                normalizedRequestBody,
                "normalizedRequestBody must not be null");
        try {
            JsonNode requestTree =
                    objectMapper.valueToTree(normalizedRequestBody);
            JsonNode canonicalTree = canonicalize(requestTree);
            return sha256(
                    objectMapper.writeValueAsBytes(canonicalTree));
        } catch (IllegalArgumentException
                | JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Request body could not be fingerprinted",
                    exception);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonicalObject =
                    objectMapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            fields.forEach(field -> canonicalObject.set(
                    field,
                    canonicalize(value.get(field))));
            return canonicalObject;
        }
        if (value.isArray()) {
            ArrayNode canonicalArray = objectMapper.createArrayNode();
            value.forEach(element ->
                    canonicalArray.add(canonicalize(element)));
            return canonicalArray;
        }
        return value;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }
}
