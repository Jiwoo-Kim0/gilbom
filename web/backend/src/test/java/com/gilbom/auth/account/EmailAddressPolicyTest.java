package com.gilbom.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailAddressPolicyTest {

    @Test
    void validatesSyntaxAndNormalizesWithLocaleIndependentLowercase() {
        assertThat(EmailAddressPolicy.hasValidFormat("Admin.User+tag@Example.COM")).isTrue();
        assertThat(EmailAddressPolicy.normalize("Admin.User+tag@Example.COM"))
                .isEqualTo("admin.user+tag@example.com");
    }

    @Test
    void preservesProviderSpecificAliases() {
        assertThat(EmailAddressPolicy.normalize("admin+notice@example.com"))
                .isEqualTo("admin+notice@example.com");
        assertThat(EmailAddressPolicy.normalize("admin.notice@example.com"))
                .isEqualTo("admin.notice@example.com");
    }

    @Test
    void rejectsWhitespaceMalformedAndOversizedAddresses() {
        assertThat(EmailAddressPolicy.hasValidFormat(" admin@example.com")).isFalse();
        assertThat(EmailAddressPolicy.hasValidFormat("admin@example.com ")).isFalse();
        assertThat(EmailAddressPolicy.hasValidFormat("admin..user@example.com")).isFalse();
        assertThat(EmailAddressPolicy.hasValidFormat("admin@example")).isFalse();
        assertThat(EmailAddressPolicy.hasValidFormat(
                "a".repeat(65) + "@example.com")).isFalse();
        assertThat(EmailAddressPolicy.hasValidFormat(
                "a@" + "b".repeat(250) + ".com")).isFalse();
        assertThat(EmailAddressPolicy.hasValidFormat(null)).isFalse();

        assertThatThrownBy(() -> EmailAddressPolicy.normalize("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email format is invalid");
    }
}
