package com.gilbom.defect;

import java.util.Locale;
import java.util.Set;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * 결함 행정 처리 상태의 전이 규칙이다.
 *
 * <p>defect-schema.md 2.1의 {@code NEW → ACCEPTED → IN_PROGRESS → RESOLVED} 흐름은
 * 권장 순서로 남기고, <b>전이는 단계에 묶지 않는다.</b> 현장 처리가 순서대로 흐르지
 * 않기 때문이다. 접수 절차 없이 바로 고쳐 완료하는 건이 있고, 완료한 뒤 재작업이
 * 필요해 처리 중으로 되돌려야 하는 건도 있다. 단계를 강제하면 담당자가 상태를
 * 실제와 다르게 남기거나 아예 갱신하지 않게 된다.
 *
 * <p>그래서 {@link #ASSIGNABLE_STATUSES} 안에서는 어느 상태에서든 서로 바꿀 수 있다.
 * {@code NEW}만 대상에서 빼둔다. 결함이 처음 적재될 때 시스템이 매기는 상태이고,
 * 사람이 "아직 안 본 것"으로 되돌리면 관측 이력과 어긋난다.
 *
 * <p>DB CHECK 제약은 값의 목록만 검사하므로 전이 판정은 여기서 하고, 허용되지 않는
 * 전이는 Swagger 21.10대로 409로 응답한다.
 */
final class DefectWorkflow {

    static final String NEW = "NEW";
    static final String ACCEPTED = "ACCEPTED";
    static final String IN_PROGRESS = "IN_PROGRESS";
    static final String RESOLVED = "RESOLVED";
    static final String FALSE_POSITIVE = "FALSE_POSITIVE";
    static final String REJECTED = "REJECTED";

    /**
     * 종결 상태. 처리 결과(resolution)를 가질 수 있고 결함 내용 수정은 막는다.
     *
     * <p>상태 전이는 막지 않는다. 종결에서 빠져나오려면 처리 상태부터 바꾸고,
     * 그다음에 내용을 고친다.
     */
    static final Set<String> TERMINAL_STATUSES =
            Set.of(RESOLVED, FALSE_POSITIVE, REJECTED);

    /**
     * 사람이 지정할 수 있는 처리 상태. {@code NEW}는 적재 시점에만 붙는다.
     */
    private static final Set<String> ASSIGNABLE_STATUSES =
            Set.of(ACCEPTED, IN_PROGRESS, RESOLVED, FALSE_POSITIVE, REJECTED);

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
     * 상태 전이가 허용되는지 검사한다. 막는 것은 두 가지뿐이다.
     * 지금과 같은 상태로의 요청(바뀌는 것이 없다)과 {@code NEW}로 되돌리는 요청이다.
     */
    static void requireTransitionAllowed(String from, String to) {
        if (from.equals(to)) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "이미 요청한 처리 상태입니다.");
        }
        if (!ASSIGNABLE_STATUSES.contains(to)) {
            throw new BusinessException(
                    ErrorCode.DEFECT_STATE_CONFLICT,
                    "신규 상태로는 되돌릴 수 없습니다.");
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
