package com.gilbom.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsOnlyTenToTwentyFourUnicodeCodePoints() {
        assertThat(PasswordPolicy.hasValidLength("a".repeat(9))).isFalse();
        assertThat(PasswordPolicy.hasValidLength("a".repeat(10))).isTrue();
        assertThat(PasswordPolicy.hasValidLength("a".repeat(24))).isTrue();
        assertThat(PasswordPolicy.hasValidLength("a".repeat(25))).isFalse();
        assertThat(PasswordPolicy.hasValidLength("😀".repeat(24))).isTrue();
        assertThat(PasswordPolicy.hasValidLength("😀".repeat(25))).isFalse();
    }

    @Test
    void doesNotImposeCompositionRulesOrTrimThePassword() {
        assertThat(PasswordPolicy.hasValidLength("abcdefghij")).isTrue();
        assertThat(PasswordPolicy.hasValidLength("1234567890")).isTrue();
        assertThat(PasswordPolicy.hasValidLength("          ")).isTrue();
        assertThat(PasswordPolicy.hasValidLength(" 12345678 ")).isTrue();
        assertThat(PasswordPolicy.hasValidLength(null)).isFalse();
    }
}
