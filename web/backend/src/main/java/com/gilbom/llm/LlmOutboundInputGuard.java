package com.gilbom.llm;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * 외부 전송 직전의 마지막 방어선이다.
 *
 * <p>보고서 계층의 타입 기반 allowlist를 통과했더라도 Client가 다른 코드에서 직접
 * 호출될 수 있으므로 key와 문자열 값을 다시 검사한다. 어떤 패턴이 일치했는지는
 * 예외나 로그에 포함하지 않는다.</p>
 */
public final class LlmOutboundInputGuard {

    public static final int MAXIMUM_JSON_BYTES = 128 * 1024;
    public static final int MAXIMUM_COLLECTION_ITEMS = 100;
    private static final int MAXIMUM_NODES = 500;
    private static final int MAXIMUM_DEPTH = 8;

    private static final Pattern FORBIDDEN_KEY = Pattern.compile(
            "(?i).*(?:authorization|cookie|session|csrf|totp|password"
                    + "|passwd|secret|api.?key|private.?key|access.?key"
                    + "|presigned|bucket|object.?key|file.?path|stack.?trace"
                    + "|email|phone|user.?id|created.?by|updated.?by).*");
    private static final Pattern BEARER_OR_JWT = Pattern.compile(
            "(?i)(?:\\bbearer\\s+[A-Za-z0-9._~+/-]+=*"
                    + "|\\beyJ[A-Za-z0-9_-]{8,}\\."
                    + "[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?82[- .]?)?0?1[016789]"
                    + "[- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)");
    private static final Pattern URL_OR_PATH = Pattern.compile(
            "(?i)(?:https?://|s3://|file://|\\\\\\\\[A-Za-z0-9._-]+\\\\"
                    + "|(?:^|\\s)/(?:home|var|tmp|etc|opt)/)");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api.?key|password|passwd|secret|session.?id"
                    + "|csrf|totp|aws.?access.?key.?id"
                    + "|aws.?secret.?access.?key)\\s*[:=]\\s*\\S+");
    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?i)(?:ignore\\s+(?:all\\s+)?previous\\s+instructions"
                    + "|reveal\\s+(?:the\\s+)?system\\s+prompt"
                    + "|system\\s*prompt\\s*[:=]"
                    + "|developer\\s*prompt\\s*[:=])");

    private final ObjectMapper objectMapper;

    public LlmOutboundInputGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(Map<String, Object> input) {
        if (input == null) {
            throw invalid();
        }
        int visited = visit(input, 0, 0);
        if (visited > MAXIMUM_NODES) {
            throw invalid();
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(input);
            if (json.length > MAXIMUM_JSON_BYTES
                    || new String(json, StandardCharsets.UTF_8)
                            .isBlank()) {
                throw invalid();
            }
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    private static int visit(
            Object value,
            int depth,
            int visited
    ) {
        if (depth > MAXIMUM_DEPTH || visited > MAXIMUM_NODES) {
            throw invalid();
        }
        int next = visited + 1;
        if (value == null
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>) {
            return next;
        }
        if (value instanceof CharSequence text) {
            validateText(text.toString());
            return next;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > MAXIMUM_COLLECTION_ITEMS) {
                throw invalid();
            }
            int count = next;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || key.isBlank()
                        || FORBIDDEN_KEY.matcher(key).matches()) {
                    throw invalid();
                }
                count = visit(entry.getValue(), depth + 1, count);
            }
            return count;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > MAXIMUM_COLLECTION_ITEMS) {
                throw invalid();
            }
            int count = next;
            for (Object item : collection) {
                count = visit(item, depth + 1, count);
            }
            return count;
        }
        // 전체 DB row, 임의 bean, byte[] 같은 우회 타입은 허용하지 않는다.
        throw invalid();
    }

    private static void validateText(String value) {
        if (value.length() > 2_000
                || containsControlCharacter(value)
                || BEARER_OR_JWT.matcher(value).find()
                || EMAIL.matcher(value).find()
                || PHONE.matcher(value).find()
                || URL_OR_PATH.matcher(value).find()
                || CREDENTIAL_ASSIGNMENT.matcher(value).find()
                || PROMPT_INJECTION.matcher(value).find()) {
            throw invalid();
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                    && character != '\n'
                    && character != '\r'
                    && character != '\t') {
                return true;
            }
        }
        return false;
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.REPORT_VALIDATION_FAILED);
    }
}
