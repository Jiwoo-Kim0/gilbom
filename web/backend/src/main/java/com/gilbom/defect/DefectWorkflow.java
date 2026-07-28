package com.gilbom.defect;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * 결함 행정 처리 상태의 전이 규칙이다.
 *
 * <p>defect-schema.md 2.1은 {@code NEW → ACCEPTED → IN_PROGRESS → RESOLVED},
 * 또는 {@code FALSE_POSITIVE}/{@code REJECTED}로 종결하는 흐름을 정의한다. DB
 * CHECK 제약은 값의 목록만 검사하고 순서는 검사하지 않으므로 전이 자체는 여기서
 * 판정하고, 허용되지 않는 전이는 Swagger 21.10대로 409로 응답한다.
 */
final class DefectWorkflow {

    static final String NEW = "NEW";
    static final String ACCEPTED = "ACCEPTED";
    static final String IN_PROGRESS = "IN_PROGRESS";
    static final String RESOLVED = "RESOLVED";
    static final String FALSE_POSITIVE = "FALSE_POSITIVE";
    static final String REJECTED = "REJECTED";

    /**
     * 종결 상태. 더 이상 전이하지 않으며 결함 내용 수정도 막는다.
     */
    static final Set<String> TERMINAL_STATUSES =
            Set.of(RESOLVED, FALSE_POSITIVE, REJECTED);

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            NEW, Set.of(ACCEPTED, FALSE_POSITIVE, REJECTED),
            ACCEPTED, Set.of(IN_PROGRESS, FALSE_POSITIVE, REJECTED),
            IN_PROGRESS, Set.of(RESOLVED, FALSE_POSITIVE, REJECTED),
            RESOLVED, Set.of(),
            FALSE_POSITIVE, Set.of(),
            REJECTED, Set.of());

    private DefectWorkflow() {
    }

    static String requireKnownStatus(String rawStatus) {
        String status = normalize(rawStatus);
        if (!DefectFilter.WORKFLOW_STATUSES.contains(status)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "지원하지 않는 workflowStatus 값입니다.");
        }
        return status;
    }

    static String requireKnownResolution(String rawResolution) {
        if (rawResolution == null || rawResolution.isBlank()) {
            return null;
        }
        String resolution = normalize(rawResolution);
        if (!DefectFilter.RESOLUTIONS.contains(resolution)) {
            throw new BusinessException(
                    ErrorCode.COMMON_INVALID_REQUEST,
                    "지원하지 않는 resolution 값입니다.");
        }
        return resolution;
    }

    /**
     * 상태 전이가 허용되는지 검사한다.
     */
    static void requireTransitionAllowed(String from, String to) {
        if (from.equals(to)) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "이미 요청한 처리 상태입니다.");
        }
        if (!ALLOWED_TRANSITIONS
                .getOrDefault(from, Set.of())
                .contains(to)) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "허용되지 않는 상태 전이입니다.");
        }
    }

    /**
     * resolution은 종결 상태의 처리 결과이므로 종결 전이에만 쓴다.
     * RESOLVED는 무엇으로 처리했는지 남기지 않으면 보고서를 만들 수 없어 필수로 둔다.
     */
    static void requireResolutionConsistent(String status, String resolution) {
        boolean terminal = TERMINAL_STATUSES.contains(status);
        if (!terminal && resolution != null) {
            throw new BusinessException(
                    ErrorCode.COMMON_BUSINESS_RULE_VIOLATION,
                    "처리 결과는 종결 상태에만 지정할 수 있습니다.");
        }
        if (RESOLVED.equals(status) && resolution == null) {
            throw new BusinessException(
                    ErrorCode.COMMON_BUSINESS_RULE_VIOLATION,
                    "완료 처리에는 처리 결과가 필요합니다.");
        }
    }

    static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
