package com.gilbom.defect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * AI confidence와 분리된 서버 위험도 V1 규칙이다.
 *
 * <p>파손 면적 비율을 0~100 점수로 환산하고 사분위 구간으로 등급을 정한다.
 * 파손 면적이 없으면 임의 점수를 만들지 않고 검토 대상 MEDIUM으로 보존한다.
 */
final class DefectRiskAssessment {

    static final String RULE_VERSION = "DAMAGE_RATIO_V1";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MEDIUM_THRESHOLD = new BigDecimal("25");
    private static final BigDecimal HIGH_THRESHOLD = new BigDecimal("50");
    private static final BigDecimal CRITICAL_THRESHOLD = new BigDecimal("75");

    private DefectRiskAssessment() {
    }

    static Result calculate(Double damageRatio, Instant calculatedAt) {
        if (damageRatio == null) {
            return new Result(
                    "MEDIUM",
                    null,
                    RULE_VERSION,
                    calculatedAt,
                    true);
        }

        BigDecimal score = BigDecimal.valueOf(damageRatio)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
        String level;
        if (score.compareTo(CRITICAL_THRESHOLD) >= 0) {
            level = "CRITICAL";
        } else if (score.compareTo(HIGH_THRESHOLD) >= 0) {
            level = "HIGH";
        } else if (score.compareTo(MEDIUM_THRESHOLD) >= 0) {
            level = "MEDIUM";
        } else {
            level = "LOW";
        }
        return new Result(
                level,
                score,
                RULE_VERSION,
                calculatedAt,
                false);
    }

    record Result(
            String level,
            BigDecimal score,
            String ruleVersion,
            Instant calculatedAt,
            boolean reviewRequired
    ) {
    }
}
