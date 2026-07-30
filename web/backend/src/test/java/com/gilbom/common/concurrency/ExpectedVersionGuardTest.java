package com.gilbom.common.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class ExpectedVersionGuardTest {

    private final ExpectedVersionGuard guard =
            new ExpectedVersionGuard();

    @Test
    void acceptsOnlyTheLockedCurrentVersion() {
        assertThatCode(() -> guard.requireMatch(
                3,
                3,
                ErrorCode.REPORT_STATE_CONFLICT))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> guard.requireMatch(
                2,
                3,
                ErrorCode.REPORT_STATE_CONFLICT))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.errorCode())
                                    .isEqualTo(
                                            ErrorCode
                                                    .REPORT_STATE_CONFLICT);
                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            ErrorCode
                                                    .REPORT_STATE_CONFLICT
                                                    .defaultMessage())
                                    .doesNotContain("2", "3");
                        });
    }
}
