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
        assertThat(ErrorCode.COMMON_PAYLOAD_TOO_LARGE.httpStatus())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(ErrorCode.COMMON_BUSINESS_RULE_VIOLATION.httpStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.STATISTICS_PERIOD_INVALID.httpStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void reportAndLlmContractCodesKeepTheirDeclaredStatuses() {
        assertThat(ErrorCode.REPORT_ACCESS_DENIED.httpStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.REPORT_VALIDATION_FAILED.httpStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.httpStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.LLM_UNAVAILABLE.httpStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.LLM_RESPONSE_INVALID.httpStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
