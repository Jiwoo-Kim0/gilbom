package com.gilbom.auth.registration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DisplayNamePolicyTest {

    @Test
    void acceptsOneToOneHundredCodePointsWithoutTransforming() {
        assertThat(DisplayNamePolicy.isValid("길봄 사용자")).isTrue();
        assertThat(DisplayNamePolicy.isValid("😀".repeat(100))).isTrue();
        assertThat(DisplayNamePolicy.isValid("😀".repeat(101))).isFalse();
    }

    @Test
    void rejectsBlankEdgeWhitespaceAndControlCharacters() {
        assertThat(DisplayNamePolicy.isValid(null)).isFalse();
        assertThat(DisplayNamePolicy.isValid("")).isFalse();
        assertThat(DisplayNamePolicy.isValid(" 사용자")).isFalse();
        assertThat(DisplayNamePolicy.isValid("사용자 ")).isFalse();
        assertThat(DisplayNamePolicy.isValid("사용자\n이름")).isFalse();
    }
}
