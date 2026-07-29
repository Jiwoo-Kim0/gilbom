package com.gilbom.defect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class DefectRiskAssessmentTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T08:00:00Z");

    @Test
    void mapsDamageRatioToVersionedRiskBands() {
        assertRisk(0.00, "LOW", "0.00");
        assertRisk(0.2499, "LOW", "24.99");
        assertRisk(0.25, "MEDIUM", "25.00");
        assertRisk(0.50, "HIGH", "50.00");
        assertRisk(0.75, "CRITICAL", "75.00");
        assertRisk(1.00, "CRITICAL", "100.00");
    }

    @Test
    void missingDamageRatioKeepsNeutralLevelAndRequiresReview() {
        DefectRiskAssessment.Result result =
                DefectRiskAssessment.calculate(null, NOW);

        assertThat(result.level()).isEqualTo("MEDIUM");
        assertThat(result.score()).isNull();
        assertThat(result.ruleVersion()).isEqualTo("DAMAGE_RATIO_V1");
        assertThat(result.calculatedAt()).isEqualTo(NOW);
        assertThat(result.reviewRequired()).isTrue();
    }

    private static void assertRisk(
            double damageRatio,
            String level,
            String score
    ) {
        DefectRiskAssessment.Result result =
                DefectRiskAssessment.calculate(damageRatio, NOW);

        assertThat(result.level()).isEqualTo(level);
        assertThat(result.score()).isEqualByComparingTo(score);
        assertThat(result.ruleVersion()).isEqualTo("DAMAGE_RATIO_V1");
        assertThat(result.reviewRequired()).isFalse();
    }
}
