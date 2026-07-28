package com.gilbom.audit;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuditSnapshot {

    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password",
            "passwd",
            "passcode",
            "token",
            "accesstoken",
            "refreshtoken",
            "secret",
            "apikey",
            "xapikey",
            "authorization",
            "credential",
            "cookie",
            "setcookie",
            "session",
            "sessionid",
            "csrf",
            "totp",
            "otp",
            "recoverycode",
            "privatekey",
            "presignedurl"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|passcode|access[_-]?token"
                    + "|refresh[_-]?token|token|secret|api[_-]?key"
                    + "|authorization|session[_-]?id|csrf|totp|otp"
                    + "|recovery[_-]?code|private[_-]?key)\\b"
                    + "(\\s*[:=]\\s*)"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s,;]+)"
    );
    private static final Pattern BEARER_VALUE = Pattern.compile(
            "(?i)\\bBearer\\s+[^\\s,;]+"
    );

    private final Map<String, Object> values;

    private AuditSnapshot(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(
                new LinkedHashMap<>(values)
        );
    }

    public static AuditSnapshot allow(
            Set<String> allowedFields,
            Map<String, ?> rawValues
    ) {
        if (allowedFields == null || rawValues == null) {
            throw new IllegalArgumentException(
                    "Audit allowlist and values must not be null");
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (String field : allowedFields) {
            if (rawValues.containsKey(field)) {
                sanitized.put(
                        field,
                        sanitizeValue(field, rawValues.get(field))
                );
            }
        }
        return new AuditSnapshot(sanitized);
    }

    public Map<String, Object> values() {
        return values;
    }

    private static Object sanitizeValue(String field, Object value) {
        if (isSensitive(field)) {
            return REDACTED;
        }
        if (value == null
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof UUID
                || value instanceof TemporalAccessor) {
            return value;
        }
        if (value instanceof String text) {
            return redactText(text);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                String nestedKey = String.valueOf(key);
                nested.put(
                        nestedKey,
                        sanitizeValue(nestedKey, nestedValue)
                );
            });
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>(collection.size());
            collection.forEach(item ->
                    sanitized.add(sanitizeValue("", item)));
            return Collections.unmodifiableList(sanitized);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                sanitized.add(sanitizeValue("", Array.get(value, index)));
            }
            return Collections.unmodifiableList(sanitized);
        }
        throw new IllegalArgumentException(
                "Unsupported audit snapshot value type");
    }

    private static boolean isSensitive(String field) {
        String normalized = field.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        return normalized.endsWith("password")
                || normalized.endsWith("passwd")
                || normalized.endsWith("passcode")
                || normalized.endsWith("token")
                || normalized.endsWith("secret")
                || normalized.endsWith("apikey")
                || normalized.endsWith("authorization")
                || normalized.endsWith("cookie")
                || normalized.endsWith("sessionid")
                || normalized.endsWith("csrf")
                || normalized.endsWith("totp")
                || normalized.endsWith("otp")
                || normalized.endsWith("recoverycode")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("presignedurl");
    }

    static String redactText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        Matcher assignmentMatcher =
                SENSITIVE_ASSIGNMENT.matcher(value);
        String assignmentsRedacted = assignmentMatcher.replaceAll(
                match -> match.group(1)
                        + match.group(2)
                        + REDACTED
        );
        return BEARER_VALUE.matcher(assignmentsRedacted)
                .replaceAll("Bearer " + REDACTED);
    }
}
