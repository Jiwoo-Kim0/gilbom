package com.gilbom.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

    @Test
    void errorCodesUseStableUpperSnakeCaseNamesAndHaveMessages() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.name()).matches("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+");
            assertThat(errorCode.defaultMessage()).isNotBlank();
        }
    }

    @Test
    void distinguishesRequestValidationConflictAndBusinessRuleViolation() {
        assertThat(ErrorCode.COMMON_VALIDATION_FAILED.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.COMMAND_STATE_CONFLICT.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.REPORT_STATE_CONFLICT.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.COMMON_BUSINESS_RULE_VIOLATION.httpStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        
    }
}
